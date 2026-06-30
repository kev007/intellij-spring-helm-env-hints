package com.github.kev007.intellijspringhelmenvhints.navigation

import com.github.kev007.intellijspringhelmenvhints.settings.HelmEnvHintsSettings
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLSequence

private val LOG = Logger.getInstance("com.github.kev007.intellijspringhelmenvhints.EnvVarIndex")

// ─── File-type detection ──────────────────────────────────────────────────────

internal fun VirtualFile.isYaml() =
    name.lowercase().let { it.endsWith(".yaml") || it.endsWith(".yml") }

internal fun VirtualFile.isSpringApp() =
    isYaml() && name.lowercase().startsWith("application")

internal fun VirtualFile.isHelmTemplate() =
    isYaml() && path.lowercase().replace('\\', '/').contains("/templates/")

// ─── Spring YAML utilities ────────────────────────────────────────────────────

/** Matches ${NAME} and ${NAME:default} in Spring property values. */
internal val ENV_REF_REGEX = Regex("""\$\{([A-Za-z_][A-Za-z0-9_.\-]*?)(?::[^}]*)?\}""")

internal fun springKeyToEnvVar(key: String): String =
    key.replace("[", "_").replace("]", "")
        .replace(Regex("[^A-Za-z0-9]+"), "_")
        .trim('_').uppercase()

/** Walks a Spring YAML file and returns (fullKeyPath → key PSI element) pairs. */
internal fun springKeyOccurrences(yamlFile: YAMLFile): List<Pair<String, PsiElement>> {
    val results = mutableListOf<Pair<String, PsiElement>>()
    fun walk(mapping: YAMLMapping, prefix: String) {
        for (kv in mapping.keyValues) {
            val fullKey = if (prefix.isEmpty()) kv.keyText else "$prefix.${kv.keyText}"
            kv.key?.let { results += fullKey to it }
            when (val v = kv.value) {
                is YAMLMapping -> walk(v, fullKey)
                is YAMLSequence -> v.items.forEachIndexed { i, item ->
                    (item.value as? YAMLMapping)?.let { walk(it, "$fullKey[$i]") }
                }
            }
        }
    }
    yamlFile.documents.forEach { (it.topLevelValue as? YAMLMapping)?.let { m -> walk(m, "") } }
    return results
}

/** Builds the fully-qualified key path for a [YAMLKeyValue] by walking up the PSI tree. */
private fun yamlKeyPath(kv: YAMLKeyValue): String {
    val parts = ArrayDeque<String>()
    var cur: PsiElement? = kv
    while (cur != null) {
        if (cur is YAMLKeyValue) parts.addFirst(cur.keyText)
        cur = cur.parent
    }
    return parts.joinToString(".")
}

internal data class EnvSpan(val envVar: String, val startOffset: Int, val endOffset: Int)

/** Returns all ${ENV_VAR} NAME spans that fall within the absolute range [start, end). */
internal fun envSpansInRange(text: String, start: Int, end: Int): List<EnvSpan> =
    ENV_REF_REGEX.findAll(text).mapNotNull { m ->
        val g = m.groups[1] ?: return@mapNotNull null
        if (g.range.last + 1 <= start || g.range.first >= end) null
        else EnvSpan(g.value, g.range.first, g.range.last + 1)
    }.toList()

/**
 * Finds the first ${ENV_VAR} pattern contained within [element]'s text range.
 * Returns the env var name and the range of the NAME group relative to [element].
 */
internal fun springRefInElement(element: PsiElement): Pair<String, TextRange>? {
    val elemRange = element.textRange ?: return null
    val fileText = element.containingFile?.text ?: return null
    val match = ENV_REF_REGEX.findAll(fileText).firstOrNull { m ->
        m.range.first >= elemRange.startOffset && m.range.last + 1 <= elemRange.endOffset
    } ?: return null
    val g = match.groups[1] ?: return null
    return g.value to TextRange(
        g.range.first - elemRange.startOffset,
        g.range.last + 1 - elemRange.startOffset,
    )
}

/** Returns the logical env var name at [offset] in a Spring application file. */
internal fun springEnvVarAtOffset(file: PsiFile, offset: Int): String? {
    ENV_REF_REGEX.findAll(file.text).forEach { m ->
        val g = m.groups[1] ?: return@forEach
        if (offset in g.range) return g.value
    }
    var cur: PsiElement? = file.findElementAt(offset)
    while (cur != null) {
        if (cur is YAMLKeyValue) return springKeyToEnvVar(yamlKeyPath(cur))
        cur = cur.parent
    }
    return null
}

// ─── Helm YAML utilities ──────────────────────────────────────────────────────

/** Returns the env var span from a Helm `name:` value element under containers/env. */
internal fun helmEnvNameSpan(valueElement: PsiElement): EnvSpan? {
    val kv = valueElement.parent as? YAMLKeyValue ?: return null
    if (kv.keyText != "name" || kv.value != valueElement || !isUnderEnvPath(kv)) return null
    val raw = valueElement.text
    val unquoted = raw.trim().trim('"', '\'')
    if (unquoted.isBlank()) return null
    val localStart = raw.indexOf(unquoted).takeIf { it >= 0 } ?: return null
    val start = valueElement.textRange.startOffset + localStart
    return EnvSpan(unquoted, start, start + unquoted.length)
}

/** Returns the Helm env var name at [offset] from a `name:` key under containers/env. */
internal fun helmEnvVarAtOffset(file: PsiFile, offset: Int): String? {
    var cur: PsiElement? = file.findElementAt(offset)
    while (cur != null) {
        if (cur is YAMLKeyValue && cur.keyText == "name" && isUnderEnvPath(cur))
            return cur.value?.text?.trim('"', '\'')?.takeIf { it.isNotBlank() }
        cur = cur.parent
    }
    return null
}

/** True when [element] is nested under the Helm `containers > env` path. */
internal fun isUnderEnvPath(element: PsiElement): Boolean {
    var foundEnv = false
    var foundContainers = false
    var cur: PsiElement? = element.parent
    while (cur != null) {
        val key = when (cur) {
            is YAMLKeyValue -> cur.keyText
            is YAMLSequence -> (cur.parent as? YAMLKeyValue)?.keyText
            else -> null
        }
        if (key == "env") foundEnv = true
        if (key == "containers") foundContainers = true
        cur = cur.parent
    }
    return foundEnv && foundContainers
}

// ─── Module-scoped index ──────────────────────────────────────────────────────

private data class EnvIndex(
    /** envVar → Helm `name:` value PSI elements, per module. */
    val helm: Map<Module, Map<String, List<PsiElement>>>,
    /** envVar → Spring key / ${...} PSI elements, per module. */
    val spring: Map<Module, Map<String, List<PsiElement>>>,
)

private fun buildIndex(project: Project): EnvIndex {
    val psiManager = PsiManager.getInstance(project)
    val helmByModule = mutableMapOf<Module, MutableMap<String, MutableList<PsiElement>>>()
    val springByModule = mutableMapOf<Module, MutableMap<String, MutableList<PsiElement>>>()

    for (module in ModuleManager.getInstance(project).modules) {
        val helmMap = mutableMapOf<String, MutableList<PsiElement>>()
        val springMap = mutableMapOf<String, MutableList<PsiElement>>()

        ModuleRootManager.getInstance(module).contentRoots.forEach { root ->
            VfsUtilCore.iterateChildrenRecursively(root, null) { vf ->
                if (!vf.isDirectory && vf.isYaml()) {
                    val yaml = psiManager.findFile(vf) as? YAMLFile
                    if (yaml != null) when {
                        vf.isHelmTemplate() -> collectHelm(yaml, helmMap)
                        vf.isSpringApp() -> collectSpring(yaml, springMap)
                    }
                }
                true
            }
        }

        if (helmMap.isNotEmpty()) helmByModule[module] = helmMap
        if (springMap.isNotEmpty()) springByModule[module] = springMap
    }

    LOG.info("Built env index for '${project.name}': modules=${
        (helmByModule.keys + springByModule.keys).map { it.name }.distinct()
    }")
    return EnvIndex(helmByModule, springByModule)
}

private fun collectHelm(yaml: YAMLFile, map: MutableMap<String, MutableList<PsiElement>>) {
    fun walk(node: PsiElement) {
        if (node is YAMLKeyValue && node.keyText == "name" && isUnderEnvPath(node)) {
            val name = node.value?.text?.trim('"', '\'')?.takeIf { it.isNotBlank() } ?: return
            node.value?.let { map.getOrPut(name) { mutableListOf() } += it }
        }
        node.children.forEach(::walk)
    }
    walk(yaml)
}

private fun collectSpring(yaml: YAMLFile, map: MutableMap<String, MutableList<PsiElement>>) {
    if (HelmEnvHintsSettings.instance.state.springKeyMatchingEnabled) {
        springKeyOccurrences(yaml).forEach { (key, el) ->
            map.getOrPut(springKeyToEnvVar(key)) { mutableListOf() } += el
        }
    }
    ENV_REF_REGEX.findAll(yaml.text).forEach { m ->
        val g = m.groups[1] ?: return@forEach
        yaml.findElementAt(g.range.first)?.let { map.getOrPut(g.value) { mutableListOf() } += it }
    }
}

/** Returns the project-wide env index, rebuilt only when PSI changes or index-relevant settings change. */
private fun projectEnvIndex(project: Project): EnvIndex =
    CachedValuesManager.getManager(project).getCachedValue(project) {
        CachedValueProvider.Result.create(
            buildIndex(project),
            PsiModificationTracker.MODIFICATION_COUNT,
            HelmEnvHintsSettings.instance.indexTracker
        )
    }

// ─── Public lookup API ────────────────────────────────────────────────────────

internal fun findHelmTargets(envVar: String, project: Project, module: Module): List<PsiElement> =
    projectEnvIndex(project).helm[module]?.get(envVar).orEmpty().also {
        if (it.isNotEmpty()) {
            LOG.info("[$envVar] Spring → Helm: ${it.size} target(s) in '${module.name}'")
        } else {
            LOG.info("what")
        }
    }

internal fun findSpringTargets(envVar: String, project: Project, module: Module): List<PsiElement> =
    projectEnvIndex(project).spring[module]?.get(envVar).orEmpty().also {
        if (it.isNotEmpty()) LOG.info("[$envVar] Helm → Spring: ${it.size} target(s) in '${module.name}'")
    }

internal fun isSpringMatched(envVar: String, project: Project, module: Module): Boolean =
    projectEnvIndex(project).helm[module]?.containsKey(envVar) == true

internal fun isHelmMatched(envVar: String, project: Project, module: Module): Boolean =
    projectEnvIndex(project).spring[module]?.containsKey(envVar) == true

/** Deduplicates PSI elements by (virtual file path, text offset). */
internal fun deduplicated(elements: List<PsiElement>): List<PsiElement> =
    elements.distinctBy { "${it.containingFile?.virtualFile?.path}:${it.textRange?.startOffset}" }

// ─── Debug / settings panel API ───────────────────────────────────────────────

/** Per-module summary of env var matching state, shown in the plugin settings debug accordion. */
data class ModuleDebugInfo(
    val moduleName: String,
    /** Vars present in BOTH Helm templates and Spring application files. */
    val matchedVars: List<String>,
    /** Vars found only in Helm templates (no Spring counterpart). */
    val helmOnlyVars: List<String>,
    /** Vars found only in Spring application files (no Helm counterpart). */
    val springOnlyVars: List<String>,
)

/**
 * Returns a [ModuleDebugInfo] list for [project], one entry per module that has any env var data.
 * Re-uses the same cached index used by the annotator and goto handler.
 */
fun getDebugInfo(project: Project): List<ModuleDebugInfo> {
    val index = projectEnvIndex(project)
    val allModules = (index.helm.keys + index.spring.keys).toSet()
    return allModules.map { module ->
        val helmKeys   = index.helm[module]?.keys?.toSet()   ?: emptySet()
        val springKeys = index.spring[module]?.keys?.toSet() ?: emptySet()
        ModuleDebugInfo(
            moduleName    = module.name,
            matchedVars   = (helmKeys intersect springKeys).sorted(),
            helmOnlyVars  = (helmKeys - springKeys).sorted(),
            springOnlyVars = (springKeys - helmKeys).sorted(),
        )
    }.sortedBy { it.moduleName }
}




