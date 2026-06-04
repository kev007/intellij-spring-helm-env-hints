package com.github.kev007.intellijspringhelmenvhints.navigation

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.PsiReference

/**
 * Common PSI utilities for env var reference resolution and deduplication.
 */
object EnvVarMappingPsiUtils {

    /**
     * Collects all resolved targets from PSI references attached to [sourceElement]
     * and from any reference found at [offset] in [file], deduplicating the result.
     */
    fun existingReferenceTargets(sourceElement: PsiElement, file: PsiFile, offset: Int): List<PsiElement> {
        val fromSource = sourceElement.references.flatMap(::resolveReference)
        val fromOffset = file.findReferenceAt(offset)?.let(::resolveReference).orEmpty()
        return distinctTargets(fromSource + fromOffset)
    }

    /**
     * Deduplicates target elements ensuring each unique (file path + offset) pair appears only once.
     */
    fun distinctTargets(targets: List<PsiElement>): List<PsiElement> =
        targets.distinctBy { "${it.containingFile?.virtualFile?.path ?: "<no-file>"}:${it.textRange?.startOffset ?: -1}" }

    private fun resolveReference(reference: PsiReference): List<PsiElement> = when (reference) {
        is PsiPolyVariantReference -> reference.multiResolve(false).mapNotNull { it.element }
        else -> listOfNotNull(reference.resolve())
    }
}
