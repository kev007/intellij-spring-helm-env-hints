package com.github.kev007.intellijspringhelmenvhints.core

import com.github.kev007.intellijspringhelmenvhints.models.*
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.openapi.util.TextRange as IdeaTextRange
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLSequence

/**
 * Pure parsing and navigation utilities for Spring and Helm YAML files.
 *
 * This module is stateless and has no dependencies on PSI caching or services.
 * All operations are deterministic and based on text/regex or simple tree traversal.
 *
 * The module is organized into three main areas:
 * 1. File type detection (recognizing Spring vs Helm files)
 * 2. Spring YAML parsing (extracting property keys and env var references)
 * 3. Helm template analysis (extracting env var names and validating structure)
 */
object EnvVarMappingCore {

    private val springKeyLineRegex = Regex("""^(\s*)([A-Za-z0-9_.-]+)\s*:\s*(.*)$""")
    private val nonAlphaNumRegex = Regex("""[^A-Za-z0-9]""")
    private val repeatedUnderscoreRegex = Regex("""_+""")

    /** Regex for matching ${ENV_VAR} and ${ENV_VAR:default} patterns in Spring values. */
    val envVarRefRegex = Regex("""\$\{([^}:\s]+)(?::([^}]*))?}""")

    // ─── File-type detection ──────────────────────────────────────────────────

    fun isYamlFile(file: VirtualFile): Boolean {
        val name = file.name.lowercase()
        return name.endsWith(".yaml") || name.endsWith(".yml")
    }

    fun isSpringApplicationFile(file: VirtualFile): Boolean {
        val name = file.name.lowercase()
        return name.startsWith("application") && (name.endsWith(".yaml") || name.endsWith(".yml"))
    }

    fun isHelmTemplateFile(file: VirtualFile): Boolean {
        val path = file.path.lowercase().replace('\\', '/')
        return (path.endsWith(".yaml") || path.endsWith(".yml")) && path.contains("/templates/")
    }

    // ─── Spring property key handling ──────────────────────────────────────────

    /**
     * Converts a Spring property key path (e.g., "server.port.number") to an environment variable name.
     * Normalizes the key by replacing non-alphanumeric characters with underscores and uppercasing.
     */
    fun springKeyToEnvVarName(key: String): String {
        val normalized = key
            .replace("[", "_")
            .replace("]", "")
            .replace(nonAlphaNumRegex, "_")
            .replace(repeatedUnderscoreRegex, "_")
            .trim('_')
        return normalized.uppercase()
    }

    /**
     * Finds the full property key path at the given character offset in Spring YAML text.
     * Walks through the YAML line-by-line, tracking indentation to maintain the key hierarchy.
     */
    fun springKeyAtOffset(text: String, offset: Int): String? {
        var matchAtOffset: String? = null
        scanSpringKeys(text) { keyPath, keyStart, key ->
            val keyEndExclusive = keyStart + key.length
            if (offset in keyStart until keyEndExclusive) {
                matchAtOffset = keyPath
                false
            } else {
                true
            }
        }
        return matchAtOffset
    }

    /**
     * Extracts all Spring property key occurrences from the given YAML text.
     * Returns both the fully-qualified key path and its character offset.
     */
    internal fun springKeyOccurrences(text: String): List<SpringKeyOccurrence> {
        val result = mutableListOf<SpringKeyOccurrence>()
        scanSpringKeys(text) { keyPath, keyStart, _ ->
            result += SpringKeyOccurrence(fullKey = keyPath, keyOffset = keyStart)
            true
        }
        return result
    }

    /**
     * Walks Spring-style YAML keys while tracking indentation-based key paths.
     * Returns false from [onKey] to stop scanning early.
     */
    private fun scanSpringKeys(
        text: String,
        onKey: (keyPath: String, keyStartOffset: Int, key: String) -> Boolean,
    ) {
        val lines = text.split('\n')
        val stack = mutableListOf<IndentKey>()
        var runningOffset = 0

        for (line in lines) {
            val lineStart = runningOffset
            val match = springKeyLineRegex.find(line)
            if (match != null) {
                val indent = match.groupValues[1].length
                val key = match.groupValues[2]

                while (stack.isNotEmpty() && indent <= stack.last().indent) {
                    stack.removeAt(stack.lastIndex)
                }
                stack += IndentKey(indent, key)

                val keyStart = lineStart + match.range.first + indent
                val keyPath = stack.joinToString(".") { it.key }
                if (!onKey(keyPath, keyStart, key)) return
            }
            runningOffset += line.length + 1
        }
    }

    // ─── Env-var reference span extraction ─────────────────────────────────────

    /**
     * Finds all env var references (${...} patterns) within a given text range.
     * Returns the env var name and its start/end offsets in the overall file.
     */
    fun envVarReferenceSpansInRange(text: String, startOffset: Int, endOffset: Int): List<EnvVarReferenceSpan> {
        if (startOffset >= endOffset) return emptyList()
        return envVarRefRegex.findAll(text).mapNotNull { match ->
            val group = match.groups[1] ?: return@mapNotNull null
            val groupStart = group.range.first
            val groupEnd = group.range.last + 1
            if (groupEnd <= startOffset || groupStart >= endOffset) return@mapNotNull null
            EnvVarReferenceSpan(group.value, groupStart, groupEnd)
        }.toList()
    }

    private fun envVarReferenceSpanAtOffset(text: String, offset: Int): EnvVarReferenceSpan? =
        envVarReferenceSpansInRange(text, offset, offset + 1).firstOrNull()

    private fun envVarReferenceAtOffset(text: String, offset: Int): String? =
        envVarReferenceSpanAtOffset(text, offset)?.envVar

    /**
     * Finds all env var references within a Spring YAML key-value pair's value element.
     */
    fun springEnvReferencesInValue(keyValue: YAMLKeyValue): List<EnvVarReferenceSpan> {
        val value = keyValue.value ?: return emptyList()
        val range = value.textRange ?: return emptyList()
        return envVarReferenceSpansInRange(
            keyValue.containingFile?.text ?: return emptyList(),
            range.startOffset,
            range.endOffset,
        )
    }

    /**
     * Finds an env var reference at the given offset, returning its offset relative to the element.
     */
    fun springEnvReferenceAtOffset(fileElement: PsiElement, offset: Int): EnvVarRefAtOffset? {
        val span = envVarReferenceSpanAtOffset(fileElement.containingFile?.text ?: return null, offset) ?: return null
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

    fun springEnvVarAtOffset(text: String, offset: Int): String? {
        val keyEnvVar = springKeyAtOffset(text, offset)?.let(::springKeyToEnvVarName)
        return keyEnvVar ?: envVarReferenceAtOffset(text, offset)
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

        val localStart = raw.indexOf(unquoted)
        if (localStart < 0) return null

        val start = valueElement.textRange.startOffset + localStart
        return EnvVarReferenceSpan(unquoted, start, start + unquoted.length)
    }

    // ─── PSI navigation helpers ───────────────────────────────────────────────

    private fun findEnclosingKeyValue(element: PsiElement): YAMLKeyValue? {
        var current: PsiElement? = element
        while (current != null) {
            if (current is YAMLKeyValue) return current
            current = current.parent
        }
        return null
    }

    /**
     * Returns the env-var name most relevant to the given element's position.
     *
     * For Spring values: if the element sits inside a `${ENV_VAR}` expression,
     * that env var is returned. Otherwise, the key-path env var is returned.
     *
     * For Helm templates: the `name:` value under containers/env is returned.
     */
    fun envVarForElement(element: PsiElement): String? {
        val file = element.containingFile ?: return null
        val virtualFile = file.virtualFile ?: return null
        if (!isYamlFile(virtualFile)) return null

        val keyValue = findEnclosingKeyValue(element) ?: return null

        return when {
            isSpringApplicationFile(virtualFile) -> {
                val valueElement = keyValue.value
                val refs = springEnvReferencesInValue(keyValue)
                // If the cursor is inside the value part, look for ${ENV_VAR} references first.
                if (valueElement != null &&
                    element.textRange.startOffset >= valueElement.textRange.startOffset
                ) {
                    if (refs.isNotEmpty()) return refs.first().envVar
                }
                // Derive env var from the key path at the key element's offset.
                keyValue.key?.let { springKeyAtOffset(file.text, it.textOffset) }
                    ?.let(::springKeyToEnvVarName)
                    ?: refs.firstOrNull()?.envVar
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
        val element = psiFile.findElementAt(offset) ?: return null
        var current: PsiElement? = element
        while (current != null) {
            if (current is YAMLKeyValue && current.keyText == "name") {
                return extractHelmEnvVarValue(current)
            }
            current = current.parent
        }
        return null
    }


    // ─── Extraction helpers for Helm env var values ────────────────────────────

    /**
     * Extracts the env var name from a Helm `name:` key-value pair.
     * Performs validation that the element is under the proper env path.
     */
    fun extractHelmEnvVarValue(nameKeyValue: YAMLKeyValue): String? {
        val value = nameKeyValue.value?.text?.trim('"', '\'') ?: return null
        if (value.isBlank()) return null
        if (!isUnderEnvPath(nameKeyValue)) return null
        return value
    }

    /**
     * Checks if the given element is under the Helm structures: containers > env > name.
     * This ensures we only match actual env var definitions, not unrelated `name:` keys.
     */
    fun isUnderEnvPath(element: PsiElement): Boolean {
        var current: PsiElement? = element.parent
        var foundEnv = false
        var foundContainers = false

        while (current != null) {
            when (current) {
                is YAMLKeyValue -> when (current.keyText) {
                    "env" -> foundEnv = true
                    "containers" -> foundContainers = true
                }
                is YAMLSequence -> {
                    val seqParent = current.parent
                    if (seqParent is YAMLKeyValue) {
                        when (seqParent.keyText) {
                            "env" -> foundEnv = true
                            "containers" -> foundContainers = true
                        }
                    }
                }
            }
            current = current.parent
        }
        return foundEnv && foundContainers
    }
}
