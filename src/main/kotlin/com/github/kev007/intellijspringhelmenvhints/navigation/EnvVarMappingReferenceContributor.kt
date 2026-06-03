package com.github.kev007.intellijspringhelmenvhints.navigation

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceContributor
import com.intellij.psi.PsiReferenceProvider
import com.intellij.psi.PsiReferenceRegistrar
import com.intellij.psi.PsiPolyVariantReferenceBase
import com.intellij.psi.ResolveResult
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

        val query = EnvVarMappingSupport.mappingQueryAtOffset(file, virtualFile, element.textOffset)
            ?: return PsiReference.EMPTY_ARRAY

        return arrayOf(EnvVarMappingPsiReference(element, query.envVar, query.targetResolver))
    }
}

private class EnvVarMappingPsiReference(
    element: PsiElement,
    private val mappedName: String,
    private val targetResolver: (Project, String) -> List<PsiElement>,
) : PsiPolyVariantReferenceBase<PsiElement>(element, TextRange(0, element.textLength), false) {

    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> {
        return targetResolver(element.project, mappedName)
            .map(::PsiElementResolveResult)
            .toTypedArray()
    }

    override fun getVariants(): Array<Any> = emptyArray()
}


