package com.github.kev007.intellijspringhelmenvhints.services

import com.github.kev007.intellijspringhelmenvhints.core.EnvVarMappingCore
import com.github.kev007.intellijspringhelmenvhints.models.EnvVarEntry
import com.github.kev007.intellijspringhelmenvhints.navigation.EnvVarMappingPsiUtils
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
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
 * Project service that maintains a live, PSI-invalidated index of every env var
 * defined in Helm templates and referenced in Spring application YAML files.
 *
 * The service scans all YAML files in the project and extracts:
 * - Helm env names: `containers[].env[].name:` values
 * - Spring keys: property key paths normalized to env var names
 * - Spring references: `${ENV_VAR}` patterns inside Spring property values
 *
 * Consumers (annotator, reference contributor, goto declaration)
 * call [getInstance] and query this service instead of rebuilding the index on each call.
 */
@Service(Service.Level.PROJECT)
class EnvVarRegistryService(private val project: Project) {

    // ─── Service access ───────────────────────────────────────────────────────

    companion object {
        fun getInstance(project: Project): EnvVarRegistryService =
            project.getService(EnvVarRegistryService::class.java)
    }

    // ─── Public query API ─────────────────────────────────────────────────────

    fun getHelmValues(envVar: String): List<PsiElement> = index()[envVar]?.helmValues.orEmpty()

    /**
     * Returns all Spring targets (keys + value references) for an env var,
     * deduped to ensure each unique PSI element appears only once.
     */
    fun getAllSpringTargets(envVar: String): List<PsiElement> {
        val entry = index()[envVar] ?: return emptyList()
        return EnvVarMappingPsiUtils.distinctTargets(entry.springKeys + entry.springValueRefs)
    }

    fun isHelmMatched(envVar: String): Boolean = index()[envVar]?.springCount?.let { it > 0 } ?: false

    fun isSpringMatched(envVar: String): Boolean = index()[envVar]?.helmCount?.let { it > 0 } ?: false

    // ─── Internal cached index ───────────────────────────────────────────────

    private fun index(): Map<String, EnvVarEntry> =
        CachedValuesManager.getManager(project).getCachedValue(project) {
            CachedValueProvider.Result.create(
                buildIndex(),
                PsiModificationTracker.MODIFICATION_COUNT,
            )
        }

    /**
     * Scans all YAML files and builds the comprehensive env var index.
     * Deduplicates elements and organizes them by env var name.
     */
    private fun buildIndex(): Map<String, EnvVarEntry> {
        val psiManager = PsiManager.getInstance(project)

        val helmValues = mutableMapOf<String, MutableList<PsiElement>>()
        val springKeys = mutableMapOf<String, MutableList<PsiElement>>()
        val springValueRefs = mutableMapOf<String, MutableList<PsiElement>>()

        for (vFile in yamlFiles()) {
            when {
                EnvVarMappingCore.isHelmTemplateFile(vFile) ->
                    collectHelmMappings(psiManager.findFile(vFile) as? YAMLFile, helmValues)

                EnvVarMappingCore.isSpringApplicationFile(vFile) ->
                    collectSpringMappings(psiManager.findFile(vFile), springKeys, springValueRefs)
            }
        }

        val allNames = (helmValues.keys + springKeys.keys + springValueRefs.keys).toSet()
        return allNames.associateWith { name ->
            EnvVarEntry(
                name = name,
                helmValues = helmValues[name]?.let(EnvVarMappingPsiUtils::distinctTargets).orEmpty(),
                springKeys = springKeys[name]?.let(EnvVarMappingPsiUtils::distinctTargets).orEmpty(),
                springValueRefs = springValueRefs[name]?.let(EnvVarMappingPsiUtils::distinctTargets).orEmpty(),
            )
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun MutableMap<String, MutableList<PsiElement>>.add(key: String, element: PsiElement) {
        getOrPut(key) { mutableListOf() }.add(element)
    }

    private fun collectHelmMappings(
        yamlFile: YAMLFile?,
        helmValues: MutableMap<String, MutableList<PsiElement>>,
    ) {
        val psiFile = yamlFile ?: return
        collectHelmEnvNameEntries(psiFile).forEach { nameKey ->
            val envVar = EnvVarMappingCore.extractHelmEnvVarValue(nameKey) ?: return@forEach
            nameKey.value?.let { helmValues.add(envVar, it) }
        }
    }

    private fun collectSpringMappings(
        psiFile: com.intellij.psi.PsiFile?,
        springKeys: MutableMap<String, MutableList<PsiElement>>,
        springValueRefs: MutableMap<String, MutableList<PsiElement>>,
    ) {
        val file = psiFile ?: return
        val text = file.text

        EnvVarMappingCore.springKeyOccurrences(text).forEach { occurrence ->
            val normalized = EnvVarMappingCore.springKeyToEnvVarName(occurrence.fullKey)
            file.findElementAt(occurrence.keyOffset)?.let { springKeys.add(normalized, it) }
        }

        EnvVarMappingCore.envVarRefRegex.findAll(text).forEach { match ->
            val envVar = match.groups[1]?.value ?: return@forEach
            val offset = match.groups[1]?.range?.first ?: return@forEach
            file.findElementAt(offset)?.let { springValueRefs.add(envVar, it) }
        }
    }

    /** Collects all YAML files in the project's content roots. */
    private fun yamlFiles(): List<VirtualFile> {
        val files = mutableListOf<VirtualFile>()
        ProjectRootManager.getInstance(project).contentRootsFromAllModules.forEach { root ->
            VfsUtilCore.iterateChildrenRecursively(root, null) { file ->
                if (!file.isDirectory && EnvVarMappingCore.isYamlFile(file)) files += file
                true
            }
        }
        return files
    }

    /** Recursively walks an element tree collecting all Helm env name entries. */
    private fun collectHelmEnvNameEntries(element: PsiElement): List<YAMLKeyValue> {
        val results = mutableListOf<YAMLKeyValue>()
        fun walk(node: PsiElement) {
            if (node is YAMLKeyValue && node.keyText == "name" &&
                EnvVarMappingCore.isUnderEnvPath(node)
            ) results += node
            node.children.forEach(::walk)
        }
        walk(element)
        return results
    }
}
