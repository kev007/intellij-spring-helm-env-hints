package com.github.kev007.intellijspringhelmenvhints.navigation

import com.github.kev007.intellijspringhelmenvhints.core.EnvVarMappingCore
import com.github.kev007.intellijspringhelmenvhints.models.MappingStatus
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.module.ModuleUtil
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.ui.JBColor
import org.jetbrains.yaml.psi.YAMLKeyValue
import java.awt.Font

/** Color for env vars that have corresponding definitions in both Spring and Helm. */
private val MATCHED_REFERENCE_COLOR = TextAttributes(JBColor(0x0B57D0, 0x7FB2FF), null, null, null, Font.PLAIN)

/** Color for env vars that exist in only one file system. */
private val UNMATCHED_REFERENCE_COLOR = TextAttributes(JBColor(0xD93025, 0xFF8A80), null, null, null, Font.PLAIN)

class EnvVarMappingAnnotator : Annotator {

    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        val file = element.containingFile ?: return
        val virtualFile = file.virtualFile ?: return

        val keyValue = PsiTreeUtil.getParentOfType(element, YAMLKeyValue::class.java, false) ?: return
        if (keyValue.value != element) return

        when {
            EnvVarMappingCore.isSpringApplicationFile(virtualFile) -> annotateSpringValue(element, holder)
            EnvVarMappingCore.isHelmTemplateFile(virtualFile) -> annotateHelmValue(element, holder)
        }
    }

    private fun annotateSpringValue(element: PsiElement, holder: AnnotationHolder) {
        val valueRange = element.textRange ?: return
        val module = ModuleUtil.findModuleForPsiElement(element)
        EnvVarMappingCore.envVarReferenceSpansInRange(
            text = element.containingFile.text,
            startOffset = valueRange.startOffset,
            endOffset = valueRange.endOffset,
        ).forEach { span ->
            annotateRange(
                holder = holder,
                range = TextRange(span.startOffset, span.endOffset),
                status = EnvVarMappingSupport.mappingStatusForSpringVar(element.project, module, span.envVar),
            )
        }
    }

    private fun annotateHelmValue(element: PsiElement, holder: AnnotationHolder) {
        val span = EnvVarMappingCore.helmEnvNameSpan(element) ?: return
        val module = ModuleUtil.findModuleForPsiElement(element)
        annotateRange(
            holder = holder,
            range = TextRange(span.startOffset, span.endOffset),
            status = EnvVarMappingSupport.mappingStatusForHelmVar(element.project, module, span.envVar),
        )
    }

    private fun annotateRange(holder: AnnotationHolder, range: TextRange, status: MappingStatus) {
        holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
            .range(range)
            .enforcedTextAttributes(if (status == MappingStatus.MATCHED) MATCHED_REFERENCE_COLOR else UNMATCHED_REFERENCE_COLOR)
            .create()
    }
}
