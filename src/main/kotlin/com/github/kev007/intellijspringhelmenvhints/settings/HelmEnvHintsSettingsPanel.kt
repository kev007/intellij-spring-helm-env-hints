package com.github.kev007.intellijspringhelmenvhints.settings

import com.github.kev007.intellijspringhelmenvhints.navigation.getDebugInfo
import com.intellij.openapi.project.Project
import com.intellij.ui.ColorPanel
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

/**
 * Settings UI for Spring Helm Env Hints.
 *
 * Sections:
 *  • Matching – toggle for Spring property key matching.
 *  • Color Mode – radio buttons selecting between background highlight and font color.
 *  • Highlight Colors (Light / Dark theme) – visible only in highlight mode.
 *  • Font Colors (Light / Dark theme) – visible only in font-color mode.
 *  • Debug accordion – collapsible panel showing the current project's env-var match state.
 */
class HelmEnvHintsSettingsPanel(private val project: Project) {

    // ─── Matching toggles ────────────────────────────────────────────────────
    private val springKeyMatchingCheckBox = JBCheckBox(
        "Match Spring property keys to Helm env vars (e.g. server.port → SERVER_PORT)"
    )
    private val matchAcrossModulesCheckBox = JBCheckBox(
        "Match env vars across all project modules"
    )

    // ─── Mode radio buttons ──────────────────────────────────────────────────
    private val useHighlightRadio = JBRadioButton("Background highlight")
    private val useFontColorRadio = JBRadioButton("Font color")

    // ─── Color pickers ────────────────────────────────────────────────────────
    private val matchedBgLightPicker    = ColorPanel()
    private val matchedBgDarkPicker     = ColorPanel()
    private val unmatchedBgLightPicker  = ColorPanel()
    private val unmatchedBgDarkPicker   = ColorPanel()
    private val springUlLightPicker     = ColorPanel()
    private val springUlDarkPicker      = ColorPanel()

    // Alpha spinners – created lazily via helper to avoid field-init ordering issues
    private val matchedBgLightAlpha    = makeAlphaSpinner()
    private val matchedBgDarkAlpha     = makeAlphaSpinner()
    private val unmatchedBgLightAlpha  = makeAlphaSpinner()
    private val unmatchedBgDarkAlpha   = makeAlphaSpinner()

    // ─── Font color pickers ──────────────────────────────────────────────────
    private val matchedFgLightPicker   = ColorPanel()
    private val matchedFgDarkPicker    = ColorPanel()
    private val unmatchedFgLightPicker = ColorPanel()
    private val unmatchedFgDarkPicker  = ColorPanel()

    // ─── Debug text area ─────────────────────────────────────────────────────
    private val debugArea = JBTextArea(20, 60).apply {
        isEditable = false
        font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        text = "(Click Refresh to load the current match data for this project)"
    }

    // Panels built in createPanel(); stored so ItemListeners can toggle their visibility
    private var highlightColorsSection: JPanel? = null
    private var fontColorsSection: JPanel? = null

    // ─── Public API ──────────────────────────────────────────────────────────

    fun createPanel(): JComponent {
        // Build the two conditionally-visible color sections as plain Swing panels
        val bgSection = panel {
            group("Highlight Colors – Light Theme") {
                row("Matched background:") {
                    cell(matchedBgLightPicker)
                    label("  Alpha (0–255):")
                    cell(matchedBgLightAlpha)
                }
                row("Unmatched background:") {
                    cell(unmatchedBgLightPicker)
                    label("  Alpha (0–255):")
                    cell(unmatchedBgLightAlpha)
                }
                row("Spring underline:") { cell(springUlLightPicker) }
            }
            group("Highlight Colors – Dark Theme") {
                row("Matched background:") {
                    cell(matchedBgDarkPicker)
                    label("  Alpha (0–255):")
                    cell(matchedBgDarkAlpha)
                }
                row("Unmatched background:") {
                    cell(unmatchedBgDarkPicker)
                    label("  Alpha (0–255):")
                    cell(unmatchedBgDarkAlpha)
                }
                row("Spring underline:") { cell(springUlDarkPicker) }
            }
        }
        val fgSection = panel {
            group("Font Colors – Light Theme") {
                row("Matched font color:")   { cell(matchedFgLightPicker) }
                row("Unmatched font color:") { cell(unmatchedFgLightPicker) }
            }
            group("Font Colors – Dark Theme") {
                row("Matched font color:")   { cell(matchedFgDarkPicker) }
                row("Unmatched font color:") { cell(unmatchedFgDarkPicker) }
            }
        }

        highlightColorsSection = bgSection
        fontColorsSection      = fgSection
        updateColorSectionVisibility()

        // Toggle color sections when the radio selection changes
        useHighlightRadio.addItemListener { updateColorSectionVisibility() }
        useFontColorRadio.addItemListener { updateColorSectionVisibility() }

        return panel {
            group("Matching") {
                row { cell(springKeyMatchingCheckBox) }
                row {
                    comment(
                        "When enabled, Spring property keys are converted to env var names and " +
                        "matched against Helm env[].name entries. " +
                        "Disable to match only explicit \${ENV_VAR} references."
                    )
                }
                row { cell(matchAcrossModulesCheckBox) }
                row {
                    comment(
                        "When enabled, env vars are matched across every module in the project. " +
                        "Disable to confine matching to the module that owns each file."
                    )
                }
            }
            group("Color Mode") {
                buttonsGroup {
                    row {
                        cell(useHighlightRadio)
                        cell(useFontColorRadio)
                    }
                }
            }
            row { cell(bgSection).align(Align.FILL).resizableColumn() }
            row { cell(fgSection).align(Align.FILL).resizableColumn() }
            row {
                button("Reset to Defaults") { resetToDefaults() }
                comment("Changes take effect immediately when applied.")
            }
            collapsibleGroup("Debug: Current Matches") {
                row {
                    button("Refresh") { refreshDebug() }
                    comment("Reads the in-memory env-var index built from the open project's YAML files.")
                }
                row {
                    scrollCell(debugArea).align(Align.FILL).resizableColumn()
                }.resizableRow()
            }
        }
    }

    /** Load persisted settings into the UI controls. */
    fun reset() {
        val s = HelmEnvHintsSettings.instance.state
        springKeyMatchingCheckBox.isSelected = s.springKeyMatchingEnabled
        matchAcrossModulesCheckBox.isSelected = s.matchAcrossModules
        if (s.useTextColor) useFontColorRadio.isSelected = true else useHighlightRadio.isSelected = true
        loadColorWithAlpha(matchedBgLightPicker,   matchedBgLightAlpha,   s.matchedBgLightArgb)
        loadColorWithAlpha(matchedBgDarkPicker,    matchedBgDarkAlpha,    s.matchedBgDarkArgb)
        loadColorWithAlpha(unmatchedBgLightPicker, unmatchedBgLightAlpha, s.unmatchedBgLightArgb)
        loadColorWithAlpha(unmatchedBgDarkPicker,  unmatchedBgDarkAlpha,  s.unmatchedBgDarkArgb)
        loadOpaqueColor(springUlLightPicker, s.springUnderlineLightArgb)
        loadOpaqueColor(springUlDarkPicker,  s.springUnderlineDarkArgb)
        loadOpaqueColor(matchedFgLightPicker,   s.matchedFgLightArgb)
        loadOpaqueColor(matchedFgDarkPicker,    s.matchedFgDarkArgb)
        loadOpaqueColor(unmatchedFgLightPicker, s.unmatchedFgLightArgb)
        loadOpaqueColor(unmatchedFgDarkPicker,  s.unmatchedFgDarkArgb)
        // Sync section visibility in case createPanel() was already called
        updateColorSectionVisibility()
    }

    /** Persist UI control values into settings. */
    fun apply() {
        val s = HelmEnvHintsSettings.instance.state
        s.springKeyMatchingEnabled  = springKeyMatchingCheckBox.isSelected
        s.matchAcrossModules        = matchAcrossModulesCheckBox.isSelected
        s.useTextColor              = useFontColorRadio.isSelected
        s.matchedBgLightArgb        = readColorWithAlpha(matchedBgLightPicker,   matchedBgLightAlpha)
        s.matchedBgDarkArgb         = readColorWithAlpha(matchedBgDarkPicker,    matchedBgDarkAlpha)
        s.unmatchedBgLightArgb      = readColorWithAlpha(unmatchedBgLightPicker, unmatchedBgLightAlpha)
        s.unmatchedBgDarkArgb       = readColorWithAlpha(unmatchedBgDarkPicker,  unmatchedBgDarkAlpha)
        springUlLightPicker.selectedColor?.let { s.springUnderlineLightArgb = it.rgb }
        springUlDarkPicker.selectedColor?.let  { s.springUnderlineDarkArgb  = it.rgb }
        matchedFgLightPicker.selectedColor?.let   { s.matchedFgLightArgb   = it.rgb }
        matchedFgDarkPicker.selectedColor?.let    { s.matchedFgDarkArgb    = it.rgb }
        unmatchedFgLightPicker.selectedColor?.let { s.unmatchedFgLightArgb = it.rgb }
        unmatchedFgDarkPicker.selectedColor?.let  { s.unmatchedFgDarkArgb  = it.rgb }
    }

    /** Returns true when the UI differs from the persisted settings. */
    fun isModified(): Boolean {
        val s = HelmEnvHintsSettings.instance.state
        return springKeyMatchingCheckBox.isSelected != s.springKeyMatchingEnabled ||
               matchAcrossModulesCheckBox.isSelected != s.matchAcrossModules ||
               useFontColorRadio.isSelected != s.useTextColor ||
               readColorWithAlpha(matchedBgLightPicker,   matchedBgLightAlpha)   != s.matchedBgLightArgb  ||
               readColorWithAlpha(matchedBgDarkPicker,    matchedBgDarkAlpha)    != s.matchedBgDarkArgb   ||
               readColorWithAlpha(unmatchedBgLightPicker, unmatchedBgLightAlpha) != s.unmatchedBgLightArgb ||
               readColorWithAlpha(unmatchedBgDarkPicker,  unmatchedBgDarkAlpha)  != s.unmatchedBgDarkArgb  ||
               springUlLightPicker.selectedColor?.rgb != s.springUnderlineLightArgb ||
               springUlDarkPicker.selectedColor?.rgb  != s.springUnderlineDarkArgb  ||
               matchedFgLightPicker.selectedColor?.rgb   != s.matchedFgLightArgb   ||
               matchedFgDarkPicker.selectedColor?.rgb    != s.matchedFgDarkArgb    ||
               unmatchedFgLightPicker.selectedColor?.rgb != s.unmatchedFgLightArgb ||
               unmatchedFgDarkPicker.selectedColor?.rgb  != s.unmatchedFgDarkArgb
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    /** Shows the color section that corresponds to the currently-selected radio button. */
    private fun updateColorSectionVisibility() {
        val showBg = useHighlightRadio.isSelected
        highlightColorsSection?.isVisible = showBg
        fontColorsSection?.isVisible      = !showBg
        highlightColorsSection?.parent?.let {
            (it as? JComponent)?.revalidate()
            it.repaint()
        }
    }

    /** Resets all controls to the built-in defaults. */
    private fun resetToDefaults() {
        springKeyMatchingCheckBox.isSelected = true
        matchAcrossModulesCheckBox.isSelected = false
        useHighlightRadio.isSelected = true
        loadColorWithAlpha(matchedBgLightPicker,   matchedBgLightAlpha,   HelmEnvHintsSettings.DEF_MATCHED_BG_LIGHT.rgb)
        loadColorWithAlpha(matchedBgDarkPicker,    matchedBgDarkAlpha,    HelmEnvHintsSettings.DEF_MATCHED_BG_DARK.rgb)
        loadColorWithAlpha(unmatchedBgLightPicker, unmatchedBgLightAlpha, HelmEnvHintsSettings.DEF_UNMATCHED_BG_LIGHT.rgb)
        loadColorWithAlpha(unmatchedBgDarkPicker,  unmatchedBgDarkAlpha,  HelmEnvHintsSettings.DEF_UNMATCHED_BG_DARK.rgb)
        loadOpaqueColor(springUlLightPicker, HelmEnvHintsSettings.DEF_SPRING_UL_LIGHT.rgb)
        loadOpaqueColor(springUlDarkPicker,  HelmEnvHintsSettings.DEF_SPRING_UL_DARK.rgb)
        loadOpaqueColor(matchedFgLightPicker,   HelmEnvHintsSettings.DEF_MATCHED_FG_LIGHT.rgb)
        loadOpaqueColor(matchedFgDarkPicker,    HelmEnvHintsSettings.DEF_MATCHED_FG_DARK.rgb)
        loadOpaqueColor(unmatchedFgLightPicker, HelmEnvHintsSettings.DEF_UNMATCHED_FG_LIGHT.rgb)
        loadOpaqueColor(unmatchedFgDarkPicker,  HelmEnvHintsSettings.DEF_UNMATCHED_FG_DARK.rgb)
    }

    /** Queries the project's env-var index and renders the results in [debugArea]. */
    private fun refreshDebug() {
        val infos = getDebugInfo(project)
        if (infos.isEmpty()) {
            debugArea.text = "(No env var data found.\n" +
                "Make sure the project contains Spring application*.yml and Helm templates/ YAML files.)"
            return
        }
        val sb = StringBuilder()
        for (m in infos) {
            sb.appendLine("══════════════════════════════════════════")
            sb.appendLine("  Module: ${m.moduleName}")
            sb.appendLine("══════════════════════════════════════════")
            if (m.matchedVars.isEmpty() && m.helmOnlyVars.isEmpty() && m.springOnlyVars.isEmpty()) {
                sb.appendLine("  (No env vars found in this module)")
            } else {
                appendDebugSection(sb, "✓  Matched (both Helm + Spring)", m.matchedVars)
                appendDebugSection(sb, "⚠  Helm-only  (no Spring counterpart)", m.helmOnlyVars)
                appendDebugSection(sb, "⚠  Spring-only (no Helm counterpart)", m.springOnlyVars)
            }
            sb.appendLine()
        }
        debugArea.text = sb.toString()
        debugArea.caretPosition = 0
    }

    private fun appendDebugSection(sb: StringBuilder, header: String, vars: List<String>) {
        sb.appendLine("  $header (${vars.size}):")
        if (vars.isEmpty()) sb.appendLine("    (none)")
        else vars.forEach { sb.appendLine("    · $it") }
    }

    // ─── Color helpers ────────────────────────────────────────────────────────

    private fun makeAlphaSpinner() = JSpinner(SpinnerNumberModel(50, 0, 255, 1)).apply {
        preferredSize = Dimension(65, preferredSize.height)
    }

    private fun loadColorWithAlpha(picker: ColorPanel, spinner: JSpinner, argb: Int) {
        val c = Color(argb, true)
        picker.selectedColor = Color(c.red, c.green, c.blue)
        spinner.value = c.alpha
    }

    private fun loadOpaqueColor(picker: ColorPanel, argb: Int) {
        val c = Color(argb, true)
        picker.selectedColor = Color(c.red, c.green, c.blue)
    }

    private fun readColorWithAlpha(picker: ColorPanel, spinner: JSpinner): Int {
        val c = picker.selectedColor ?: return 0
        val alpha = (spinner.value as Int).coerceIn(0, 255)
        return Color(c.red, c.green, c.blue, alpha).rgb
    }
}
