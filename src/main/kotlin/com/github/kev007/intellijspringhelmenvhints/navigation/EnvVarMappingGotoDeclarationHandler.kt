package com.github.kev007.intellijspringhelmenvhints.navigation

import com.github.kev007.intellijspringhelmenvhints.core.EnvVarMappingCore
import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement

/**
 * Adds "Go to Declaration" support for env var cross-file navigation.
 * Merges both:
 * - Standard PSI references from the ReferenceContributor
 * - Mapped targets from the opposite file system (Spring ↔ Helm)
 *
 * Enables Ctrl+B (or Cmd+B) to navigate between Spring env vars and Helm env names.
 */
class EnvVarMappingGotoDeclarationHandler : GotoDeclarationHandler {

    override fun getGotoDeclarationTargets(
        sourceElement: PsiElement?,
        offset: Int,
        editor: Editor,
    ): Array<PsiElement>? {
        if (sourceElement == null) return null

        val file = sourceElement.containingFile ?: return null
        val virtualFile = file.virtualFile ?: return null
        if (!EnvVarMappingCore.isYamlFile(virtualFile)) return null

        val existingTargets = EnvVarMappingPsiUtils.existingReferenceTargets(sourceElement, file, offset)
        val mappingTargets = EnvVarMappingSupport.resolveMappedTargets(file, virtualFile, offset)
        val mergedTargets = EnvVarMappingPsiUtils.distinctTargets(existingTargets + mappingTargets)

        return mergedTargets.toTypedArray().takeIf { it.isNotEmpty() }
    }
}
