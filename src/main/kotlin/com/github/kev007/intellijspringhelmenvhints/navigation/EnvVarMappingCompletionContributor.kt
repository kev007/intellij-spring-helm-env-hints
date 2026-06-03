package com.github.kev007.intellijspringhelmenvhints.navigation

import com.github.kev007.intellijspringhelmenvhints.core.EnvVarMappingCore
import com.github.kev007.intellijspringhelmenvhints.services.EnvVarRegistryService
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiElement
import com.intellij.util.ProcessingContext
import org.jetbrains.yaml.YAMLLanguage

class EnvVarMappingCompletionContributor : CompletionContributor() {

    init {
        extend(
            CompletionType.BASIC,
            PlatformPatterns.psiElement().withLanguage(YAMLLanguage.INSTANCE),
            EnvVarCompletionProvider(),
        )
    }
}

private class EnvVarCompletionProvider : CompletionProvider<CompletionParameters>() {

    override fun addCompletions(parameters: CompletionParameters, context: ProcessingContext, result: CompletionResultSet) {
        val position = parameters.position
        val file = position.containingFile ?: return
        val virtualFile = file.virtualFile ?: return
        if (!EnvVarMappingCore.isYamlFile(virtualFile)) return

        if (!isCompletionContext(position, virtualFile)) return

        EnvVarRegistryService.getInstance(position.project).getSuggestions()
            .asSequence()
            .filter { result.prefixMatcher.prefixMatches(it.envVar) }
            .map { suggestion -> LookupElementBuilder.create(suggestion.envVar) }
            .forEach(result::addElement)
    }

    private fun isCompletionContext(position: PsiElement, virtualFile: com.intellij.openapi.vfs.VirtualFile): Boolean {
        val keyValue = EnvVarMappingCore.findEnclosingKeyValue(position) ?: return false
        val envVar = EnvVarMappingCore.envVarForElement(position)

        return when {
            EnvVarMappingCore.isSpringApplicationFile(virtualFile) -> keyValue.value != null && envVar != null

            EnvVarMappingCore.isHelmTemplateFile(virtualFile) -> keyValue.keyText == "name" && keyValue.value != null && envVar != null

            else -> false
        }
    }
}








