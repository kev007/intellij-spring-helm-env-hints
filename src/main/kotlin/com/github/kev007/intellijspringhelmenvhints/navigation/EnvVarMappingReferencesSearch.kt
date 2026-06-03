package com.github.kev007.intellijspringhelmenvhints.navigation

import com.intellij.openapi.application.QueryExecutorBase
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.util.Processor

class EnvVarMappingReferencesSearch : QueryExecutorBase<PsiReference, ReferencesSearch.SearchParameters>(true) {

    private val logger = thisLogger()

    override fun processQuery(
        queryParameters: ReferencesSearch.SearchParameters,
        consumer: Processor<in PsiReference>,
    ) {
        val target = queryParameters.elementToSearch
        val file = target.containingFile ?: return
        val virtualFile = file.virtualFile ?: return
        if (!EnvVarMappingSupport.isYamlFile(virtualFile)) return

        val sourceElements = EnvVarMappingSupport.resolveMappedTargets(file, virtualFile, target.textOffset)

        processMatchingReferences(target, sourceElements, consumer)
    }

    private fun processMatchingReferences(
        target: PsiElement,
        sourceElements: List<PsiElement>,
        consumer: Processor<in PsiReference>,
    ) {
        val seen = hashSetOf<String>()

        sourceElements.forEach { source ->
            val key = "${source.containingFile?.virtualFile?.path}:${source.textRange?.startOffset ?: -1}"
            if (!seen.add(key)) return@forEach

            // Create a synthetic reference from the source element to the target
            val reference = EnvVarMappingSyntheticReference(source, target)
            logger.debug("Found mapping reference: $key -> ${target.containingFile?.virtualFile?.path}:${target.textRange?.startOffset ?: -1}")
            consumer.process(reference)
        }
    }
}

private class EnvVarMappingSyntheticReference(
    val source: PsiElement,
    val target: PsiElement,
) : PsiReference {

    override fun getElement(): PsiElement = source

    override fun getRangeInElement(): TextRange = source.textRange?.shiftRight(-source.textRange!!.startOffset) ?: TextRange(0, source.textLength)

    override fun resolve(): PsiElement = target

    override fun getCanonicalText(): String = source.text

    override fun handleElementRename(newName: String): PsiElement = source

    override fun bindToElement(element: PsiElement): PsiElement = source

    override fun isReferenceTo(element: PsiElement): Boolean = element == target

    override fun isSoft(): Boolean = true
}

