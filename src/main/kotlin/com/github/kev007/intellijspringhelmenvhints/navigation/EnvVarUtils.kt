package com.github.kev007.intellijspringhelmenvhints.navigation

import com.github.kev007.intellijspringhelmenvhints.settings.HelmEnvHintsSettings
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.diagnostic.debug
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileFilter
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.yaml.YAMLLanguage
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLScalar
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

/**
 * The YAML PSI root of the view provider this [PsiFile] belongs to.
 *
 * Same idea as [yamlPsi], but starting from a PSI file that has already been resolved (the
 * one an inspection / inlay pass was handed), so a Helm template owned by `HelmYAML` or
 * `GoTemplate` resolves to its template-data YAML root instead of returning null.
 */
internal fun PsiFile.yamlRoot(): YAMLFile? =
    this as? YAMLFile ?: viewProvider.getPsi(YAMLLanguage.INSTANCE) as? YAMLFile


// ─── Spring YAML utilities ────────────────────────────────────────────────────

/** Matches ${NAME} and ${NAME:default} in Spring property values. */
internal val ENV_REF_REGEX = Regex("""\$\{([A-Za-z_][A-Za-z0-9_.\-]*?)(?::[^}]*)?}""")

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

/**
 * An env var name occurrence, with absolute (file-level) offsets.
 *
 * [startOffset]/[endOffset] delimit the NAME itself (that is what gets highlighted and what
 * a PSI reference covers), while [tagOffset] is the offset the "N refs" tag is anchored to:
 * the end of the whole occurrence (`${NAME:default}` including the closing brace, or the end
 * of the — possibly quoted — Helm `name:` value), so the tag never lands inside the text.
 */
internal data class EnvSpan(
    val envVar: String,
    val startOffset: Int,
    val endOffset: Int,
    val tagOffset: Int = endOffset,
) {
    operator fun contains(offset: Int) = offset in startOffset until endOffset

    /** This span expressed relative to [element]'s own text range. */
    fun relativeTo(element: PsiElement): TextRange {
        val base = element.textRange.startOffset
        return TextRange(startOffset - base, endOffset - base)
    }
}

/**
 * Returns the NAME spans of every `${NAME}` / `${NAME:default}` reference inside
 * [element]'s own text.
 *
 * Scanning the element text (instead of the whole file text) keeps this linear in the
 * element size; the reference contributor and the annotator are invoked for *every* leaf
 * of a file, so a file-wide scan per element would be quadratic.
 */
internal fun springRefSpans(element: PsiElement): List<EnvSpan> {
    val base = element.textRange?.startOffset ?: return emptyList()
    return ENV_REF_REGEX.findAll(element.text).mapNotNull { m ->
        m.groups[1]?.let { g ->
            EnvSpan(
                envVar      = g.value,
                startOffset = base + g.range.first,
                endOffset   = base + g.range.last + 1,
                // Anchor the tag after the closing '}' of the whole ${...} expression.
                tagOffset   = base + m.range.last + 1,
            )
        }
    }.toList()
}

/**
 * Returns the logical env var name at [offset] in a Spring application file: either the
 * `${ENV_VAR}` reference under the caret, or the env var derived from the enclosing
 * property key path.
 */
internal fun springEnvVarAtOffset(file: PsiFile, offset: Int): String? {
    var cur: PsiElement? = file.findElementAt(offset)
    while (cur != null) {
        springRefSpans(cur).firstOrNull { offset in it }?.let { return it.envVar }
        if (cur is YAMLKeyValue) return springKeyToEnvVar(yamlKeyPath(cur))
        cur = cur.parent
    }
    return null
}

// ─── Helm YAML utilities ──────────────────────────────────────────────────────

/** True when [kv] is a `name:` entry under the Helm `containers > env` path. */
private fun isHelmEnvNameKv(kv: YAMLKeyValue) = kv.keyText == "name" && isUnderEnvPath(kv)

/** The unquoted, non-blank env var name held by a Helm `name:` value, or null. */
private fun PsiElement.helmEnvName(): String? =
    text.trim().trim('"', '\'').takeIf { it.isNotBlank() }

/** Returns the env var span from a Helm `name:` value element under containers/env. */
internal fun helmEnvNameSpan(valueElement: PsiElement): EnvSpan? {
    val kv = valueElement.parent as? YAMLKeyValue ?: return null
    if (kv.value != valueElement || !isHelmEnvNameKv(kv)) return null
    val name = valueElement.helmEnvName() ?: return null
    val start = valueElement.textRange.startOffset + valueElement.text.indexOf(name)
    // Anchor the tag after the value (past any surrounding quotes).
    return EnvSpan(name, start, start + name.length, valueElement.textRange.endOffset)
}

/** Returns the Helm env var name at [offset] from a `name:` key under containers/env. */
internal fun helmEnvVarAtOffset(file: PsiFile, offset: Int): String? {
    var cur: PsiElement? = file.findElementAt(offset)
    while (cur != null) {
        if (cur is YAMLKeyValue && isHelmEnvNameKv(cur)) return cur.value?.helmEnvName()
        cur = cur.parent
    }
    return null
}

/** True when [element] is nested under the Helm `containers > env` path. */
internal fun isUnderEnvPath(element: PsiElement): Boolean {
    var foundEnv = false
    var foundContainers = false
    var cur: PsiElement? = element.parent
    while (cur != null && !(foundEnv && foundContainers)) {
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

// ─── File-wide occurrence walk ────────────────────────────────────────────────

/**
 * Every env var occurrence in [yamlFile], from the Spring side (`${ENV_VAR}` inside scalar
 * values) or from the Helm side (`containers > env > name:` values).
 *
 * Each occurrence is reported EXACTLY ONCE. Walking scalars — rather than "the value of a
 * key/value pair" — is what guarantees that: scalars never nest, whereas a `YAMLMapping` is
 * both a value itself and the owner of every `${ENV_VAR}` in its subtree, so a value-based
 * walk reports a deeply nested reference once per enclosing mapping level. As a bonus,
 * scalars inside sequences (`args: [ ${FOO} ]`) are covered too, matching what the index
 * (which scans the raw file text) already records.
 */
internal fun envSpansInFile(yamlFile: YAMLFile, fromSpring: Boolean): List<EnvSpan> =
    if (fromSpring) {
        PsiTreeUtil.findChildrenOfType(yamlFile, YAMLScalar::class.java).flatMap(::springRefSpans)
    } else {
        PsiTreeUtil.findChildrenOfType(yamlFile, YAMLKeyValue::class.java)
            .mapNotNull { kv -> kv.value?.let(::helmEnvNameSpan) }
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
    // Folders marked "Excluded" in the project structure (build, target, out, …) hold
    // generated copies of resources. Scanning them would duplicate — and sometimes
    // invent — matches, so they are skipped unless the user opts in.
    // Returning false from the filter makes iterateChildrenRecursively skip the file
    // AND its children, so an excluded directory is never descended into.
    val fileIndex = ProjectRootManager.getInstance(project).fileIndex
    val fileFilter = if (HelmEnvHintsSettings.instance.state.includeExcludedFolders) null
                     else VirtualFileFilter { !fileIndex.isExcluded(it) }
    for (module in ModuleManager.getInstance(project).modules) {
        ModuleRootManager.getInstance(module).contentRoots.forEach { root ->
            VfsUtilCore.iterateChildrenRecursively(root, fileFilter) { vf ->
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

    LOG.debug {
        "Built env index for '${project.name}': scopes=${scopeIdByRoot.size}, " +
            "modules=${modulesWithData.map { it.name }.distinct()}"
    }
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
        if (node is YAMLKeyValue && isHelmEnvNameKv(node)) {
            val value = node.value
            val name = value?.helmEnvName()
            if (name != null) map.getOrPut(name) { mutableListOf() } += value
            return
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

/**
 * True when [envVar] is present on BOTH sides within the scope of [module].
 *
 * Matching is symmetric by construction, so the same query answers "is this Spring
 * reference matched?" and "is this Helm entry matched?".
 */
internal fun isEnvMatched(envVar: String, project: Project, module: Module): Boolean =
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

/**
 * The counterpart occurrences of [envVar] as seen from one side of the match: the Helm
 * `env[*].name` entries when [fromSpring] is true, the Spring `${ENV_VAR}` / property-key
 * occurrences otherwise.
 *
 * The result is deduplicated and never contains an element from [sourceFile] itself, so its
 * size is exactly the number of places the user can navigate to — which is what the
 * reference-count tag shows.
 */
internal fun counterpartRefs(
    envVar: String,
    project: Project,
    module: Module,
    sourceFile: VirtualFile?,
    fromSpring: Boolean,
): List<PsiElement> {
    val match = resolveEnvMatch(envVar, project, module) ?: return emptyList()
    val raw = if (fromSpring) match.helmElements else match.springElements
    return excludingSelf(deduplicated(raw), sourceFile)
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
 * Returns a [ModuleDebugInfo] list for [project], one entry per matching scope
 * (or a single project-wide entry when cross-module matching is enabled).
 * Re-uses the same cached index (and the same matching rules) as the annotator
 * and goto handler, so it reflects exactly what the user sees.
 */
fun getDebugInfo(project: Project): List<ModuleDebugInfo> {
    val index = projectEnvIndex(project)

    fun summarise(
        name: String,
        helm: Map<String, List<PsiElement>>,
        spring: Map<String, List<PsiElement>>,
    ) = ModuleDebugInfo(
        moduleName     = name,
        matchedVars    = (helm.keys intersect spring.keys).sorted(),
        helmOnlyVars   = (helm.keys - spring.keys).sorted(),
        springOnlyVars = (spring.keys - helm.keys).sorted(),
    )

    if (HelmEnvHintsSettings.instance.state.matchAcrossModules) {
        return listOf(
            summarise("All modules (project-wide matching)", index.helmProjectWide, index.springProjectWide)
        )
    }

    // One entry per scope, labelled by the member module names.
    val namesByScope = index.scopeOfModule.entries.groupBy({ it.value }, { it.key.name })
    return (index.helmByScope.keys + index.springByScope.keys).map { scope ->
        summarise(
            namesByScope[scope]?.sorted()?.joinToString(", ") ?: "scope-$scope",
            index.helmByScope[scope].orEmpty(),
            index.springByScope[scope].orEmpty(),
        )
    }.sortedBy { it.moduleName }
}




