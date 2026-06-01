package com.github.kev007.intellijspringhelmenvhints.navigation

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.actionSystem.DataContext
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

        val project = sourceElement.project

        if (EnvVarMappingSupport.isSpringApplicationFile(virtualFile)) {
            val springKey = EnvVarMappingSupport.springKeyAtOffset(file.text, offset) ?: return null
            val envVar = EnvVarMappingSupport.springKeyToEnvVarName(springKey)
            return EnvVarMappingSupport.findHelmEnvTargets(project, envVar)
                .toTypedArray()
                .takeIf { it.isNotEmpty() }
        }

        if (EnvVarMappingSupport.isHelmTemplateFile(virtualFile)) {
            val envVar = EnvVarMappingSupport.envVarAtOffset(file.text, offset) ?: return null
            return EnvVarMappingSupport.findSpringTargets(project, envVar)
                .toTypedArray()
                .takeIf { it.isNotEmpty() }
        }

        return null
    }

    override fun getActionText(context: DataContext): String = "Go to mapped Spring/Helm variable"
}
