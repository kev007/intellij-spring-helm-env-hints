package com.github.kev007.intellijspringhelmenvhints.navigation

import com.github.kev007.intellijspringhelmenvhints.core.EnvVarMappingCore
import com.github.kev007.intellijspringhelmenvhints.models.MappingStatus
import com.github.kev007.intellijspringhelmenvhints.services.EnvVarRegistryService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement

/**
 * Facade used by the annotator, reference contributor, and goto declaration handler.
 *
 * Delegates implementation to:
 * - [EnvVarMappingCore]: Pure parsing and YAML traversal logic
 * - [EnvVarRegistryService]: Cached cross-file env var index
 */
object EnvVarMappingSupport {

    // ─── Service-delegating methods ────────────────────────────────────────────

    fun findHelmEnvTargets(project: Project, envVar: String): List<PsiElement> =
        registry(project).getHelmValues(envVar)

    fun findSpringTargets(project: Project, envVar: String): List<PsiElement> =
        registry(project).getAllSpringTargets(envVar)

    fun mappingStatusForSpringVar(project: Project, envVar: String): MappingStatus =
        mappingStatus(registry(project).isSpringMatched(envVar))

    fun mappingStatusForHelmVar(project: Project, envVar: String): MappingStatus =
        mappingStatus(registry(project).isHelmMatched(envVar))

    // ─── Cross-file target resolution ──────────────────────────────────────────

    fun resolveMappedTargets(fileElement: PsiElement, virtualFile: VirtualFile, offset: Int): List<PsiElement> {
        return when {
            EnvVarMappingCore.isSpringApplicationFile(virtualFile) -> {
                val envVar = EnvVarMappingCore.springEnvVarAtOffset(fileElement.text, offset) ?: return emptyList()
                findHelmEnvTargets(fileElement.project, envVar)
            }
            EnvVarMappingCore.isHelmTemplateFile(virtualFile) -> {
                val envVar = EnvVarMappingCore.envVarAtOffset(fileElement, offset) ?: return emptyList()
                findSpringTargets(fileElement.project, envVar)
            }
            else -> EnvVarMappingCore.envVarForElement(fileElement)
                ?.let { envVar -> targetResolverFor(virtualFile)(fileElement.project, envVar) }
                .orEmpty()
        }
    }

    private fun registry(project: Project): EnvVarRegistryService = EnvVarRegistryService.getInstance(project)

    private fun targetResolverFor(virtualFile: VirtualFile): (Project, String) -> List<PsiElement> =
        if (EnvVarMappingCore.isSpringApplicationFile(virtualFile)) ::findHelmEnvTargets else ::findSpringTargets

    private fun mappingStatus(isMatched: Boolean): MappingStatus =
        if (isMatched) MappingStatus.MATCHED else MappingStatus.UNMATCHED
}
