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
     * Collects all targets from both existing PSI references at a source element
     * and from references found at a specific offset in the file.
     */
    fun existingReferenceTargets(sourceElement: PsiElement, file: PsiFile, offset: Int): List<PsiElement> {
        val fromSource = sourceElement.references.flatMap(::referenceTargets)
        val fromOffset = file.findReferenceAt(offset)?.let(::referenceTargets).orEmpty()
        return distinctTargets(fromSource + fromOffset)
    }

    /**
     * Deduplicates target elements ensuring each unique (file path + offset) pair appears only once.
     */
    fun distinctTargets(targets: List<PsiElement>): List<PsiElement> = targets.distinctBy(::targetKey)

    /**
     * Creates a unique key for a PSI element based on its file and position.
     * Used for deduplication across file boundaries.
     */
    fun targetKey(target: PsiElement): String {
        val path = target.containingFile?.virtualFile?.path ?: "<no-file>"
        val startOffset = target.textRange?.startOffset ?: -1
        return "$path:$startOffset"
    }

    private fun referenceTargets(reference: PsiReference): List<PsiElement> {
        return when (reference) {
            is PsiPolyVariantReference -> reference.multiResolve(false).mapNotNull { it.element }
            else -> listOfNotNull(reference.resolve())
        }
    }
}

