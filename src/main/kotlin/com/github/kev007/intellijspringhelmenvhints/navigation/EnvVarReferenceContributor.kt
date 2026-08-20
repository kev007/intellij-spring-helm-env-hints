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
import org.jetbrains.yaml.psi.YAMLScalar

/**
 * Registers bidirectional references between Spring application YAML and Helm template YAML:
 * - Spring `${ENV_VAR}` values → Helm `name: ENV_VAR` elements
 * - Helm `name: ENV_VAR` elements → Spring occurrences of the same var
 * - Spring `${some.property}` values → the `some.property` key of the SAME application file
 *
 * Enables Ctrl+Click navigation. All resolution is scope-scoped (see [resolveEnvMatch]).
 *
 * The provider is registered for [YAMLScalar] on purpose: contributed references are only
 * requested from `YAMLScalarImpl.getReferences()`, never from the leaf tokens inside it, so a
 * leaf-only provider (which this used to be) is never asked for anything and silently
 * produces no references at all.
 */
class EnvVarReferenceContributor : PsiReferenceContributor() {

    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        registrar.registerReferenceProvider(
            PlatformPatterns.psiElement(YAMLScalar::class.java),
            object : PsiReferenceProvider() {
                override fun getReferencesByElement(
                    element: PsiElement,
                    context: ProcessingContext,
                ): Array<PsiReference> {
                    if (element.textLength == 0) return PsiReference.EMPTY_ARRAY

                    val vf = element.containingFile?.virtualFile ?: return PsiReference.EMPTY_ARRAY
                    val module = ModuleUtil.findModuleForPsiElement(element) ?: return PsiReference.EMPTY_ARRAY

                    // Both directions differ only in how the spans are found and which side is
                    // resolved. One Spring scalar can hold several `${...}` references.
                    val spans: List<EnvSpan>
                    val targets: (String) -> List<PsiElement>
                    // Same-file targets: a Spring placeholder may point at a property of its own
                    // `application*.yaml` (`url: ${app.host}:${app.port}`). Those keys are legal
                    // targets, so they are resolved separately from the cross-file ones — the
                    // cross-file resolution deliberately drops everything located in this file.
                    val localTargets: (String) -> List<PsiElement>
                    val springSpans = if (vf.isSpringApp()) springRefSpans(element) else emptyList()
                    when {
                        springSpans.isNotEmpty() -> {
                            spans = springSpans
                            targets = { findHelmTargets(it, element.project, module) }
                            localTargets = { springLocalTargets(element, it) }
                        }
                        vf.isHelmTemplate() -> {
                            spans = listOfNotNull(helmEnvNameSpan(element))
                            targets = { findSpringTargets(it, element.project, module) }
                            localTargets = { emptyList() }
                        }
                        else -> return PsiReference.EMPTY_ARRAY
                    }
                    if (spans.isEmpty()) return PsiReference.EMPTY_ARRAY

                    return spans.map { span ->
                        EnvVarReference(element, span.relativeTo(element)) {
                            excludingSelf(targets(span.envVar), vf) + localTargets(span.envVar)
                        }
                    }.toTypedArray()
                }
            },
        )
    }
}

private class EnvVarReference(
    element: PsiElement,
    range: TextRange,
    private val resolver: () -> List<PsiElement>,
) : PsiPolyVariantReferenceBase<PsiElement>(element, range, /* soft = */ true), EnvVarPluginReference {

    override fun multiResolve(incomplete: Boolean): Array<ResolveResult> =
        deduplicated(resolver()).map(::PsiElementResolveResult).toTypedArray()

    override fun handleElementRename(newName: String): PsiElement =
        ElementManipulators.handleContentChange(element, rangeInElement, newName)

    override fun getVariants(): Array<Any> = emptyArray()
}
