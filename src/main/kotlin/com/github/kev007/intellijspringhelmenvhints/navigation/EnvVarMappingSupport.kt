package com.github.kev007.intellijspringhelmenvhints.navigation

import com.github.kev007.intellijspringhelmenvhints.core.EnvVarMappingCore
import com.github.kev007.intellijspringhelmenvhints.models.MappingStatus
import com.github.kev007.intellijspringhelmenvhints.services.EnvVarRegistryService
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement

/**
 * Facade used by the annotator, reference contributor, and goto declaration handler.
 *
 * All query methods accept a [Module] to ensure references are resolved within the same
 * project module as the source element — never cross-module.
 *
 * Delegates implementation to:
 * - [EnvVarMappingCore]: Pure parsing and YAML traversal logic
 * - [EnvVarRegistryService]: Cached per-module env var index
 */
object EnvVarMappingSupport {

    private val LOG = Logger.getInstance(EnvVarMappingSupport::class.java)

    // ─── Module-scoped target resolution ──────────────────────────────────────

    fun findHelmEnvTargets(project: Project, module: Module?, envVar: String): List<PsiElement> =
        EnvVarRegistryService.getInstance(project).getHelmValues(envVar, module)

    fun findSpringTargets(project: Project, module: Module?, envVar: String): List<PsiElement> =
        EnvVarRegistryService.getInstance(project).getAllSpringTargets(envVar, module)

    fun mappingStatusForSpringVar(project: Project, module: Module?, envVar: String): MappingStatus =
        if (EnvVarRegistryService.getInstance(project).isSpringMatched(envVar, module)) MappingStatus.MATCHED
        else MappingStatus.UNMATCHED

    fun mappingStatusForHelmVar(project: Project, module: Module?, envVar: String): MappingStatus =
        if (EnvVarRegistryService.getInstance(project).isHelmMatched(envVar, module)) MappingStatus.MATCHED
        else MappingStatus.UNMATCHED

    // ─── Goto-declaration target resolution ───────────────────────────────────

    fun resolveMappedTargets(fileElement: PsiElement, virtualFile: VirtualFile, offset: Int): List<PsiElement> {
        val module = ModuleUtil.findModuleForPsiElement(fileElement)
        return when {
            EnvVarMappingCore.isSpringApplicationFile(virtualFile) -> {
                val envVar = EnvVarMappingCore.springEnvVarAtOffset(fileElement, offset) ?: return emptyList()
                findHelmEnvTargets(fileElement.project, module, envVar).also { targets ->
                    if (targets.isNotEmpty())
                        LOG.info("[$envVar] Spring → Helm: resolved ${targets.size} target(s) in module '${module?.name}'")
                }
            }
            EnvVarMappingCore.isHelmTemplateFile(virtualFile) -> {
                val envVar = EnvVarMappingCore.envVarAtOffset(fileElement, offset) ?: return emptyList()
                findSpringTargets(fileElement.project, module, envVar).also { targets ->
                    if (targets.isNotEmpty())
                        LOG.info("[$envVar] Helm → Spring: resolved ${targets.size} target(s) in module '${module?.name}'")
                }
            }
            else -> emptyList()
        }
    }
}
