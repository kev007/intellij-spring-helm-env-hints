package com.github.kev007.intellijspringhelmenvhints.navigation

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager

object EnvVarMappingSupport {

    private val helmEnvNameRegex = Regex("""(?m)^\s*-?\s*name\s*:\s*[\"']?([A-Z][A-Z0-9_]*)[\"']?\s*$""")
    private val springKeyLineRegex = Regex("""^(\s*)([A-Za-z0-9_.-]+)\s*:\s*(.*)$""")

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

    fun envVarAtOffset(text: String, offset: Int): String? {
        helmEnvNameRegex.findAll(text).forEach { match ->
            val range = match.groups[1]?.range ?: return@forEach
            if (offset in range.first..range.last + 1) {
                return match.groupValues[1]
            }
        }
        return null
    }

    fun findHelmEnvTargets(project: Project, envVar: String): List<PsiElement> {
        val psiManager = PsiManager.getInstance(project)
        val targets = mutableListOf<PsiElement>()

        for (file in yamlFiles(project)) {
            if (!isHelmTemplateFile(file)) continue
            val psiFile = psiManager.findFile(file) ?: continue
            helmEnvNameRegex.findAll(psiFile.text).forEach { match ->
                if (match.groupValues[1] == envVar) {
                    val offset = match.groups[1]?.range?.first ?: match.range.first
                    psiFile.findElementAt(offset)?.let { targets += it }
                }
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
            val occurrences = springKeyOccurrences(psiFile.text)
            for (occurrence in occurrences) {
                if (springKeyToEnvVarName(occurrence.fullKey) == envVar) {
                    psiFile.findElementAt(occurrence.keyOffset)?.let { targets += it }
                }
            }
        }

        return targets
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

