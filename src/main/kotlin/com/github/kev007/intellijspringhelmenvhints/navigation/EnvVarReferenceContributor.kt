package com.github.kev007.intellijspringhelmenvhints.navigation

import com.intellij.openapi.module.ModuleUtil
import com.intellij.openapi.util.TextRange
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.ElementManipulators
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.PsiPolyVariantReferenceBase
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceContributor
import com.intellij.psi.PsiReferenceProvider
import com.intellij.psi.PsiReferenceRegistrar
import com.intellij.psi.ResolveResult
import com.intellij.util.ProcessingContext
import org.jetbrains.yaml.YAMLLanguage

/**
 * Registers bidirectional references between Spring application YAML and Helm template YAML:
 * - Spring ${ENV_VAR} values → Helm `name: ENV_VAR` elements
 * - Helm `name: ENV_VAR` elements → Spring property keys
 *
 * Enables Ctrl+Click navigation and rename refactoring. All resolution is module-scoped.
 */
class EnvVarReferenceContributor : PsiReferenceContributor() {

    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        registrar.registerReferenceProvider(
            PlatformPatterns.psiElement(PsiElement::class.java).withLanguage(YAMLLanguage.INSTANCE),
            object : PsiReferenceProvider() {
                override fun getReferencesByElement(
                    element: PsiElement,
                    context: ProcessingContext,
                ): Array<PsiReference> {
                    // Only process leaf elements (no children) with non-empty text
                    if (element.firstChild != null || element.textLength == 0) return PsiReference.EMPTY_ARRAY

                    val vf = element.containingFile?.virtualFile ?: return PsiReference.EMPTY_ARRAY
                    val module = ModuleUtil.findModuleForPsiElement(element) ?: return PsiReference.EMPTY_ARRAY

                    if (vf.isSpringApp()) {
                        val (envVar, range) = springRefInElement(element) ?: return PsiReference.EMPTY_ARRAY
                        return arrayOf(EnvVarReference(element, envVar, range, resolver = {
                            findHelmTargets(envVar, element.project, module)
                        }))
                    }

                    if (vf.isHelmTemplate()) {
                        val span = helmEnvNameSpan(element) ?: return PsiReference.EMPTY_ARRAY
                        val range = TextRange(
                            span.startOffset - element.textRange.startOffset,
                            span.endOffset - element.textRange.startOffset,
                        )
                        return arrayOf(EnvVarReference(element, span.envVar, range, resolver = {
                            findSpringTargets(span.envVar, element.project, module)
                        }))
                    }

                    return PsiReference.EMPTY_ARRAY
                }
            },
        )
    }
}

private class EnvVarReference(
    element: PsiElement,
    private val envVar: String,
    range: TextRange,
    private val resolver: () -> List<PsiElement>,
) : PsiPolyVariantReferenceBase<PsiElement>(element, range, false) {

    override fun multiResolve(incomplete: Boolean): Array<ResolveResult> =
        deduplicated(resolver()).map(::PsiElementResolveResult).toTypedArray()

    override fun handleElementRename(newName: String): PsiElement =
        ElementManipulators.handleContentChange(element, rangeInElement, newName)

    override fun getVariants(): Array<Any> = emptyArray()
}




