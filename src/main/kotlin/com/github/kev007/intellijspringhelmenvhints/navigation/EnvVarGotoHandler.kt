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
 * from the opposite file system, enabling navigation from YAML keys (not just values).
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

        return excludingSelf(deduplicated(fromRefs + fromMapping), vf)
            .toTypedArray().takeIf { it.isNotEmpty() }
    }
}


