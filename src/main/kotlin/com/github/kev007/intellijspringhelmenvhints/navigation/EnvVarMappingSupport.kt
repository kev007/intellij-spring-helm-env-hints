package com.github.kev007.intellijspringhelmenvhints.navigation

import com.github.kev007.intellijspringhelmenvhints.core.EnvVarMappingCore
import com.github.kev007.intellijspringhelmenvhints.models.*
import com.github.kev007.intellijspringhelmenvhints.services.EnvVarRegistryService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import org.jetbrains.yaml.psi.YAMLKeyValue

/**
 * Backward-compatibility facade for the refactored env var mapping system.
 *
 * This object provides the public API that existing client code depends on, while
 * delegating implementation to:
 * - [EnvVarMappingCore]: Pure parsing and YAML traversal logic
 * - [EnvVarRegistryService]: Cached cross-file env var index
 *
 * All re-exported functions from Core are stateless and deterministic.
 * All service-delegating functions (find targets, get status) are cached via the service.
 *
 * Rationale: Allows incremental refactoring without breaking client code.
 * New code should prefer calling Core or Service directly.
 */
object EnvVarMappingSupport {

    // ─── Re-exported from core ────────────────────────────────────────────────

    val envVarRefRegex = EnvVarMappingCore.envVarRefRegex

    fun isYamlFile(file: VirtualFile): Boolean = EnvVarMappingCore.isYamlFile(file)
    fun isSpringApplicationFile(file: VirtualFile): Boolean = EnvVarMappingCore.isSpringApplicationFile(file)
    fun isHelmTemplateFile(file: VirtualFile): Boolean = EnvVarMappingCore.isHelmTemplateFile(file)

    fun springKeyToEnvVarName(key: String): String = EnvVarMappingCore.springKeyToEnvVarName(key)
    fun springKeyAtOffset(text: String, offset: Int): String? = EnvVarMappingCore.springKeyAtOffset(text, offset)
    internal fun springKeyOccurrences(text: String): List<SpringKeyOccurrence> = EnvVarMappingCore.springKeyOccurrences(text)

    fun envVarReferenceSpansInRange(text: String, startOffset: Int, endOffset: Int): List<EnvVarReferenceSpan> =
        EnvVarMappingCore.envVarReferenceSpansInRange(text, startOffset, endOffset)

    fun envVarReferenceSpanAtOffset(text: String, offset: Int): EnvVarReferenceSpan? =
        EnvVarMappingCore.envVarReferenceSpanAtOffset(text, offset)

    fun envVarReferenceAtOffset(text: String, offset: Int): String? =
        EnvVarMappingCore.envVarReferenceAtOffset(text, offset)

    fun springEnvReferencesInValue(keyValue: YAMLKeyValue): List<EnvVarReferenceSpan> =
        EnvVarMappingCore.springEnvReferencesInValue(keyValue)

    fun springEnvReferenceAtOffset(fileElement: PsiElement, offset: Int): EnvVarRefAtOffset? =
        EnvVarMappingCore.springEnvReferenceAtOffset(fileElement, offset)

    fun springEnvVarAtOffset(text: String, offset: Int): String? =
        EnvVarMappingCore.springEnvVarAtOffset(text, offset)

    fun helmEnvNameSpan(valueElement: PsiElement): EnvVarReferenceSpan? =
        EnvVarMappingCore.helmEnvNameSpan(valueElement)

    fun findEnclosingKeyValue(element: PsiElement): YAMLKeyValue? =
        EnvVarMappingCore.findEnclosingKeyValue(element)

    fun envVarForElement(element: PsiElement): String? = EnvVarMappingCore.envVarForElement(element)
    fun envVarForCompletionContext(element: PsiElement): String? = EnvVarMappingCore.envVarForElement(element)

    fun findUsagesTarget(element: PsiElement): PsiElement? = EnvVarMappingCore.findUsagesTarget(element)

    fun envVarAtOffset(psiFile: PsiElement, offset: Int): String? = EnvVarMappingCore.envVarAtOffset(psiFile, offset)

    fun isUnderEnvPath(element: PsiElement): Boolean = EnvVarMappingCore.isUnderEnvPath(element)

    // ─── Service-delegating methods ────────────────────────────────────────────

    fun findHelmEnvTargets(project: Project, envVar: String): List<PsiElement> =
        registry(project).getHelmValues(envVar)

    fun findSpringTargets(project: Project, envVar: String): List<PsiElement> =
        registry(project).getAllSpringTargets(envVar)

    fun mappingStatusForSpringVar(project: Project, envVar: String): MappingStatus =
        mappingStatus(registry(project).isSpringMatched(envVar))

    fun mappingStatusForHelmVar(project: Project, envVar: String): MappingStatus =
        mappingStatus(registry(project).isHelmMatched(envVar))

    // ─── Complex query methods ──────────────────────────────────────────────────

    fun mappingQueryAtOffset(fileElement: PsiElement, virtualFile: VirtualFile, offset: Int): MappingQuery? {
        return when {
            isSpringApplicationFile(virtualFile) -> {
                val envVar = springEnvVarAtOffset(fileElement.text, offset) ?: return null
                MappingQuery(envVar, ::findHelmEnvTargets)
            }
            isHelmTemplateFile(virtualFile) -> {
                val envVar = envVarAtOffset(fileElement, offset) ?: return null
                MappingQuery(envVar, ::findSpringTargets)
            }
            else -> null
        }
    }

    fun resolveMappedTargets(fileElement: PsiElement, virtualFile: VirtualFile, offset: Int): List<PsiElement> {
        val query = mappingQueryAtOffset(fileElement, virtualFile, offset)
            ?: envVarForElement(fileElement)?.let {
                MappingQuery(
                    it,
                    targetResolverFor(virtualFile),
                )
            }
            ?: return emptyList()
        return query.targetResolver(fileElement.project, query.envVar)
    }

    private fun registry(project: Project): EnvVarRegistryService = EnvVarRegistryService.getInstance(project)

    private fun targetResolverFor(virtualFile: VirtualFile): (Project, String) -> List<PsiElement> =
        if (isSpringApplicationFile(virtualFile)) ::findHelmEnvTargets else ::findSpringTargets

    private fun mappingStatus(isMatched: Boolean): MappingStatus =
        if (isMatched) MappingStatus.MATCHED else MappingStatus.UNMATCHED
}
