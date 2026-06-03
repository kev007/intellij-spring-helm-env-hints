package com.github.kev007.intellijspringhelmenvhints.navigation

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLSequence
import org.jetbrains.yaml.psi.YAMLSequenceItem
import org.jetbrains.yaml.psi.YAMLValue

object EnvVarMappingSupport {

    private val springKeyLineRegex = Regex("""^(\s*)([A-Za-z0-9_.-]+)\s*:\s*(.*)$""")
    private val envVarRefRegex = Regex("""\$\{([A-Z][A-Z0-9_]*)(?::([^}]*))?\}""")

    data class EnvVarUsage(
        val element: PsiElement,
        val relativePath: String,
        val line: Int,
        val value: String?,
    )

    data class EnvVarReferenceMatch(
        val envVar: String,
        val defaultValue: String?,
        val startOffset: Int,
        val endOffset: Int,
    )

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

    fun springKeyToEnvVarName(key: String): String {
        val normalized = key
            .replace("[", "_")
            .replace("]", "")
            .replace(Regex("[^A-Za-z0-9]"), "_")
            .replace(Regex("_+"), "_")
            .trim('_')

        return normalized.uppercase()
    }

    fun springKeyAtOffset(text: String, offset: Int): String? {
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
                val keyEnd = keyStart + key.length
                if (offset in keyStart..keyEnd) {
                    return stack.joinToString(".") { it.key }
                }
            }

            runningOffset += line.length + 1
        }

        return null
    }

    fun envVarAtOffset(psiFile: PsiElement, offset: Int): String? {
        return envVarFromPsiAtOffset(psiFile, offset)
    }

    fun envVarReferenceAtOffset(text: String, offset: Int): String? {
        return envVarReferenceMatchAtOffset(text, offset)?.envVar
    }

    fun springEnvVarAtOffset(text: String, offset: Int): String? {
        val keyEnvVar = springKeyAtOffset(text, offset)?.let(::springKeyToEnvVarName)
        return keyEnvVar ?: envVarReferenceAtOffset(text, offset)
    }

    fun resolveMappedTargets(fileElement: PsiElement, virtualFile: VirtualFile, offset: Int): List<PsiElement> {
        return when {
            isSpringApplicationFile(virtualFile) -> {
                val envVar = springEnvVarAtOffset(fileElement.text, offset) ?: return emptyList()
                findHelmEnvTargets(fileElement.project, envVar)
            }

            isHelmTemplateFile(virtualFile) -> {
                val envVar = envVarAtOffset(fileElement, offset) ?: return emptyList()
                findSpringTargets(fileElement.project, envVar)
            }

            else -> emptyList()
        }
    }

    fun envVarReferenceMatchAtOffset(text: String, offset: Int): EnvVarReferenceMatch? {
        var currentOffset = 0
        val lines = text.split('\n')

        for (line in lines) {
            val lineEnd = currentOffset + line.length
            if (offset < currentOffset || offset > lineEnd) {
                currentOffset = lineEnd + 1
                continue
            }

            // Check if there's an env var reference in this line at the given offset
            val matches = envVarRefRegex.findAll(line)
            for (match in matches) {
                val matchStart = currentOffset + match.range.first
                val matchEnd = currentOffset + match.range.last + 1
                if (offset in matchStart..matchEnd) {
                    val defaultValue = match.groups[2]?.value?.takeIf { it.isNotBlank() }
                    return EnvVarReferenceMatch(match.groupValues[1], defaultValue, matchStart, matchEnd)
                }
            }

            currentOffset = lineEnd + 1
        }

        return null
    }

    private fun envVarFromPsiAtOffset(psiFile: PsiElement, offset: Int): String? {
        val element = psiFile.findElementAt(offset) ?: return null

        // Walk up the tree to find if we're in a "name" key value under spec.containers[*].env[*]
        var current: PsiElement? = element
        while (current != null) {
            if (current is YAMLKeyValue && current.keyText == "name") {
                return extractEnvVarValue(current)
            }
            current = current.parent
        }

        return null
    }

    private fun extractEnvVarValue(nameKeyValue: YAMLKeyValue): String? {
        val value = nameKeyValue.value?.text?.trim('"', '\'') ?: return null

        // Validate it looks like an env var (uppercase letters, numbers, underscores)
        if (!value.matches(Regex("[A-Z][A-Z0-9_]*"))) return null

        // Verify we're under spec.containers[*].env[*]
        if (!isUnderEnvPath(nameKeyValue)) return null

        return value
    }

    private fun isUnderEnvPath(element: PsiElement): Boolean {
        var current: PsiElement? = element.parent
        var foundEnv = false
        var foundContainers = false

        while (current != null) {
            when (current) {
                is YAMLKeyValue -> {
                    when (current.keyText) {
                        "env" -> foundEnv = true
                        "containers" -> foundContainers = true
                    }
                }
                is YAMLSequence -> {
                    // Sequence under a key - check parent key
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

        // Must be under both env and containers to be valid
        return foundEnv && foundContainers
    }

    fun findHelmEnvTargets(project: Project, envVar: String): List<PsiElement> {
        return findHelmUsages(project, envVar).map { it.element }
    }

    fun findHelmUsages(project: Project, envVar: String): List<EnvVarUsage> {
        val psiManager = PsiManager.getInstance(project)
        val targets = mutableListOf<EnvVarUsage>()

        for (file in yamlFiles(project)) {
            if (!isHelmTemplateFile(file)) continue
            val psiFile = psiManager.findFile(file) as? YAMLFile ?: continue

            val envNameEntries = collectHelmEnvNameEntries(psiFile)
            for (nameKey in envNameEntries) {
                val name = extractEnvVarValue(nameKey) ?: continue
                if (name != envVar) continue
                val valueElement = nameKey.value ?: continue
                targets += EnvVarUsage(
                    element = valueElement,
                    relativePath = relativePath(project, file),
                    line = lineNumber(psiFile.text, valueElement.textOffset),
                    value = findSiblingYamlValue(nameKey, "value"),
                )
            }
        }

        return targets
    }

    fun findSpringTargets(project: Project, envVar: String): List<PsiElement> {
        return findSpringUsages(project, envVar).map { it.element }
    }

    fun findSpringUsages(project: Project, envVar: String): List<EnvVarUsage> {
        val psiManager = PsiManager.getInstance(project)
        val targets = mutableListOf<EnvVarUsage>()

        for (file in yamlFiles(project)) {
            if (!isSpringApplicationFile(file)) continue
            val psiFile = psiManager.findFile(file) ?: continue
            val text = psiFile.text

            // Search for property keys that map to this env var
            val keyOccurrences = springKeyOccurrences(text)
            for (occurrence in keyOccurrences) {
                if (springKeyToEnvVarName(occurrence.fullKey) == envVar) {
                    psiFile.findElementAt(occurrence.keyOffset)?.let { keyElement ->
                        targets += EnvVarUsage(
                            element = keyElement,
                            relativePath = relativePath(project, file),
                            line = lineNumber(text, occurrence.keyOffset),
                            value = null,
                        )
                    }
                }
            }

            // Also search for env var references in property values (e.g., ${POSTGRES_HOST:default})
            val valueReferences = findEnvVarReferencesInValues(psiFile, file, envVar)
            targets += valueReferences
        }

        return targets
    }

    private fun findEnvVarReferencesInValues(psiFile: PsiElement, file: VirtualFile, envVar: String): List<EnvVarUsage> {
        val targets = mutableListOf<EnvVarUsage>()
        val text = psiFile.text
        envVarRefRegex.findAll(text).forEach { match ->
            if (match.groupValues[1] != envVar) return@forEach
            val varStartOffset = match.range.first + 2 // skip "${"
            psiFile.findElementAt(varStartOffset)?.let { element ->
                val defaultValue = match.groups[2]?.value?.takeIf { it.isNotBlank() }
                targets += EnvVarUsage(element, relativePath(psiFile.project, file), lineNumber(text, varStartOffset), defaultValue)
            }
        }

        return targets
    }

    private fun collectHelmEnvNameEntries(element: PsiElement): List<YAMLKeyValue> {
        val results = mutableListOf<YAMLKeyValue>()
        fun walk(node: PsiElement) {
            if (node is YAMLKeyValue && node.keyText == "name" && isUnderEnvPath(node)) {
                results += node
            }
            node.children.forEach(::walk)
        }
        walk(element)
        return results
    }

    private fun findSiblingYamlValue(nameKey: YAMLKeyValue, siblingKey: String): String? {
        val mapping = nameKey.parent as? YAMLMapping ?: return null
        return mapping.keyValues
            .firstOrNull { it.keyText == siblingKey }
            ?.value
            ?.text
            ?.trim()
            ?.trim('"', '\'')
            ?.takeIf { it.isNotEmpty() }
    }

    private fun lineNumber(text: String, offset: Int): Int {
        val safeOffset = offset.coerceIn(0, text.length)
        return text.substring(0, safeOffset).count { it == '\n' } + 1
    }

    private fun relativePath(project: Project, file: VirtualFile): String {
        val path = file.path.replace('\\', '/')
        val roots = ProjectRootManager.getInstance(project).contentRootsFromAllModules
        for (root in roots) {
            val rootPath = root.path.replace('\\', '/')
            if (path.startsWith("$rootPath/")) {
                return path.removePrefix("$rootPath/")
            }
        }
        return file.name
    }

    private fun yamlFiles(project: Project): List<VirtualFile> {
        val files = mutableListOf<VirtualFile>()
        val roots = ProjectRootManager.getInstance(project).contentRootsFromAllModules
        roots.forEach { root ->
            VfsUtilCore.iterateChildrenRecursively(root, null) { file ->
                if (!file.isDirectory && isYamlFile(file)) {
                    files += file
                }
                true
            }
        }
        return files
    }

    private fun springKeyOccurrences(text: String): List<SpringKeyOccurrence> {
        val lines = text.split('\n')
        val stack = mutableListOf<IndentKey>()
        val result = mutableListOf<SpringKeyOccurrence>()
        var runningOffset = 0

        for (line in lines) {
            val match = springKeyLineRegex.find(line)
            if (match != null) {
                val indent = match.groupValues[1].length
                val key = match.groupValues[2]
                val keyOffsetInLine = match.range.first + indent

                while (stack.isNotEmpty() && indent <= stack.last().indent) {
                    stack.removeAt(stack.lastIndex)
                }
                stack += IndentKey(indent, key)

                result += SpringKeyOccurrence(
                    fullKey = stack.joinToString(".") { it.key },
                    keyOffset = runningOffset + keyOffsetInLine,
                )
            }

            runningOffset += line.length + 1
        }

        return result
    }

    private data class IndentKey(val indent: Int, val key: String)

    private data class SpringKeyOccurrence(
        val fullKey: String,
        val keyOffset: Int,
    )
}

