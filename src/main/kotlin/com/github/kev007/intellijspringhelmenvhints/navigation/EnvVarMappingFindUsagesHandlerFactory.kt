package com.github.kev007.intellijspringhelmenvhints.navigation

import com.github.kev007.intellijspringhelmenvhints.core.EnvVarMappingCore
import com.github.kev007.intellijspringhelmenvhints.services.EnvVarRegistryService
import com.intellij.find.findUsages.FindUsagesHandler
import com.intellij.find.findUsages.FindUsagesHandlerFactory
import com.intellij.psi.PsiElement

/**
 * Factory for creating Find Usages handlers for env vars.
 * Returns a handler with env var definitions (Helm values + Spring keys) as primary elements.
 */
class EnvVarMappingFindUsagesHandlerFactory : FindUsagesHandlerFactory() {

    override fun canFindUsages(element: PsiElement): Boolean {
        val file = element.containingFile ?: return false
        val virtualFile = file.virtualFile ?: return false
        if (!EnvVarMappingCore.isYamlFile(virtualFile)) return false
        return EnvVarMappingCore.envVarForElement(element) != null
    }

    override fun createFindUsagesHandler(element: PsiElement, forHighlightUsages: Boolean): FindUsagesHandler? {
        val file = element.containingFile ?: return null
        val virtualFile = file.virtualFile ?: return null
        if (!EnvVarMappingCore.isYamlFile(virtualFile)) return null

        val envVar = EnvVarMappingCore.envVarForElement(element) ?: return null
        val service = EnvVarRegistryService.getInstance(element.project)

        // Helm values are the canonical "declarations" for an env var.
        // Spring key elements are treated as co-declarations (equivalent property bindings).
        // Returning all of them as primary elements lets ReferencesSearch find every
        // cross-file usage from each declaration site.
        val primary = EnvVarMappingPsiUtils.distinctTargets(
            service.getHelmValues(envVar) + service.getSpringKeys(envVar),
        ).ifEmpty { return null }

        return EnvVarMappingFindUsagesHandler(primary)
    }
}

/**
 * Handler for finding all usages of an env var across Spring and Helm files.
 * Primary elements are the actual definitions (Helm env names and Spring property keys).
 */
private class EnvVarMappingFindUsagesHandler(
    private val primaryElements: List<PsiElement>,
) : FindUsagesHandler(primaryElements.first()) {

    override fun getPrimaryElements(): Array<PsiElement> = primaryElements.toTypedArray()

    override fun getSecondaryElements(): Array<PsiElement> = emptyArray()
}
