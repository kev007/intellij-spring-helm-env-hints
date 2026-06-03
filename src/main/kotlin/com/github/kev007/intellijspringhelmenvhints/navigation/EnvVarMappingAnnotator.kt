package com.github.kev007.intellijspringhelmenvhints.navigation

import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.ui.JBColor
import org.jetbrains.yaml.psi.YAMLKeyValue

class EnvVarMappingAnnotator : Annotator {

    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        val file = element.containingFile ?: return
        val virtualFile = file.virtualFile ?: return
        if (!EnvVarMappingSupport.isYamlFile(virtualFile)) return

        if (EnvVarMappingSupport.isHelmTemplateFile(virtualFile)) {
            annotateHelmEnvName(element, holder)
            return
        }

        if (EnvVarMappingSupport.isSpringApplicationFile(virtualFile)) {
            annotateSpringEnvReference(element, holder)
        }
    }

    private fun annotateHelmEnvName(element: PsiElement, holder: AnnotationHolder) {
        val keyValue = PsiTreeUtil.getParentOfType(element, YAMLKeyValue::class.java, false) ?: return
        if (keyValue.keyText != "name" || keyValue.value != element) return

        val envVar = EnvVarMappingSupport.envVarAtOffset(element.containingFile, element.textOffset) ?: return
        val springMatches = EnvVarMappingSupport.findSpringUsages(element.project, envVar)

        if (springMatches.isEmpty()) {
            holder.newAnnotation(
                HighlightSeverity.ERROR,
                "No exact Spring match for Helm env var '$envVar'",
            )
                .range(element)
                .enforcedTextAttributes(ERROR_UNDERLINE)
                .tooltip(buildHelmMissingTooltip(envVar))
                .create()
            return
        }

        holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
            .range(element)
            .enforcedTextAttributes(MATCHED_REFERENCE)
            .tooltip(buildHelmMatchTooltip(envVar, springMatches))
            .create()
    }

    private fun annotateSpringEnvReference(element: PsiElement, holder: AnnotationHolder) {
        val keyValue = PsiTreeUtil.getParentOfType(element, YAMLKeyValue::class.java, false) ?: return
        if (keyValue.value != element) return

        val match = EnvVarMappingSupport.envVarReferenceMatchAtOffset(element.containingFile.text, element.textOffset) ?: return
        val helmMatches = EnvVarMappingSupport.findHelmUsages(element.project, match.envVar)
        if (helmMatches.isEmpty()) {
            holder.newAnnotation(
                HighlightSeverity.ERROR,
                "No exact Helm template match for application env reference '${match.envVar}'",
            )
                .range(element)
                .enforcedTextAttributes(ERROR_UNDERLINE)
                .tooltip(buildSpringMissingTooltip(match.envVar, match.defaultValue))
                .create()
            return
        }

        holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
            .range(element)
            .enforcedTextAttributes(MATCHED_REFERENCE)
            .tooltip(buildSpringMatchTooltip(match.envVar, match.defaultValue, helmMatches))
            .create()
    }

    private fun buildSpringMissingTooltip(envVar: String, defaultValue: String?): String {
        val defaultHint = defaultValue?.let {
            "Default in application.yaml: <code>${StringUtil.escapeXmlEntities(it)}</code><br/>"
        } ?: ""

        return "<html><b>${StringUtil.escapeXmlEntities(envVar)}</b><br/>" +
            defaultHint +
            "No exact match found in Helm template <code>spec.containers[*].env[*].name</code>.</html>"
    }

    private fun buildHelmMissingTooltip(envVar: String): String {
        return "<html><b>$envVar</b><br/>No exact match found in <code>application*.yaml</code>.</html>"
    }

    private fun buildHelmMatchTooltip(envVar: String, springMatches: List<EnvVarMappingSupport.EnvVarUsage>): String {
        val items = springMatches.take(8).joinToString("<br/>") { usage ->
            val valueHint = usage.value?.let { " (default: ${StringUtil.escapeXmlEntities(it)})" } ?: ""
            "- ${StringUtil.escapeXmlEntities(usage.relativePath)}:${usage.line}$valueHint"
        }

        return "<html><b>${StringUtil.escapeXmlEntities(envVar)}</b><br/>" +
            "Spring matches:<br/>$items</html>"
    }

    private fun buildSpringMatchTooltip(
        envVar: String,
        defaultValue: String?,
        helmMatches: List<EnvVarMappingSupport.EnvVarUsage>,
    ): String {
        val defaultHint = defaultValue?.let { "Default in application.yaml: <code>${StringUtil.escapeXmlEntities(it)}</code><br/>" } ?: ""
        val items = helmMatches.take(8).joinToString("<br/>") { usage ->
            val valueHint = usage.value?.let { " (value: ${StringUtil.escapeXmlEntities(it)})" } ?: ""
            "- ${StringUtil.escapeXmlEntities(usage.relativePath)}:${usage.line}$valueHint"
        }

        return "<html><b>${StringUtil.escapeXmlEntities(envVar)}</b><br/>" +
            defaultHint +
            "Helm matches:<br/>$items</html>"
    }

    companion object {
        private val MATCHED_REFERENCE = TextAttributes(
            JBColor(0x0B57D0, 0x7FB2FF),
            null,
            null,
            EffectType.LINE_UNDERSCORE,
            0,
        )

        private val ERROR_UNDERLINE = TextAttributes(
            JBColor(0xD93025, 0xFF8A80),
            null,
            null,
            EffectType.WAVE_UNDERSCORE,
            0,
        )
    }
}


