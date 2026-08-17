package com.github.kev007.intellijspringhelmenvhints.navigation

import com.github.kev007.intellijspringhelmenvhints.settings.HelmEnvHintsSettings
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.module.ModuleUtil
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.ui.JBColor
import org.jetbrains.yaml.psi.YAMLScalar
import java.awt.Color
import java.awt.Font

/** Builds a theme-aware colour from the packed light/dark ARGB pair stored in the settings. */
private fun jbColor(lightArgb: Int, darkArgb: Int): JBColor =
    JBColor(Color(lightArgb, true), Color(darkArgb, true))

/**
 * Renders the highlight for one env var occurrence straight from [HelmEnvHintsSettings], so
 * settings changes take effect on the next daemon pass without a colour-scheme round trip.
 *
 * @param matched   whether the env var exists on both the Spring and the Helm side
 * @param underline whether to add the "this is a reference" underline (Spring side only)
 */
private fun envAttributes(matched: Boolean, underline: Boolean): TextAttributes {
    val s = HelmEnvHintsSettings.instance.state
    val effectColor = if (underline) jbColor(s.springUnderlineLightArgb, s.springUnderlineDarkArgb) else null
    val effectType = if (underline) EffectType.LINE_UNDERSCORE else null
    return if (s.useTextColor) {
        val fg = if (matched) jbColor(s.matchedFgLightArgb, s.matchedFgDarkArgb)
                 else jbColor(s.unmatchedFgLightArgb, s.unmatchedFgDarkArgb)
        TextAttributes(fg, null, effectColor, effectType, Font.BOLD)
    } else {
        val bg = if (matched) jbColor(s.matchedBgLightArgb, s.matchedBgDarkArgb)
                 else jbColor(s.unmatchedBgLightArgb, s.unmatchedBgDarkArgb)
        TextAttributes(null, bg, effectColor, effectType, Font.PLAIN)
    }
}

/**
 * Colours env var occurrences in Spring `application*.yaml` values (`${ENV_VAR}`) and in Helm
 * `spec.containers[*].env[*].name` entries, using different colours depending on whether the
 * var is matched on the opposite side. No tooltips are added.
 */
class EnvVarAnnotator : Annotator {

    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        val vf = element.containingFile?.virtualFile ?: return
        // Only scalars carry env var occurrences. Restricting to them (instead of "the value
        // of a key/value pair") avoids annotating the same reference once per enclosing
        // YAMLMapping — a mapping is a value too, and its text spans its whole subtree.
        if (element !is YAMLScalar) return

        val module = ModuleUtil.findModuleForPsiElement(element) ?: return

        // Spring occurrences are navigable references and get an underline; Helm names do not.
        val underline: Boolean
        val spans: List<EnvSpan> = when {
            vf.isSpringApp() -> { underline = true; springRefSpans(element) }
            vf.isHelmTemplate() -> { underline = false; listOfNotNull(helmEnvNameSpan(element)) }
            else -> return
        }

        for (span in spans) {
            holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
                .range(TextRange(span.startOffset, span.endOffset))
                .enforcedTextAttributes(
                    envAttributes(isEnvMatched(span.envVar, element.project, module), underline)
                )
                .create()
        }
    }
}
