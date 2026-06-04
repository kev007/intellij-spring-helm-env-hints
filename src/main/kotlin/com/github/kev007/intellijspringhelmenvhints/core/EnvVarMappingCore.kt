package com.github.kev007.intellijspringhelmenvhints.core

import com.github.kev007.intellijspringhelmenvhints.models.*
import com.intellij.openapi.util.TextRange as IdeaTextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLSequence

/**
 * Pure parsing and navigation utilities for Spring and Helm YAML files.
 *
 * This module is stateless and has no dependencies on PSI caching or services.
 * All operations are deterministic and based on PSI tree traversal or simple text scanning.
 *
 * The module is organized into three main areas:
 * 1. File type detection (recognising Spring vs Helm files)
 * 2. Spring YAML parsing (extracting property keys and env var references via PSI)
 * 3. Helm template analysis (extracting env var names and validating structure)
 */
object EnvVarMappingCore {

    /**
     * Matches valid environment variable / Spring property placeholder names inside ${…}.
     * Accepts UPPER_SNAKE_CASE, lower.dotted, and mixed forms used in both Helm and Spring.
     */
    private const val ENV_VAR_NAME_PATTERN = """[A-Za-z_][A-Za-z0-9_.]*"""

    /** Regex for ${NAME} and ${NAME:default} patterns in Spring values. */
    val envVarRefRegex = Regex("""\$\{($ENV_VAR_NAME_PATTERN)(?::[^}]*)?\}""")

    // ─── File-type detection ──────────────────────────────────────────────────

    fun isYamlFile(file: VirtualFile) =
        file.name.lowercase().let { it.endsWith(".yaml") || it.endsWith(".yml") }

    fun isSpringApplicationFile(file: VirtualFile) =
        file.name.lowercase().let { it.startsWith("application") && (it.endsWith(".yaml") || it.endsWith(".yml")) }

    fun isHelmTemplateFile(file: VirtualFile) =
        file.path.lowercase().replace('\\', '/').let {
            (it.endsWith(".yaml") || it.endsWith(".yml")) && it.contains("/templates/")
        }

    // ─── Spring property key handling ──────────────────────────────────────────

    /**
     * Converts a Spring property key path (e.g., "server.port.number") to an environment variable name.
     * Replaces any run of non-alphanumeric characters with a single underscore and uppercases the result.
     */
    fun springKeyToEnvVarName(key: String): String =
        key.replace("[", "_").replace("]", "")
            .replace(Regex("[^A-Za-z0-9]+"), "_")
            .trim('_').uppercase()

    /**
     * Walks a Spring application YAML file via the PSI tree, yielding fully-qualified key paths
     * alongside their PSI key elements. Handles nested mappings and indexed sequences.
     */
    internal fun springKeyOccurrences(yamlFile: YAMLFile): List<SpringKeyOccurrence> {
        val results = mutableListOf<SpringKeyOccurrence>()
        fun walk(mapping: YAMLMapping, prefix: String) {
            for (kv in mapping.keyValues) {
                val fullKey = if (prefix.isEmpty()) kv.keyText else "$prefix.${kv.keyText}"
                kv.key?.let { results += SpringKeyOccurrence(fullKey, it) }
                when (val v = kv.value) {
                    is YAMLMapping -> walk(v, fullKey)
                    is YAMLSequence -> v.items.forEachIndexed { i, item ->
                        (item.value as? YAMLMapping)?.let { walk(it, "$fullKey[$i]") }
                    }
                }
            }
        }
        for (doc in yamlFile.documents) (doc.topLevelValue as? YAMLMapping)?.let { walk(it, "") }
        return results
    }

    /**
     * Returns the fully-qualified YAML key path for a given YAMLKeyValue by walking up the PSI tree.
     * E.g., a `url` key nested under `spring > datasource` yields "spring.datasource.url".
     */
    internal fun yamlKeyPath(keyValue: YAMLKeyValue): String {
        val parts = ArrayDeque<String>()
        var current: PsiElement? = keyValue
        while (current != null) {
            if (current is YAMLKeyValue) parts.addFirst(current.keyText)
            current = current.parent
        }
        return parts.joinToString(".")
    }

    // ─── Env-var reference span extraction ─────────────────────────────────────

    /**
     * Finds all env var references (${…} patterns) within a given text range.
     * Returns the env var name and its absolute start/end offsets in the file text.
     */
    fun envVarReferenceSpansInRange(text: String, startOffset: Int, endOffset: Int): List<EnvVarReferenceSpan> {
        if (startOffset >= endOffset) return emptyList()
        return envVarRefRegex.findAll(text).mapNotNull { match ->
            val group = match.groups[1] ?: return@mapNotNull null
            if (group.range.last + 1 <= startOffset || group.range.first >= endOffset) return@mapNotNull null
            EnvVarReferenceSpan(group.value, group.range.first, group.range.last + 1)
        }.toList()
    }

    /**
     * Finds all env var references within a Spring YAML key-value pair's value element.
     */
    fun springEnvReferencesInValue(keyValue: YAMLKeyValue): List<EnvVarReferenceSpan> {
        val value = keyValue.value ?: return emptyList()
        val range = value.textRange ?: return emptyList()
        return envVarReferenceSpansInRange(
            keyValue.containingFile?.text ?: return emptyList(),
            range.startOffset, range.endOffset,
        )
    }

    /**
     * Finds an env var reference at the given offset, returning its range relative to [fileElement].
     */
    fun springEnvReferenceAtOffset(fileElement: PsiElement, offset: Int): EnvVarRefAtOffset? {
        val fileText = fileElement.containingFile?.text ?: return null
        val span = envVarReferenceSpansInRange(fileText, offset, offset + 1).firstOrNull() ?: return null
        val elementRange = fileElement.textRange ?: return null
        if (span.startOffset < elementRange.startOffset || span.endOffset > elementRange.endOffset) return null
        return EnvVarRefAtOffset(
            envVar = span.envVar,
            rangeInElement = IdeaTextRange(
                span.startOffset - elementRange.startOffset,
                span.endOffset - elementRange.startOffset,
            ),
        )
    }

    /**
     * Returns the env var name at the given offset in a Spring application file.
     * Checks for ${ENV_VAR} references first, then derives the name from the YAML key path.
     */
    fun springEnvVarAtOffset(psiFile: PsiElement, offset: Int): String? {
        val fileText = psiFile.text
        envVarRefRegex.findAll(fileText).forEach { match ->
            val group = match.groups[1] ?: return@forEach
            if (offset in group.range) return group.value
        }
        var current: PsiElement? = (psiFile as? PsiFile)?.findElementAt(offset)
        while (current != null) {
            if (current is YAMLKeyValue) return springKeyToEnvVarName(yamlKeyPath(current))
            current = current.parent
        }
        return null
    }

    // ─── Helm env-name span extraction ────────────────────────────────────────

    /**
     * Extracts the env var name span from a Helm `name:` value element under containers/env.
     * Returns null if the element is not in the proper Helm structure or is empty.
     */
    fun helmEnvNameSpan(valueElement: PsiElement): EnvVarReferenceSpan? {
        val keyValue = valueElement.parent as? YAMLKeyValue ?: return null
        if (keyValue.keyText != "name" || keyValue.value != valueElement || !isUnderEnvPath(keyValue)) return null
        val raw = valueElement.text
        val unquoted = raw.trim().trim('"', '\'')
        if (unquoted.isBlank()) return null
        val localStart = raw.indexOf(unquoted).takeIf { it >= 0 } ?: return null
        val start = valueElement.textRange.startOffset + localStart
        return EnvVarReferenceSpan(unquoted, start, start + unquoted.length)
    }

    /**
     * Returns the env-var name most relevant to the given element's position.
     *
     * For Spring values: if the element sits inside a `${ENV_VAR}` expression that env var is
     * returned; otherwise the key-path env var is returned.
     * For Helm templates: the `name:` value under containers/env is returned.
     */
    fun envVarForElement(element: PsiElement): String? {
        val file = element.containingFile ?: return null
        val virtualFile = file.virtualFile ?: return null
        if (!isYamlFile(virtualFile)) return null

        var cur: PsiElement? = element
        while (cur != null) {
            if (cur is YAMLKeyValue) break
            cur = cur.parent
        }
        val keyValue = cur as? YAMLKeyValue ?: return null

        return when {
            isSpringApplicationFile(virtualFile) -> {
                val valueElement = keyValue.value
                val refs = springEnvReferencesInValue(keyValue)
                if (valueElement != null &&
                    element.textRange.startOffset >= valueElement.textRange.startOffset &&
                    refs.isNotEmpty()
                ) return refs.first().envVar
                springKeyToEnvVarName(yamlKeyPath(keyValue)).ifEmpty { null } ?: refs.firstOrNull()?.envVar
            }
            isHelmTemplateFile(virtualFile) ->
                helmEnvNameSpan(keyValue.value ?: return null)?.envVar
            else -> null
        }
    }

    /**
     * Extracts an env var reference from a Helm `name:` key at the given caret offset.
     */
    fun envVarAtOffset(psiFile: PsiElement, offset: Int): String? {
        var current: PsiElement? = (psiFile as? PsiFile)?.findElementAt(offset)
        while (current != null) {
            if (current is YAMLKeyValue && current.keyText == "name") return extractHelmEnvVarValue(current)
            current = current.parent
        }
        return null
    }

    /**
     * Extracts the env var name from a Helm `name:` key-value pair under containers/env.
     */
    fun extractHelmEnvVarValue(nameKeyValue: YAMLKeyValue): String? {
        if (!isUnderEnvPath(nameKeyValue)) return null
        return nameKeyValue.value?.text?.trim('"', '\'')?.takeIf { it.isNotBlank() }
    }

    /**
     * Checks if the given element is nested under the Helm containers > env structure.
     * This ensures we only match actual env var definitions, not unrelated `name:` keys.
     */
    fun isUnderEnvPath(element: PsiElement): Boolean {
        var foundEnv = false
        var foundContainers = false
        var current: PsiElement? = element.parent
        while (current != null) {
            val keyText = when (current) {
                is YAMLKeyValue -> current.keyText
                is YAMLSequence -> (current.parent as? YAMLKeyValue)?.keyText
                else -> null
            }
            when (keyText) {
                "env" -> foundEnv = true
                "containers" -> foundContainers = true
            }
            current = current.parent
        }
        return foundEnv && foundContainers
    }
}
