package com.github.kev007.intellijspringhelmenvhints.navigation

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLSequence

object EnvVarMappingSupport {

    private val springKeyLineRegex = Regex("""^(\s*)([A-Za-z0-9_.-]+)\s*:\s*(.*)$""")
    private val envVarRefRegex = Regex("""\$\{([^}:\s]+)(?::([^}]*))?}""")

    data class MappingQuery(
        val envVar: String,
        val targetResolver: (Project, String) -> List<PsiElement>,
    )

    data class EnvVarReferenceSpan(
        val envVar: String,
        val startOffset: Int,
        val endOffset: Int,
    )

    enum class MappingStatus {
        MATCHED,
        UNMATCHED,
    }

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

    fun envVarAtOffset(psiFile: PsiElement, offset: Int): String? = envVarFromPsiAtOffset(psiFile, offset)

    fun envVarReferenceAtOffset(text: String, offset: Int): String? {
        return envVarReferenceSpanAtOffset(text, offset)?.envVar
    }

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

    fun envVarReferenceSpanAtOffset(text: String, offset: Int): EnvVarReferenceSpan? {
        return envVarReferenceSpansInRange(text, offset, offset + 1).firstOrNull()
    }

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

    fun springEnvVarAtOffset(text: String, offset: Int): String? {
        val keyEnvVar = springKeyAtOffset(text, offset)?.let(::springKeyToEnvVarName)
        return keyEnvVar ?: envVarReferenceAtOffset(text, offset)
    }

    fun mappingQueryAtOffset(fileElement: PsiElement, virtualFile: VirtualFile, offset: Int): MappingQuery? {
        return when {
            isSpringApplicationFile(virtualFile) -> {
                val envVar = springEnvVarAtOffset(fileElement.text, offset) ?: return null
                MappingQuery(envVar, ::findHelmEnvTargets)
            }

            isHelmTemplateFile(virtualFile) -> {
                val envVar = envVarAtOffset(fileElement, offset) ?: return null
                MappingQuery(envVar, ::findSpringTargets)
            }

            else -> null
        }
    }

    fun resolveMappedTargets(fileElement: PsiElement, virtualFile: VirtualFile, offset: Int): List<PsiElement> {
        val query = mappingQueryAtOffset(fileElement, virtualFile, offset) ?: return emptyList()
        return query.targetResolver(fileElement.project, query.envVar)
    }

    fun mappingStatusForSpringVar(project: Project, envVar: String): MappingStatus {
        return if (findHelmEnvTargets(project, envVar).isEmpty()) MappingStatus.UNMATCHED else MappingStatus.MATCHED
    }

    fun mappingStatusForHelmVar(project: Project, envVar: String): MappingStatus {
        return if (findSpringTargets(project, envVar).isEmpty()) MappingStatus.UNMATCHED else MappingStatus.MATCHED
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
        if (value.isBlank()) return null

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
        val psiManager = PsiManager.getInstance(project)
        val targets = mutableListOf<PsiElement>()

        for (file in yamlFiles(project)) {
            if (!isHelmTemplateFile(file)) continue
            val psiFile = psiManager.findFile(file) as? YAMLFile ?: continue

            val envNameEntries = collectHelmEnvNameEntries(psiFile)
            for (nameKey in envNameEntries) {
                val name = extractEnvVarValue(nameKey) ?: continue
                if (name != envVar) continue
                nameKey.value?.let(targets::add)
            }
        }

        return targets
    }

    fun findSpringTargets(project: Project, envVar: String): List<PsiElement> {
        val psiManager = PsiManager.getInstance(project)
        val targets = mutableListOf<PsiElement>()

        for (file in yamlFiles(project)) {
            if (!isSpringApplicationFile(file)) continue
            val psiFile = psiManager.findFile(file) ?: continue
            val text = psiFile.text

            val keyOccurrences = springKeyOccurrences(text)
            for (occurrence in keyOccurrences) {
                if (springKeyToEnvVarName(occurrence.fullKey) == envVar) {
                    psiFile.findElementAt(occurrence.keyOffset)?.let(targets::add)
                }
            }

            targets += findEnvVarReferencesInValues(psiFile, envVar)
        }

        return targets
    }

    private fun findEnvVarReferencesInValues(psiFile: PsiElement, envVar: String): List<PsiElement> {
        val targets = mutableListOf<PsiElement>()
        val text = psiFile.text
        envVarRefRegex.findAll(text).forEach { match ->
            if (match.groupValues[1] != envVar) return@forEach
            val varStartOffset = match.range.first + 2 // skip "${"
            psiFile.findElementAt(varStartOffset)?.let(targets::add)
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

