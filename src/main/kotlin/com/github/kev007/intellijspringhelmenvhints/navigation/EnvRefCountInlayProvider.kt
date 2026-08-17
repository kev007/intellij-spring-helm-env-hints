package com.github.kev007.intellijspringhelmenvhints.navigation

import com.github.kev007.intellijspringhelmenvhints.settings.HelmEnvHintsSettings
import com.intellij.codeInsight.hints.declarative.HintFormat
import com.intellij.codeInsight.hints.declarative.InlayActionData
import com.intellij.codeInsight.hints.declarative.InlayActionHandler
import com.intellij.codeInsight.hints.declarative.InlayActionPayload
import com.intellij.codeInsight.hints.declarative.InlayHintsCollector
import com.intellij.codeInsight.hints.declarative.InlayHintsProvider
import com.intellij.codeInsight.hints.declarative.InlayTreeSink
import com.intellij.codeInsight.hints.declarative.InlineInlayPosition
import com.intellij.codeInsight.hints.declarative.OwnBypassCollector
import com.intellij.codeInsight.hints.declarative.StringInlayActionPayload
import com.intellij.codeInsight.navigation.PsiTargetNavigator
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

/** Id shared by the inlay provider and its click handler registration in plugin.xml. */
internal const val ENV_REF_COUNT_HANDLER_ID = "spring.helm.env.reference.count"

/** At most this many entries are listed in the tag tooltip. */
private const val MAX_TOOLTIP_ENTRIES = 10

/**
 * Renders an inline "N refs" tag right after a highlighted env var occurrence, stating how
 * many occurrences it resolves to on the opposite side:
 *
 * ```
 * # application.yaml
 * url: ${DATASOURCE_URL}[3 refs]      ← 3 Helm env entries declare DATASOURCE_URL
 *
 * # templates/deployment.yaml
 * - name: DATASOURCE_URL[1 ref]       ← 1 Spring occurrence uses it
 * ```
 *
 * Clicking the tag navigates to the lone target, or opens the list of targets.
 *
 * The collector drives the traversal itself ([OwnBypassCollector]) rather than reacting to
 * every element the platform walks
 * ([com.intellij.codeInsight.hints.declarative.SharedBypassCollector]). That is deliberate
 * and fixes two problems at once:
 *
 * 1. **No duplicate tags.** A nested `YAMLMapping` is itself the *value* of its parent key
 *    and its text contains every `${ENV_VAR}` of its subtree, so an element-driven
 *    collector emitted one identical tag per enclosing mapping level (`spring:` >
 *    `datasource:` > `url:` produced three).
 * 2. **Helm templates are covered.** When the Kubernetes / "Go Template" plugins own a Helm
 *    template, the file's base language is `HelmYAML` / `GoTemplate` and YAML is only the
 *    *template-data* language, living in a separate PSI root of the same view provider. The
 *    platform walks the base-language root only, so no YAML element was ever visited and no
 *    tag could ever appear. Resolving the YAML root explicitly (see [yamlRoot]) makes plain
 *    and templated Helm YAML behave identically.
 */
class EnvRefCountInlayProvider : InlayHintsProvider {

    override fun createCollector(file: PsiFile, editor: Editor): InlayHintsCollector? {
        if (!HelmEnvHintsSettings.instance.state.showReferenceCountTag) return null
        val vf = file.virtualFile ?: return null
        // Same precedence as the annotator: a file that qualifies as both is treated as Spring.
        val fromSpring = when {
            vf.isSpringApp() -> true
            vf.isHelmTemplate() -> false
            else -> return null
        }
        // Nothing to walk when the file carries no YAML root at all (e.g. a Helm *.tpl file).
        if (file.yamlRoot() == null) return null
        return EnvRefCountCollector(fromSpring)
    }
}

private class EnvRefCountCollector(private val fromSpring: Boolean) : OwnBypassCollector {

    override fun collectHintsForFile(file: PsiFile, sink: InlayTreeSink) {
        val vf = file.virtualFile ?: return
        val yaml = file.yamlRoot() ?: return
        val module = ModuleUtilCore.findModuleForFile(vf, file.project) ?: return

        for (span in envSpansInFile(yaml, fromSpring)) {
            val refs = counterpartRefs(span.envVar, file.project, module, vf, fromSpring)
            if (refs.size == 1) continue
            sink.addPresentation(
                position = InlineInlayPosition(span.tagOffset, relatedToPrevious = true),
                tooltip = tooltip(span.envVar, refs),
                hintFormat = HintFormat.default,
            ) {
                text(
                    if (refs.size == 1) "1 ref" else "${refs.size} refs",
                    InlayActionData(StringInlayActionPayload(span.envVar), ENV_REF_COUNT_HANDLER_ID),
                )
            }
        }
    }

    /** "DATASOURCE_URL → 3 Helm references" followed by the file:line of each one. */
    private fun tooltip(envVar: String, refs: List<PsiElement>): String {
        val side = if (fromSpring) "Helm" else "Spring"
        val noun = if (refs.size == 1) "reference" else "references"
        return buildString {
            append("$envVar → ${refs.size} $side $noun")
            refs.take(MAX_TOOLTIP_ENTRIES).forEach { append("\n• ${describe(it)}") }
            if (refs.size > MAX_TOOLTIP_ENTRIES) append("\n• … ${refs.size - MAX_TOOLTIP_ENTRIES} more")
        }
    }

    /** "deployment.yaml:42" — the document is already cached, so no file text is re-read. */
    private fun describe(element: PsiElement): String {
        val file = element.containingFile ?: return "<unknown>"
        val document = file.viewProvider.document
        val offset = element.textRange?.startOffset
        val line = if (document != null && offset != null && offset <= document.textLength) {
            document.getLineNumber(offset) + 1
        } else null
        return if (line != null) "${file.name}:$line" else file.name
    }
}

/**
 * Click handler for the "N refs" tag: navigates to the single target, or shows the usual
 * "choose target" popup, re-using exactly the same resolution the tag was rendered from.
 */
class EnvRefCountActionHandler : InlayActionHandler {

    override fun handleClick(editor: Editor, payload: InlayActionPayload) {
        val envVar = (payload as? StringInlayActionPayload)?.text ?: return
        val project = editor.project ?: return
        val file = PsiDocumentManager.getInstance(project).getPsiFile(editor.document) ?: return
        val vf = file.virtualFile ?: return
        val module = ModuleUtilCore.findModuleForFile(vf, project) ?: return

        val targets = counterpartRefs(envVar, project, module, vf, fromSpring = vf.isSpringApp())
        if (targets.isEmpty()) return
        // Navigates straight to a lone target and shows the standard chooser popup otherwise.
        PsiTargetNavigator(targets).navigate(editor, "References to $envVar")
    }
}



