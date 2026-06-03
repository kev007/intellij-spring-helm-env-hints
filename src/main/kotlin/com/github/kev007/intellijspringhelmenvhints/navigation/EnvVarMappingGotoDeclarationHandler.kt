package com.github.kev007.intellijspringhelmenvhints.navigation

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.PsiReference

class EnvVarMappingGotoDeclarationHandler : GotoDeclarationHandler {

    private val logger = thisLogger()

    override fun getGotoDeclarationTargets(
        sourceElement: PsiElement?,
        offset: Int,
        editor: Editor,
    ): Array<PsiElement>? {
        if (sourceElement == null) return null

        val file = sourceElement.containingFile ?: return null
        val virtualFile = file.virtualFile ?: return null
        if (!EnvVarMappingSupport.isYamlFile(virtualFile)) return null

        val mappingTargets = EnvVarMappingSupport.resolveMappedTargets(file, virtualFile, offset)

        if (mappingTargets.isNotEmpty()) {
            mappingTargets.forEach { target ->
                logger.debug("Found mapped declaration reference: ${targetKey(target)}")
            }

            val existingTargets = existingReferenceTargets(sourceElement, file, offset)

            val mergedTargets = (existingTargets + mappingTargets)
                .distinctBy { targetKey(it) }
                .also { targets ->
                    targets.forEach { target ->
                        logger.debug("Goto declaration target: ${targetKey(target)}")
                    }
                }

            return mergedTargets.toTypedArray().takeIf { it.isNotEmpty() }
        }

        // If no mapped targets, still check existing references
        val existingTargets = existingReferenceTargets(sourceElement, file, offset)
        return existingTargets.toTypedArray().takeIf { it.isNotEmpty() }
    }

    private fun existingReferenceTargets(sourceElement: PsiElement, file: PsiFile, offset: Int): List<PsiElement> {
        val fromSourceElement = sourceElement.references.flatMap { reference ->
            referenceTargets(reference).also { targets ->
                targets.forEach { target ->
                    logger.debug("Found existing declaration reference (source element): ${targetKey(target)}")
                }
            }
        }

        val fromReferenceAtOffset = file.findReferenceAt(offset)
            ?.let { reference ->
                referenceTargets(reference).also { targets ->
                    targets.forEach { target ->
                        logger.debug("Found existing declaration reference (offset): ${targetKey(target)}")
                    }
                }
            }
            .orEmpty()

        return (fromSourceElement + fromReferenceAtOffset).distinctBy(::targetKey)
    }

    private fun referenceTargets(reference: PsiReference): List<PsiElement> = when (reference) {
        is PsiPolyVariantReference -> reference.multiResolve(false).mapNotNull { it.element }
        else -> listOfNotNull(reference.resolve())
    }

    private fun targetKey(target: PsiElement): String {
        val path = target.containingFile?.virtualFile?.path ?: "<no-file>"
        val startOffset = target.textRange?.startOffset ?: -1
        return "$path:$startOffset"
    }
}
