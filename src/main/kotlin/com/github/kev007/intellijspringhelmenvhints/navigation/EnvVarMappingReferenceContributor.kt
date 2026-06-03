package com.github.kev007.intellijspringhelmenvhints.navigation

import com.github.kev007.intellijspringhelmenvhints.core.EnvVarMappingCore
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.ElementManipulators
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

/**
 * Registers bidirectional env var references between Spring and Helm YAML files.
 *
 * For Spring values: creates references to Helm env names
 * For Helm env names: creates references to Spring property keys
 * This enables navigation with Ctrl+Click, rename with Shift+F6, and highlight usages.
 */
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
        // Only process leaf elements; skip containers
        if (element.firstChild != null || element.textLength == 0) return PsiReference.EMPTY_ARRAY

        val file = element.containingFile ?: return PsiReference.EMPTY_ARRAY
        val virtualFile = file.virtualFile ?: return PsiReference.EMPTY_ARRAY
        if (!EnvVarMappingCore.isYamlFile(virtualFile)) return PsiReference.EMPTY_ARRAY

        if (EnvVarMappingCore.isSpringApplicationFile(virtualFile)) {
            // Spring values: ${ENV_VAR} → Helm env names
            val ref = EnvVarMappingCore.springEnvReferenceAtOffset(element, element.textOffset)
                ?: return PsiReference.EMPTY_ARRAY
            return arrayOf(
                EnvVarMappingPsiReference(
                    element = element,
                    mappedName = ref.envVar,
                    rangeInElement = ref.rangeInElement,
                    targetResolver = EnvVarMappingSupport::findHelmEnvTargets,
                ),
            )
        }

        if (EnvVarMappingCore.isHelmTemplateFile(virtualFile)) {
            // Helm env names → Spring property keys
            val span = EnvVarMappingCore.helmEnvNameSpan(element) ?: return PsiReference.EMPTY_ARRAY
            val range = TextRange(
                span.startOffset - element.textRange.startOffset,
                span.endOffset - element.textRange.startOffset,
            )
            return arrayOf(
                EnvVarMappingPsiReference(
                    element = element,
                    mappedName = span.envVar,
                    rangeInElement = range,
                    targetResolver = EnvVarMappingSupport::findSpringTargets,
                ),
            )
        }

        return PsiReference.EMPTY_ARRAY
    }
}

/**
 * A cross-file reference from a Spring or Helm env var to the opposite file system.
 * Supports rename/refactor and resolves to all matching targets.
 */
private class EnvVarMappingPsiReference(
    element: PsiElement,
    private val mappedName: String,
    rangeInElement: TextRange,
    private val targetResolver: (Project, String) -> List<PsiElement>,
) : PsiPolyVariantReferenceBase<PsiElement>(element, rangeInElement, false) {

    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> {
        return targetResolver(element.project, mappedName)
            .map(::PsiElementResolveResult)
            .toTypedArray()
    }

    override fun handleElementRename(newElementName: String): PsiElement {
        return ElementManipulators.handleContentChange(element, rangeInElement, newElementName)
    }

    override fun getVariants(): Array<Any> = emptyArray()
}


