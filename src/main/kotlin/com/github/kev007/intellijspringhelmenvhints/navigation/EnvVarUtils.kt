package com.github.kev007.intellijspringhelmenvhints.navigation

import com.github.kev007.intellijspringhelmenvhints.settings.HelmEnvHintsSettings
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.module.ModuleUtilCore
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
import org.jetbrains.yaml.YAMLLanguage
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

/**
 * Returns the YAML PSI root for this file, even when another plugin (e.g. "Go Template")
 * turns Helm templates into a template-language file where YAML is only the
 * *template-data* language rather than the file's base language.
 *
 * [PsiManager.findFile] returns the PSI for the file's BASE language, which for a
 * Go-template file is a `GoTemplateFile`, not a `YAMLFile`. Going through the view
 * provider and asking for the YAML language explicitly works uniformly for both plain
 * YAML files and template-data YAML, so the Helm side of the index is no longer lost.
 */
internal fun VirtualFile.yamlPsi(psiManager: PsiManager): YAMLFile? =
    psiManager.findViewProvider(this)?.getPsi(YAMLLanguage.INSTANCE) as? YAMLFile

/** [yamlPsi] variant that starts from an already-resolved [PsiFile]. */
internal fun PsiFile.yamlRoot(): YAMLFile? =
    viewProvider.getPsi(YAMLLanguage.INSTANCE) as? YAMLFile

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

// ─── Scope-based match index ──────────────────────────────────────────────────

/**
 * A single resolved match: an env var present on BOTH the Helm side and the
 * Spring side within one matching scope. This is the *single* source of truth
 * used by highlighting and navigation in both directions, so a match is computed
 * exactly once and is guaranteed to be symmetric.
 */
internal data class EnvMatch(
    val envVar: String,
    val helmElements: List<PsiElement>,
    val springElements: List<PsiElement>,
)

private class EnvIndex(
    /** Module → id of the matching scope it belongs to. */
    val scopeOfModule: Map<Module, Int>,
    /** scope id → (envVar → Helm elements) aggregated across the scope's modules. */
    val helmByScope: Map<Int, Map<String, List<PsiElement>>>,
    /** scope id → (envVar → Spring elements) aggregated across the scope's modules. */
    val springByScope: Map<Int, Map<String, List<PsiElement>>>,
    /** Project-wide Helm aggregate (all modules) — used when cross-module matching is on. */
    val helmProjectWide: Map<String, List<PsiElement>>,
    /** Project-wide Spring aggregate (all modules) — used when cross-module matching is on. */
    val springProjectWide: Map<String, List<PsiElement>>,
)

private fun buildIndex(project: Project): EnvIndex {
    val psiManager = PsiManager.getInstance(project)
    val helmByModule = mutableMapOf<Module, MutableMap<String, MutableList<PsiElement>>>()
    val springByModule = mutableMapOf<Module, MutableMap<String, MutableList<PsiElement>>>()

    // Index each YAML file exactly once, under its DEEPEST owning module (the
    // module IntelliJ reports for that file). This is what prevents the leak: an
    // aggregate / root module whose content root spans the whole repository must
    // NOT index every module's files, otherwise matches bleed across unrelated
    // project modules.
    val visited = HashSet<String>()
    for (module in ModuleManager.getInstance(project).modules) {
        ModuleRootManager.getInstance(module).contentRoots.forEach { root ->
            VfsUtilCore.iterateChildrenRecursively(root, null) { vf ->
                if (!vf.isDirectory && vf.isYaml() && visited.add(vf.path)) {
                    val owner = ModuleUtilCore.findModuleForFile(vf, project) ?: module
                    // Resolve the YAML root through the view provider so files owned by a
                    // template-language plugin (e.g. Go Template) are still indexed.
                    val yaml = vf.yamlPsi(psiManager)
                    if (yaml != null) when {
                        vf.isHelmTemplate() ->
                            collectHelm(yaml, helmByModule.getOrPut(owner) { mutableMapOf() })
                        vf.isSpringApp() ->
                            collectSpring(yaml, springByModule.getOrPut(owner) { mutableMapOf() })
                    }
                }
                true
            }
        }
    }
    helmByModule.entries.removeIf { it.value.isEmpty() }
    springByModule.entries.removeIf { it.value.isEmpty() }

    // ── Group modules into matching scopes ────────────────────────────────────
    //
    // Goal: keep every logically-distinct module in its own scope, while still
    // treating the several IntelliJ modules that make up ONE service as a single
    // scope.
    //
    // In a Gradle import IntelliJ splits one service into source-set modules
    // named "<service>.main" / "<service>.test", whose content roots are
    // sub-directories of the parent "<service>" module. Helm templates live under
    // the parent while Spring application.yaml lives under a child, so the two
    // sides of the SAME service can land in different modules and must share a
    // scope.
    //
    // We therefore merge a module into another ONLY when it is a genuine
    // source-set child by NAME (its name is "<parent>.<something>" and the parent
    // module exists) AND its content roots are nested inside that parent. We must
    // NOT merge by path containment alone: a root/aggregate module's content root
    // contains every sub-project, and a source-set parent is path-wise
    // indistinguishable from such an aggregate. Relying on the name relationship
    // is what keeps unrelated services (e.g. sibling modules under a common root)
    // in separate scopes instead of collapsing them all together.
    val modulesWithData = (helmByModule.keys + springByModule.keys).toList()
    val rootPaths = modulesWithData.associateWith { m ->
        ModuleRootManager.getInstance(m).contentRoots.map { it.path }
    }
    val moduleByName = modulesWithData.associateBy { it.name }

    val ufParent = HashMap<Module, Module>()
    modulesWithData.forEach { ufParent[it] = it }
    fun findRoot(m: Module): Module {
        var r = m
        while (ufParent.getValue(r) !== r) r = ufParent.getValue(r)
        var c = m
        while (ufParent.getValue(c) !== r) { val n = ufParent.getValue(c); ufParent[c] = r; c = n }
        return r
    }
    fun union(a: Module, b: Module) { ufParent[findRoot(a)] = findRoot(b) }

    for (child in modulesWithData) {
        // Source-set modules are named "<parent>.<sourceSet>" (e.g. ".main").
        if (!child.name.contains('.')) continue
        val parent = moduleByName[child.name.substringBeforeLast('.')] ?: continue
        if (parent === child) continue

        // Sanity-check the naming with the actual layout: the child's content
        // roots must sit inside the parent's. This prevents a coincidental name
        // match from merging modules that are not physically nested.
        val childRoots = rootPaths.getValue(child)
        val parentRoots = rootPaths.getValue(parent)
        val nested = childRoots.isNotEmpty() && parentRoots.isNotEmpty() &&
            childRoots.all { cp -> parentRoots.any { pp -> isSubPath(cp, pp) } }
        if (nested) union(child, parent)
    }

    val scopeIdByRoot = HashMap<Module, Int>()
    val scopeOfModule = HashMap<Module, Int>()
    for (m in modulesWithData) {
        val id = scopeIdByRoot.getOrPut(findRoot(m)) { scopeIdByRoot.size }
        scopeOfModule[m] = id
    }

    fun mergeInto(
        dst: MutableMap<String, MutableList<PsiElement>>,
        src: Map<String, List<PsiElement>>,
    ) { for ((k, v) in src) dst.getOrPut(k) { mutableListOf() }.addAll(v) }

    val helmByScope = HashMap<Int, MutableMap<String, MutableList<PsiElement>>>()
    val springByScope = HashMap<Int, MutableMap<String, MutableList<PsiElement>>>()
    for ((m, data) in helmByModule)
        mergeInto(helmByScope.getOrPut(scopeOfModule.getValue(m)) { mutableMapOf() }, data)
    for ((m, data) in springByModule)
        mergeInto(springByScope.getOrPut(scopeOfModule.getValue(m)) { mutableMapOf() }, data)

    val helmProjectWide = mutableMapOf<String, MutableList<PsiElement>>()
    val springProjectWide = mutableMapOf<String, MutableList<PsiElement>>()
    helmByModule.values.forEach { mergeInto(helmProjectWide, it) }
    springByModule.values.forEach { mergeInto(springProjectWide, it) }

    LOG.info("Built env index for '${project.name}': scopes=${scopeIdByRoot.size}, modules=${
        modulesWithData.map { it.name }.distinct()
    }")
    return EnvIndex(scopeOfModule, helmByScope, springByScope, helmProjectWide, springProjectWide)
}

/**
 * Returns true when [child] is the same path as [parent] or a sub-directory of it.
 * Paths are normalised to use '/' and a trailing separator is appended before comparison
 * so that "/foo/bar" is not considered a sub-path of "/foo/b".
 */
private fun isSubPath(child: String, parent: String): Boolean {
    val normChild  = child.replace('\\', '/').trimEnd('/') + '/'
    val normParent = parent.replace('\\', '/').trimEnd('/') + '/'
    return normChild.startsWith(normParent)
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

// ─── Single bidirectional match resolver ──────────────────────────────────────

/**
 * The one and only matching routine. Returns the [EnvMatch] for [envVar] as seen
 * from [module] — the env var must be present on BOTH the Helm and the Spring
 * side within the same scope — or null when it is not matched.
 *
 * Highlighting and navigation for BOTH directions are derived from this single
 * result, so exactly one match is generated per env var per scope and it is
 * symmetric by construction.
 *
 * The "match across all project modules" setting simply switches the scope from
 * the module's own scope to the whole project.
 */
internal fun resolveEnvMatch(envVar: String, project: Project, module: Module): EnvMatch? {
    val index = projectEnvIndex(project)
    val helm: Map<String, List<PsiElement>>
    val spring: Map<String, List<PsiElement>>
    if (HelmEnvHintsSettings.instance.state.matchAcrossModules) {
        helm = index.helmProjectWide
        spring = index.springProjectWide
    } else {
        val scope = index.scopeOfModule[module] ?: return null
        helm = index.helmByScope[scope].orEmpty()
        spring = index.springByScope[scope].orEmpty()
    }
    val helmElements = helm[envVar].orEmpty()
    val springElements = spring[envVar].orEmpty()
    if (helmElements.isEmpty() || springElements.isEmpty()) return null
    return EnvMatch(envVar, helmElements, springElements)
}

// ─── Public lookup API (thin wrappers over the single resolver) ────────────────

/** Spring → Helm navigation targets (the Helm side of the single match). */
internal fun findHelmTargets(envVar: String, project: Project, module: Module): List<PsiElement> =
    resolveEnvMatch(envVar, project, module)?.helmElements.orEmpty()

/** Helm → Spring navigation targets (the Spring side of the single match). */
internal fun findSpringTargets(envVar: String, project: Project, module: Module): List<PsiElement> =
    resolveEnvMatch(envVar, project, module)?.springElements.orEmpty()

/** True when a Spring env var has a matching Helm counterpart (same single match). */
internal fun isSpringMatched(envVar: String, project: Project, module: Module): Boolean =
    resolveEnvMatch(envVar, project, module) != null

/** True when a Helm env var has a matching Spring counterpart (same single match). */
internal fun isHelmMatched(envVar: String, project: Project, module: Module): Boolean =
    resolveEnvMatch(envVar, project, module) != null

/** Deduplicates PSI elements by (virtual file path, text offset). */
internal fun deduplicated(elements: List<PsiElement>): List<PsiElement> =
    elements.distinctBy { "${it.containingFile?.virtualFile?.path}:${it.textRange?.startOffset}" }

/**
 * Removes any target located in [sourceFile] itself.
 *
 * Spring→Helm and Helm→Spring navigation is always meant to be cross-file, so a
 * target resolved into the originating file is a spurious self-loop. This happens
 * when a single file qualifies as BOTH a Spring app file and a Helm template
 * (e.g. an `application.yaml` under a `/templates/` path, or a helm template named
 * `application*.yaml`): the index and the reference contributor then classify it
 * differently and a `${ENV_VAR}` reference resolves back to a `name: ENV_VAR` in
 * the same file. Filtering by originating file makes navigation robust regardless
 * of that classification.
 */
internal fun excludingSelf(targets: List<PsiElement>, sourceFile: VirtualFile?): List<PsiElement> {
    val self = sourceFile?.path ?: return targets
    return targets.filter { it.containingFile?.virtualFile?.path != self }
}

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
 * Returns a [ModuleDebugInfo] list for [project], one entry per matching scope.
 * Re-uses the same cached index (and the same matching rules) as the annotator
 * and goto handler, so it reflects exactly what the user sees.
 */
fun getDebugInfo(project: Project): List<ModuleDebugInfo> {
    val index = projectEnvIndex(project)

    if (HelmEnvHintsSettings.instance.state.matchAcrossModules) {
        val helmKeys = index.helmProjectWide.keys.toSet()
        val springKeys = index.springProjectWide.keys.toSet()
        return listOf(
            ModuleDebugInfo(
                moduleName     = "All modules (project-wide matching)",
                matchedVars    = (helmKeys intersect springKeys).sorted(),
                helmOnlyVars   = (helmKeys - springKeys).sorted(),
                springOnlyVars = (springKeys - helmKeys).sorted(),
            )
        )
    }

    // One entry per scope, labelled by the member module names.
    val namesByScope = index.scopeOfModule.entries.groupBy({ it.value }, { it.key.name })
    val scopeIds = (index.helmByScope.keys + index.springByScope.keys).toSet()
    return scopeIds.map { scope ->
        val helmKeys   = index.helmByScope[scope]?.keys?.toSet()   ?: emptySet()
        val springKeys = index.springByScope[scope]?.keys?.toSet() ?: emptySet()
        ModuleDebugInfo(
            moduleName     = namesByScope[scope]?.sorted()?.joinToString(", ") ?: "scope-$scope",
            matchedVars    = (helmKeys intersect springKeys).sorted(),
            helmOnlyVars   = (helmKeys - springKeys).sorted(),
            springOnlyVars = (springKeys - helmKeys).sorted(),
        )
    }.sortedBy { it.moduleName }
}




