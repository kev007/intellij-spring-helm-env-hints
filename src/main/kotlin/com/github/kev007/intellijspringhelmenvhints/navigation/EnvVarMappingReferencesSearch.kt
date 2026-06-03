package com.github.kev007.intellijspringhelmenvhints.navigation

import com.github.kev007.intellijspringhelmenvhints.core.EnvVarMappingCore
import com.intellij.openapi.application.QueryExecutorBase
import com.intellij.openapi.util.TextRange
import com.intellij.psi.ElementManipulators
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.util.Processor

/**
 * Synthesizes bidirectional references for the References Search action.
 * When searching for usages of a Spring key or Helm env name, this queries for
 * all corresponding env vars in the opposite file system and creates synthetic references.
 */
class EnvVarMappingReferencesSearch : QueryExecutorBase<PsiReference, ReferencesSearch.SearchParameters>(true) {

    override fun processQuery(
        queryParameters: ReferencesSearch.SearchParameters,
        consumer: Processor<in PsiReference>,
    ) {
        val target = queryParameters.elementToSearch
        val file = target.containingFile ?: return
        val virtualFile = file.virtualFile ?: return
        if (!EnvVarMappingCore.isYamlFile(virtualFile)) return

        processMatchingReferences(
            target = target,
            sourceElements = EnvVarMappingSupport.resolveMappedTargets(file, virtualFile, target.textOffset),
            consumer = consumer,
        )
    }

    private fun processMatchingReferences(
        target: PsiElement,
        sourceElements: List<PsiElement>,
        consumer: Processor<in PsiReference>,
    ) {
        val seen = hashSetOf<String>()

        sourceElements.forEach { source ->
            val key = EnvVarMappingPsiUtils.targetKey(source)
            if (!seen.add(key)) return@forEach

            consumer.process(EnvVarMappingSyntheticReference(source, target))
        }
    }
}

/**
 * A synthetic reference created for cross-file env var usage tracking.
 * When a Spring key is renamed, this resolves to matching Helm env names, and vice versa.
 */
private class EnvVarMappingSyntheticReference(
    private val source: PsiElement,
    private val target: PsiElement,
) : PsiReference {

    override fun getElement(): PsiElement = source

    override fun getRangeInElement(): TextRange = source.textRange?.shiftRight(-source.textRange!!.startOffset) ?: TextRange(0, source.textLength)

    override fun resolve(): PsiElement = target

    override fun getCanonicalText(): String = source.text

    override fun handleElementRename(newName: String): PsiElement = ElementManipulators.handleContentChange(source, getRangeInElement(), newName)

    override fun bindToElement(element: PsiElement): PsiElement = source

    override fun isReferenceTo(element: PsiElement): Boolean = element == target

    override fun isSoft(): Boolean = true
}

