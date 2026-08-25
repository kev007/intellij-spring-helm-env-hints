package com.github.kev007.intellijspringhelmenvhints.navigation

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.module.ModuleUtil
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiPolyVariantReference

/**
 * Handles Ctrl+B / Cmd+B Go-to-Declaration for Spring ↔ Helm env var cross-navigation.
 *
 * Merges any PSI references already attached at the caret position with targets resolved
 * from the opposite file system, enabling navigation from YAML keys (not just values), plus
 * every target a Spring placeholder resolves to inside its own `application*.yaml` or through
 * the IDE's own placeholder references (a key declared in `application-local.yml`, …).
 *
 * Collecting the latter is not optional: the platform stops at the FIRST goto handler that
 * returns a non-empty result and skips its own reference-based resolution, so anything left
 * out here would become unreachable.
 */
class EnvVarGotoHandler : GotoDeclarationHandler {

    override fun getGotoDeclarationTargets(
        source: PsiElement?,
        offset: Int,
        editor: Editor,
    ): Array<PsiElement>? {
        source ?: return null
        val file = source.containingFile ?: return null
        val vf = file.virtualFile ?: return null
        if (!vf.isYaml()) return null

        val module = ModuleUtil.findModuleForPsiElement(source) ?: return null

        // Targets already resolved by reference contributor
        val fromRefs = file.findReferenceAt(offset)?.let { ref ->
            if (ref is PsiPolyVariantReference) ref.multiResolve(false).mapNotNull { it.element }
            else listOfNotNull(ref.resolve())
        }.orEmpty()

        // Targets from the opposite file system (handles key positions not covered by references)
        val fromMapping = when {
            vf.isSpringApp() -> springEnvVarAtOffset(file, offset)
                ?.let { findHelmTargets(it, source.project, module) }.orEmpty()
            vf.isHelmTemplate() -> helmEnvVarAtOffset(file, offset)
                ?.let { findSpringTargets(it, source.project, module) }.orEmpty()
            else -> emptyList()
        }

        // Everything a `${...}` placeholder resolves to besides a Helm entry: keys of this very
        // file, and the declarations the IDE itself finds (possibly several, across profile
        // files). Those targets may live in the source file, so they are added AFTER the
        // self-loop filter, which only concerns the cross-file Spring ↔ Helm directions.
        val placeholder = if (vf.isSpringApp()) {
            envOccurrenceAt(file, offset)?.let { springPlaceholderTargets(source, it) }.orEmpty()
        } else emptyList()

        // References inside one application.yaml stay resolvable, but never onto the line the
        // caret is on: that target is the reference itself and navigating to it does nothing.
        return excludingSameLine(
            deduplicated(excludingSelf(fromRefs + fromMapping, vf) + placeholder),
            file,
            offset,
        ).toTypedArray().takeIf { it.isNotEmpty() }
    }
}


