package com.github.kev007.intellijspringhelmenvhints.settings

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.codeInsight.hints.declarative.impl.DeclarativeInlayHintsPassFactory
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
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
        // Drop the cached inlay stamp so the "N refs" tags are recomputed, not reused
        DeclarativeInlayHintsPassFactory.resetModificationStamp()
        // Force immediate re-highlighting of all open editors in this project
        DaemonCodeAnalyzer.getInstance(project).restart()
    }

    override fun reset() {
        settingsPanel?.reset()
    }

    override fun disposeUIResources() {
        settingsPanel = null
    }
}
