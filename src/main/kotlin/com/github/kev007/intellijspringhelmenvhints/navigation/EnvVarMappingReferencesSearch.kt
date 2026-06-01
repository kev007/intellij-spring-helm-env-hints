package com.github.kev007.intellijspringhelmenvhints.navigation

import com.intellij.openapi.application.QueryExecutorBase
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.util.Processor

class EnvVarMappingReferencesSearch : QueryExecutorBase<PsiReference, ReferencesSearch.SearchParameters>(true) {

    override fun processQuery(
        queryParameters: ReferencesSearch.SearchParameters,
        consumer: Processor<in PsiReference>,
    ) {
        val target = queryParameters.elementToSearch
        val file = target.containingFile ?: return
        val virtualFile = file.virtualFile ?: return
        if (!EnvVarMappingSupport.isYamlFile(virtualFile)) return

        val project = target.project
        val sourceElements = when {
            EnvVarMappingSupport.isSpringApplicationFile(virtualFile) -> {
                val springKey = EnvVarMappingSupport.springKeyAtOffset(file.text, target.textOffset) ?: return
                val envVar = EnvVarMappingSupport.springKeyToEnvVarName(springKey)
                EnvVarMappingSupport.findHelmEnvTargets(project, envVar)
            }

            EnvVarMappingSupport.isHelmTemplateFile(virtualFile) -> {
                val envVar = EnvVarMappingSupport.envVarAtOffset(file.text, target.textOffset) ?: return
                EnvVarMappingSupport.findSpringTargets(project, envVar)
            }

            else -> return
        }

        processMatchingReferences(target, sourceElements, consumer)
    }

    private fun processMatchingReferences(
        target: PsiElement,
        sourceElements: List<PsiElement>,
        consumer: Processor<in PsiReference>,
    ) {
        val seen = hashSetOf<String>()

        sourceElements.forEach { source ->
            source.references.forEach { reference ->
                if (!reference.isReferenceTo(target)) return@forEach

                val key = "${source.containingFile.virtualFile.path}:${reference.rangeInElement.startOffset}:${reference.rangeInElement.endOffset}"
                if (seen.add(key)) {
                    consumer.process(reference)
                }
            }
        }
    }
}

