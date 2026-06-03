package com.github.kev007.intellijspringhelmenvhints.navigation

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.PsiReference

object EnvVarMappingPsiUtils {

    fun existingReferenceTargets(sourceElement: PsiElement, file: PsiFile, offset: Int): List<PsiElement> {
        val fromSource = sourceElement.references.flatMap(::referenceTargets)
        val fromOffset = file.findReferenceAt(offset)?.let(::referenceTargets).orEmpty()
        return distinctTargets(fromSource + fromOffset)
    }

    fun distinctTargets(targets: List<PsiElement>): List<PsiElement> = targets.distinctBy(::targetKey)

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

