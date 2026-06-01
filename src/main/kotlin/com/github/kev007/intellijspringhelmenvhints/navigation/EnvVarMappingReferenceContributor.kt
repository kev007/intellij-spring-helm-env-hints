package com.github.kev007.intellijspringhelmenvhints.navigation

import com.intellij.openapi.util.TextRange
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceContributor
import com.intellij.psi.PsiReferenceProvider
import com.intellij.psi.PsiReferenceRegistrar
import com.intellij.psi.PsiPolyVariantReferenceBase
import com.intellij.psi.ResolveResult
import com.intellij.psi.PsiElementResolveResult
import com.intellij.util.ProcessingContext
import org.jetbrains.yaml.YAMLLanguage

class EnvVarMappingReferenceContributor : PsiReferenceContributor() {

    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        registrar.registerReferenceProvider(
            PlatformPatterns.psiElement(PsiElement::class.java).withLanguage(YAMLLanguage.INSTANCE),
            EnvVarMappingReferenceProvider(),
        )
    }
}

private class EnvVarMappingReferenceProvider : PsiReferenceProvider() {

    override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<PsiReference> {
        val file = element.containingFile ?: return PsiReference.EMPTY_ARRAY
        val virtualFile = file.virtualFile ?: return PsiReference.EMPTY_ARRAY
        if (!EnvVarMappingSupport.isYamlFile(virtualFile)) return PsiReference.EMPTY_ARRAY

        if (EnvVarMappingSupport.isSpringApplicationFile(virtualFile)) {
            val springKey = EnvVarMappingSupport.springKeyAtOffset(file.text, element.textOffset) ?: return PsiReference.EMPTY_ARRAY
            val envVar = EnvVarMappingSupport.springKeyToEnvVarName(springKey)
            return arrayOf(EnvVarMappingPsiReference(element, envVar, MappingDirection.SPRING_TO_HELM))
        }

        if (EnvVarMappingSupport.isHelmTemplateFile(virtualFile)) {
            val envVar = EnvVarMappingSupport.envVarAtOffset(file.text, element.textOffset) ?: return PsiReference.EMPTY_ARRAY
            return arrayOf(EnvVarMappingPsiReference(element, envVar, MappingDirection.HELM_TO_SPRING))
        }

        return PsiReference.EMPTY_ARRAY
    }
}

private class EnvVarMappingPsiReference(
    element: PsiElement,
    private val mappedName: String,
    private val direction: MappingDirection,
) : PsiPolyVariantReferenceBase<PsiElement>(element, TextRange(0, element.textLength), false) {

    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> {
        val project = element.project
        val targets = when (direction) {
            MappingDirection.SPRING_TO_HELM -> EnvVarMappingSupport.findHelmEnvTargets(project, mappedName)
            MappingDirection.HELM_TO_SPRING -> EnvVarMappingSupport.findSpringTargets(project, mappedName)
        }

        return targets.map { PsiElementResolveResult(it) }.toTypedArray()
    }

    override fun getVariants(): Array<Any> = emptyArray()
}

private enum class MappingDirection {
    SPRING_TO_HELM,
    HELM_TO_SPRING,
}

