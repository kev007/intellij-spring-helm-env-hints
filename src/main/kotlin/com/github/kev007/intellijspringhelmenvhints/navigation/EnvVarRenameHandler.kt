package com.github.kev007.intellijspringhelmenvhints.navigation

import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.InputValidatorEx
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.ReadonlyStatusHandler
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.refactoring.rename.RenameHandler

/** Accepted env var names — same shape as the names [ENV_REF_REGEX] recognises. */
private val ENV_NAME_REGEX = Regex("""[A-Za-z_][A-Za-z0-9_.\-]*""")

/**
 * *Refactor → Rename* (Shift+F6) for env vars, renaming every occurrence of the var inside the
 * matching scope at once: the Helm `env[*].name` entries and the Spring `${ENV_VAR}`
 * placeholders, across all files.
 *
 * A dedicated handler is required because the platform's default rename works on a resolved
 * *declaration*, and there is none here: both sides are plain `YAMLScalar`s, which are not
 * [com.intellij.psi.PsiNamedElement]s, so `PsiElementRenameHandler` bails out with
 * "cannot be renamed" no matter how the references are set up. Renaming through the index —
 * the very same [resolveEnvMatch] data that drives highlighting and navigation — also keeps
 * rename consistent with what the user sees, and works for one-sided (unmatched) vars.
 *
 * Spring occurrences derived from *property keys* (`my.service.url` → `MY_SERVICE_URL`,
 * an opt-in setting) are deliberately left untouched: their text is not the env var name.
 */
class EnvVarRenameHandler : RenameHandler {

    override fun isAvailableOnDataContext(dataContext: DataContext): Boolean =
        occurrenceAt(dataContext) != null

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?, dataContext: DataContext?) {
        val ctx = dataContext ?: return
        val psiFile = file ?: CommonDataKeys.PSI_FILE.getData(ctx) ?: return
        val caretEditor = editor ?: CommonDataKeys.EDITOR.getData(ctx) ?: return
        val occurrence = envOccurrenceAt(psiFile, caretEditor.caretModel.offset) ?: return
        val module = ModuleUtil.findModuleForPsiElement(psiFile) ?: return

        val newName = Messages.showInputDialog(
            project,
            "Rename environment variable '${occurrence.envVar}' and all its Spring/Helm occurrences to:",
            "Rename Environment Variable",
            null,
            occurrence.envVar,
            EnvNameValidator,
        )?.trim().orEmpty()
        if (newName.isEmpty() || newName == occurrence.envVar) return

        renameEverywhere(project, psiFile, module, occurrence, newName)
    }

    /** Rename invoked outside an editor (e.g. from a tree) is not supported. */
    override fun invoke(project: Project, elements: Array<out PsiElement>, dataContext: DataContext?) {
        val ctx = dataContext ?: return
        invoke(project, CommonDataKeys.EDITOR.getData(ctx), CommonDataKeys.PSI_FILE.getData(ctx), ctx)
    }
}

private fun occurrenceAt(dataContext: DataContext): EnvSpan? {
    val editor = CommonDataKeys.EDITOR.getData(dataContext) ?: return null
    val psiFile = CommonDataKeys.PSI_FILE.getData(dataContext) ?: return null
    // Both the availability query (run under the action update read action) and the actual
    // invocation (EDT) already hold read access, so no extra wrapping is needed here.
    return envOccurrenceAt(psiFile, editor.caretModel.offset)
}

/**
 * Replaces the name text of every occurrence of `occurrence.envVar` in the scope of [module]
 * with [newName], as one undoable command spanning all touched files.
 *
 * Edits are applied on the documents (not through the PSI) because an occurrence is a slice of
 * a scalar's text, and are applied back-to-front per document so earlier offsets stay valid.
 */
private fun renameEverywhere(
    project: Project,
    sourceFile: PsiFile,
    module: Module,
    occurrence: EnvSpan,
    newName: String,
) {
    val documentManager = PsiDocumentManager.getInstance(project)
    val edits = LinkedHashMap<Document, MutableSet<TextRange>>()

    fun collect(psiFile: PsiFile?, range: TextRange) {
        val document = psiFile?.let(documentManager::getDocument) ?: return
        edits.getOrPut(document) { linkedSetOf() } += range
    }

    // The occurrence under the caret is always renamed, even if the index has not caught up.
    collect(sourceFile, TextRange(occurrence.startOffset, occurrence.endOffset))
    envOccurrences(occurrence.envVar, project, module)
        .filter { it.isValid }
        .forEach { element ->
            envNameRanges(element, occurrence.envVar).forEach { collect(element.containingFile, it) }
        }

    val files = edits.keys.mapNotNull(documentManager::getPsiFile)
    val virtualFiles = files.mapNotNull { it.virtualFile }
    if (virtualFiles.isEmpty()) return
    if (ReadonlyStatusHandler.getInstance(project).ensureFilesWritable(virtualFiles).hasReadonlyFiles()) return

    WriteCommandAction.writeCommandAction(project, *files.toTypedArray())
        .withName("Rename Environment Variable")
        .run<RuntimeException> {
            for ((document, ranges) in edits) {
                ranges.sortedByDescending { it.startOffset }
                    .forEach { document.replaceString(it.startOffset, it.endOffset, newName) }
            }
            documentManager.commitAllDocuments()
        }
}

private object EnvNameValidator : InputValidatorEx {
    override fun getErrorText(inputString: String?): String? {
        val value = inputString?.trim().orEmpty()
        return when {
            value.isEmpty() -> "Name must not be empty"
            !ENV_NAME_REGEX.matches(value) ->
                "'$value' is not a valid environment variable name"
            else -> null
        }
    }

    override fun checkInput(inputString: String?): Boolean = getErrorText(inputString) == null

    override fun canClose(inputString: String?): Boolean = checkInput(inputString)
}
