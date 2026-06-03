package com.github.kev007.intellijspringhelmenvhints.navigation

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement

class EnvVarMappingGotoDeclarationHandler : GotoDeclarationHandler {

    override fun getGotoDeclarationTargets(
        sourceElement: PsiElement?,
        offset: Int,
        editor: Editor,
    ): Array<PsiElement>? {
        if (sourceElement == null) return null

        val file = sourceElement.containingFile ?: return null
        val virtualFile = file.virtualFile ?: return null
        if (!EnvVarMappingSupport.isYamlFile(virtualFile)) return null

        val existingTargets = EnvVarMappingPsiUtils.existingReferenceTargets(sourceElement, file, offset)
        val mappingTargets = EnvVarMappingSupport.resolveMappedTargets(file, virtualFile, offset)
        val mergedTargets = EnvVarMappingPsiUtils.distinctTargets(existingTargets + mappingTargets)

        return mergedTargets.toTypedArray().takeIf { it.isNotEmpty() }
    }
}
