package com.github.kev007.intellijspringhelmenvhints.navigation

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.module.ModuleUtil
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.ui.JBColor
import org.jetbrains.yaml.psi.YAMLKeyValue
import java.awt.Font

private val blue = JBColor(0x0B57D0, 0x7FB2FF)
private val red  = JBColor(0xD93025, 0xFF8A80)

/** Solid blue for matched Helm env names. */
private val MATCHED_HELM   = TextAttributes(blue, null, null, null, Font.PLAIN)

/** Blue underline for matched Spring ${ENV_VAR} references. */
private val MATCHED_SPRING = TextAttributes(blue, null, blue, EffectType.LINE_UNDERSCORE, Font.PLAIN)

/** Red for unmatched env vars in both file types. */
private val UNMATCHED      = TextAttributes(red, null, null, null, Font.PLAIN)

class EnvVarAnnotator : Annotator {

    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        val vf = element.containingFile?.virtualFile ?: return
        // Only annotate the direct value element of a key-value pair
        val kv = PsiTreeUtil.getParentOfType(element, YAMLKeyValue::class.java, false) ?: return
        if (kv.value != element) return

        val module = ModuleUtil.findModuleForPsiElement(element) ?: return

        when {
            vf.isSpringApp() -> {
                val range = element.textRange ?: return
                envSpansInRange(element.containingFile.text, range.startOffset, range.endOffset).forEach { span ->
                    val attrs = if (isSpringMatched(span.envVar, element.project, module)) MATCHED_SPRING else UNMATCHED
                    holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
                        .range(TextRange(span.startOffset, span.endOffset))
                        .enforcedTextAttributes(attrs)
                        .create()
                }
            }
            vf.isHelmTemplate() -> {
                val span = helmEnvNameSpan(element) ?: return
                val attrs = if (isHelmMatched(span.envVar, element.project, module)) MATCHED_HELM else UNMATCHED
                holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
                    .range(TextRange(span.startOffset, span.endOffset))
                    .enforcedTextAttributes(attrs)
                    .create()
            }
        }
    }
}

