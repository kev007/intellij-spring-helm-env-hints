package com.github.kev007.intellijspringhelmenvhints.models

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement

/**
 * Represents the mapping status of an environment variable.
 * MATCHED: the env var has corresponding definitions in the other file system (Spring↔Helm)
 * UNMATCHED: the env var exists in one system but not the other
 */
enum class MappingStatus {
    MATCHED,
    UNMATCHED,
}

/**
 * Represents a span of text that contains an env var reference, typically inside ${ENV_VAR} syntax.
 * Used for highlighting and range calculations.
 */
data class EnvVarReferenceSpan(
    val envVar: String,
    val startOffset: Int,
    val endOffset: Int,
)

/**
 * Represents an env var reference found at a specific element position within a range.
 * Used for calculating relative offsets within PSI elements for reference handling.
 */
data class EnvVarRefAtOffset(
    val envVar: String,
    val rangeInElement: TextRange,
)

/**
 * Query descriptor for resolving an env var to its mapped targets in another file system.
 * Encapsulates the env var name and the target resolver function (Spring→Helm or Helm→Spring).
 */
data class MappingQuery(
    val envVar: String,
    val targetResolver: (Project, String) -> List<PsiElement>,
)

/**
 * A single environment variable entry tracked by the registry service.
 * Contains references to all PSI elements across Spring application YAMLs and Helm templates
 * that define or reference this env var.
 */
data class EnvVarEntry(
    val name: String,
    /** PSI value elements for Helm `name: ENV_VAR` under containers/env paths. */
    val helmValues: List<PsiElement>,
    /** PSI key elements for Spring property keys that normalize to this env var. */
    val springKeys: List<PsiElement>,
    /** PSI elements inside Spring `${ENV_VAR[:default]}` value references. */
    val springValueRefs: List<PsiElement>,
) {
    val helmCount: Int get() = helmValues.size
    val springCount: Int get() = springKeys.size + springValueRefs.size
    val totalCount: Int get() = helmValues.size + springKeys.size + springValueRefs.size

    /** All PSI elements that represent a *definition* of this env var. */
    fun definitions(): List<PsiElement> = helmValues + springKeys

    /** All PSI elements that represent a *usage/reference* to this env var. */
    fun usages(): List<PsiElement> = springValueRefs + helmValues

    /** All tracked PSI elements across both file types. */
    fun allElements(): List<PsiElement> = helmValues + springKeys + springValueRefs
}

/**
 * Autocomplete suggestion for an env var.
 * Used by the completion contributor to suggest env vars in order of popularity.
 */
data class EnvVarSuggestion(
    val envVar: String,
    val usageCount: Int,
)

// Internal helpers for Spring YAML parsing
internal data class IndentKey(val indent: Int, val key: String)

internal data class SpringKeyOccurrence(
    val fullKey: String,
    val keyOffset: Int,
)

