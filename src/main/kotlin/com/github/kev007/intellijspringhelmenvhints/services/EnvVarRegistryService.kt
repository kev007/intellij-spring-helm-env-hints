package com.github.kev007.intellijspringhelmenvhints.services

import com.github.kev007.intellijspringhelmenvhints.core.EnvVarMappingCore
import com.github.kev007.intellijspringhelmenvhints.models.EnvVarEntry
import com.github.kev007.intellijspringhelmenvhints.navigation.EnvVarMappingPsiUtils
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue

/**
 * Project service that maintains a live, PSI-invalidated, per-module index of every env var
 * defined in Helm templates and referenced in Spring application YAML files.
 *
 * The index is keyed by [Module] so that reference resolution never crosses module boundaries.
 *
 * Consumers (annotator, reference contributor, goto declaration) call [getInstance] and supply
 * the source [Module] when querying so only same-module targets are returned.
 */
@Service(Service.Level.PROJECT)
class EnvVarRegistryService(private val project: Project) {

    private val LOG = Logger.getInstance(EnvVarRegistryService::class.java)

    companion object {
        fun getInstance(project: Project): EnvVarRegistryService =
            project.getService(EnvVarRegistryService::class.java)
    }

    // ─── Public query API ─────────────────────────────────────────────────────

    fun getHelmValues(envVar: String, module: Module?): List<PsiElement> =
        moduleEntries(module, envVar)?.helmValues.orEmpty().also { targets ->
            if (targets.isNotEmpty()) LOG.info("[$envVar] resolved ${targets.size} Helm target(s) in module '${module?.name}'")
        }

    fun getAllSpringTargets(envVar: String, module: Module?): List<PsiElement> {
        val entry = moduleEntries(module, envVar) ?: return emptyList()
        return EnvVarMappingPsiUtils.distinctTargets(entry.springKeys + entry.springValueRefs).also { targets ->
            if (targets.isNotEmpty()) LOG.info("[$envVar] resolved ${targets.size} Spring target(s) in module '${module?.name}'")
        }
    }

    /** True when the Helm env var [envVar] has at least one matching Spring target in [module]. */
    fun isHelmMatched(envVar: String, module: Module?): Boolean =
        (moduleEntries(module, envVar)?.springCount ?: 0) > 0

    /** True when the Spring env var [envVar] has at least one matching Helm target in [module]. */
    fun isSpringMatched(envVar: String, module: Module?): Boolean =
        (moduleEntries(module, envVar)?.helmCount ?: 0) > 0

    // ─── Internal cached index ────────────────────────────────────────────────

    private fun moduleEntries(module: Module?, envVar: String): EnvVarEntry? =
        module?.let { index()[it]?.get(envVar) }

    private fun index(): Map<Module, Map<String, EnvVarEntry>> =
        CachedValuesManager.getManager(project).getCachedValue(project) {
            CachedValueProvider.Result.create(buildIndex(), PsiModificationTracker.MODIFICATION_COUNT)
        }

    /**
     * Builds one [EnvVarEntry] map per module, scanning each module's own content roots only.
     */
    private fun buildIndex(): Map<Module, Map<String, EnvVarEntry>> {
        val psiManager = PsiManager.getInstance(project)
        return yamlFilesByModule().mapValues { (_, files) ->
            val helmValues = mutableMapOf<String, MutableList<PsiElement>>()
            val springKeys = mutableMapOf<String, MutableList<PsiElement>>()
            val springValueRefs = mutableMapOf<String, MutableList<PsiElement>>()

            for (vFile in files) {
                val yamlFile = psiManager.findFile(vFile) as? YAMLFile ?: continue
                when {
                    EnvVarMappingCore.isHelmTemplateFile(vFile) ->
                        collectHelmMappings(yamlFile, helmValues)
                    EnvVarMappingCore.isSpringApplicationFile(vFile) ->
                        collectSpringMappings(yamlFile, springKeys, springValueRefs)
                }
            }

            val allNames = (helmValues.keys + springKeys.keys + springValueRefs.keys).toSet()
            allNames.associateWith { name ->
                EnvVarEntry(
                    name = name,
                    helmValues = helmValues[name]?.let(EnvVarMappingPsiUtils::distinctTargets).orEmpty(),
                    springKeys = springKeys[name]?.let(EnvVarMappingPsiUtils::distinctTargets).orEmpty(),
                    springValueRefs = springValueRefs[name]?.let(EnvVarMappingPsiUtils::distinctTargets).orEmpty(),
                )
            }
        }
    }

    // ─── Collection helpers ───────────────────────────────────────────────────

    private fun MutableMap<String, MutableList<PsiElement>>.add(key: String, element: PsiElement) {
        getOrPut(key) { mutableListOf() }.add(element)
    }

    private fun collectHelmMappings(
        yamlFile: YAMLFile,
        helmValues: MutableMap<String, MutableList<PsiElement>>,
    ) {
        fun walk(node: PsiElement) {
            if (node is YAMLKeyValue && node.keyText == "name" && EnvVarMappingCore.isUnderEnvPath(node)) {
                val envVar = EnvVarMappingCore.extractHelmEnvVarValue(node) ?: return
                node.value?.let { helmValues.add(envVar, it) }
            }
            node.children.forEach(::walk)
        }
        walk(yamlFile)
    }

    private fun collectSpringMappings(
        yamlFile: YAMLFile,
        springKeys: MutableMap<String, MutableList<PsiElement>>,
        springValueRefs: MutableMap<String, MutableList<PsiElement>>,
    ) {
        EnvVarMappingCore.springKeyOccurrences(yamlFile).forEach { (fullKey, keyElement) ->
            springKeys.add(EnvVarMappingCore.springKeyToEnvVarName(fullKey), keyElement)
        }
        EnvVarMappingCore.envVarRefRegex.findAll(yamlFile.text).forEach { match ->
            val group = match.groups[1] ?: return@forEach
            yamlFile.findElementAt(group.range.first)?.let { springValueRefs.add(group.value, it) }
        }
    }

    /** Collects all YAML files per module using each module's own content roots. */
    private fun yamlFilesByModule(): Map<Module, List<VirtualFile>> =
        ModuleManager.getInstance(project).modules.associate { module ->
            val files = mutableListOf<VirtualFile>()
            ModuleRootManager.getInstance(module).contentRoots.forEach { root ->
                VfsUtilCore.iterateChildrenRecursively(root, null) { file ->
                    if (!file.isDirectory && EnvVarMappingCore.isYamlFile(file)) files += file
                    true
                }
            }
            module to files
        }
}
