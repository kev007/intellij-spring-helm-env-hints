package com.github.kev007.intellijspringhelmenvhints.navigation

import com.intellij.openapi.diagnostic.thisLogger
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
import com.intellij.openapi.project.Project
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
            val envVar = EnvVarMappingSupport.springEnvVarAtOffset(file.text, element.textOffset) ?: return PsiReference.EMPTY_ARRAY
            return arrayOf(EnvVarMappingPsiReference(element, envVar, EnvVarMappingSupport::findHelmEnvTargets))
        }

        if (EnvVarMappingSupport.isHelmTemplateFile(virtualFile)) {
            val envVar = EnvVarMappingSupport.envVarAtOffset(file, element.textOffset) ?: return PsiReference.EMPTY_ARRAY
            return arrayOf(EnvVarMappingPsiReference(element, envVar, EnvVarMappingSupport::findSpringTargets))
        }

        return PsiReference.EMPTY_ARRAY
    }
}

private class EnvVarMappingPsiReference(
    element: PsiElement,
    private val mappedName: String,
    private val targetResolver: (Project, String) -> List<PsiElement>,
) : PsiPolyVariantReferenceBase<PsiElement>(element, TextRange(0, element.textLength), false) {

    private val logger = thisLogger()

    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> {
        val targets = targetResolver(element.project, mappedName)

        targets.forEach { target ->
            val path = target.containingFile?.virtualFile?.path ?: "<no-file>"
            logger.debug("Resolved mapping reference '$mappedName' to $path:${target.textRange?.startOffset ?: -1}")
        }

        return targets.map { PsiElementResolveResult(it) }.toTypedArray()
    }

    override fun getVariants(): Array<Any> = emptyArray()
}


