package com.github.kev007.intellijspringhelmenvhints.settings

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.psi.PsiManager
import javax.swing.JComponent

/**
 * Registers the plugin's settings page under Settings → Other Settings → Spring Helm Env Hints.
 *
 * Color preferences are stored in [HelmEnvHintsSettings] (application-level, shared across
 * all projects). The debug accordion uses [project] to query the current match index.
 */
class HelmEnvHintsConfigurable(private val project: Project) : Configurable {

    private var settingsPanel: HelmEnvHintsSettingsPanel? = null

    override fun getDisplayName(): String = "Spring Helm Env Hints"

    override fun createComponent(): JComponent {
        val panel = HelmEnvHintsSettingsPanel(project).also { settingsPanel = it }
        panel.reset()
        return panel.createPanel()
    }

    override fun isModified(): Boolean = settingsPanel?.isModified() ?: false

    override fun apply() {
        settingsPanel?.apply()
        // Invalidate the cached env-var index so index-affecting settings take effect
        HelmEnvHintsSettings.instance.indexTracker.incModificationCount()
        // Settings are application-level, so every open project has to pick them up.
        for (openProject in ProjectManager.getInstance().openProjects) {
            if (openProject.isDisposed) continue
            // The declarative inlay pass skips a file whose cached stamp still equals the
            // project's PSI modification count, which would make it reuse the previous
            // "N refs" tags. Dropping the PSI caches bumps that counter and is public API,
            // unlike DeclarativeInlayHintsPassFactory.resetModificationStamp().
            PsiManager.getInstance(openProject).dropPsiCaches()
            // Force immediate re-highlighting of all open editors
            DaemonCodeAnalyzer.getInstance(openProject).restart()
        }
    }

    override fun reset() {
        settingsPanel?.reset()
    }

    override fun disposeUIResources() {
        settingsPanel = null
    }
}
