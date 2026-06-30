package com.github.kev007.intellijspringhelmenvhints.navigation

import com.github.kev007.intellijspringhelmenvhints.settings.HelmEnvHintsSettings
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.module.ModuleUtil
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.ui.JBColor
import org.jetbrains.yaml.psi.YAMLKeyValue
import java.awt.Color
import java.awt.Font

/**
 * Registered [TextAttributesKey]s kept for colour-scheme integration.
 * Actual rendering uses [HelmEnvHintsSettings] via [enforcedTextAttributes],
 * so changes in the plugin settings take effect immediately.
 */
val HELM_ENV_HINTS_MATCHED_HELM: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
    "HELM_ENV_HINTS_MATCHED_HELM"
)
val HELM_ENV_HINTS_MATCHED_SPRING: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
    "HELM_ENV_HINTS_MATCHED_SPRING"
)
val HELM_ENV_HINTS_UNMATCHED: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
    "HELM_ENV_HINTS_UNMATCHED"
)

// ─── Settings-driven TextAttributes builders ──────────────────────────────────

private fun matchedBgColor(): Color {
    val s = HelmEnvHintsSettings.instance.state
    return JBColor(Color(s.matchedBgLightArgb, true), Color(s.matchedBgDarkArgb, true))
}

private fun unmatchedBgColor(): Color {
    val s = HelmEnvHintsSettings.instance.state
    return JBColor(Color(s.unmatchedBgLightArgb, true), Color(s.unmatchedBgDarkArgb, true))
}

private fun springUnderlineColor(): Color {
    val s = HelmEnvHintsSettings.instance.state
    return JBColor(Color(s.springUnderlineLightArgb, true), Color(s.springUnderlineDarkArgb, true))
}

private fun matchedFgColor(): Color {
    val s = HelmEnvHintsSettings.instance.state
    return JBColor(Color(s.matchedFgLightArgb, true), Color(s.matchedFgDarkArgb, true))
}

private fun unmatchedFgColor(): Color {
    val s = HelmEnvHintsSettings.instance.state
    return JBColor(Color(s.unmatchedFgLightArgb, true), Color(s.unmatchedFgDarkArgb, true))
}

private fun matchedHelmAttributes(): TextAttributes {
    val s = HelmEnvHintsSettings.instance.state
    return if (s.useTextColor)
        TextAttributes(matchedFgColor(), null, null, null, Font.BOLD)
    else
        TextAttributes(null, matchedBgColor(), null, null, Font.PLAIN)
}

private fun matchedSpringAttributes(): TextAttributes {
    val s = HelmEnvHintsSettings.instance.state
    return if (s.useTextColor)
        TextAttributes(matchedFgColor(), null, springUnderlineColor(), EffectType.LINE_UNDERSCORE, Font.BOLD)
    else
        TextAttributes(null, matchedBgColor(), springUnderlineColor(), EffectType.LINE_UNDERSCORE, Font.PLAIN)
}

private fun unmatchedAttributes(): TextAttributes {
    val s = HelmEnvHintsSettings.instance.state
    return if (s.useTextColor)
        TextAttributes(unmatchedFgColor(), null, null, null, Font.BOLD)
    else
        TextAttributes(null, unmatchedBgColor(), null, null, Font.PLAIN)
}

// ─── Annotator ────────────────────────────────────────────────────────────────

class EnvVarAnnotator : Annotator {

    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        val vf = element.containingFile?.virtualFile ?: return
        val kv = PsiTreeUtil.getParentOfType(element, YAMLKeyValue::class.java, false) ?: return
        if (kv.value != element) return

        val module = ModuleUtil.findModuleForPsiElement(element) ?: return

        when {
            vf.isSpringApp() -> {
                val range = element.textRange ?: return
                envSpansInRange(element.containingFile.text, range.startOffset, range.endOffset).forEach { span ->
                    val attrs = if (isSpringMatched(span.envVar, element.project, module))
                        matchedSpringAttributes() else unmatchedAttributes()
                    holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
                        .range(TextRange(span.startOffset, span.endOffset))
                        .enforcedTextAttributes(attrs)
                        .create()
                }
            }
            vf.isHelmTemplate() -> {
                val span = helmEnvNameSpan(element) ?: return
                val attrs = if (isHelmMatched(span.envVar, element.project, module))
                    matchedHelmAttributes() else unmatchedAttributes()
                holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
                    .range(TextRange(span.startOffset, span.endOffset))
                    .enforcedTextAttributes(attrs)
                    .create()
            }
        }
    }
}
