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
 * - Spring `${ENV_VAR}` values → Helm `name: ENV_VAR` elements
 * - Helm `name: ENV_VAR` elements → Spring occurrences of the same var
 *
 * Enables Ctrl+Click navigation and rename refactoring. All resolution is scope-scoped
 * (see [resolveEnvMatch]).
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

                    // Both directions differ only in how the span is found and which side is resolved.
                    val span: EnvSpan
                    val targets: (String) -> List<PsiElement>
                    when {
                        vf.isSpringApp() -> {
                            span = springRefSpans(element).firstOrNull() ?: return PsiReference.EMPTY_ARRAY
                            targets = { findHelmTargets(it, element.project, module) }
                        }
                        vf.isHelmTemplate() -> {
                            span = helmEnvNameSpan(element) ?: return PsiReference.EMPTY_ARRAY
                            targets = { findSpringTargets(it, element.project, module) }
                        }
                        else -> return PsiReference.EMPTY_ARRAY
                    }
                    return arrayOf(
                        EnvVarReference(element, span.relativeTo(element)) { targets(span.envVar) }
                    )
                }
            },
        )
    }
}

private class EnvVarReference(
    element: PsiElement,
    range: TextRange,
    private val resolver: () -> List<PsiElement>,
) : PsiPolyVariantReferenceBase<PsiElement>(element, range, false) {

    override fun multiResolve(incomplete: Boolean): Array<ResolveResult> =
        deduplicated(resolver()).map(::PsiElementResolveResult).toTypedArray()

    override fun handleElementRename(newName: String): PsiElement =
        ElementManipulators.handleContentChange(element, rangeInElement, newName)

    override fun getVariants(): Array<Any> = emptyArray()
}
