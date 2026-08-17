package com.github.kev007.intellijspringhelmenvhints.settings

import com.github.kev007.intellijspringhelmenvhints.navigation.ModuleDebugInfo
import com.github.kev007.intellijspringhelmenvhints.navigation.getDebugInfo
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.ui.ColorPanel
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.panel
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

private typealias State = HelmEnvHintsSettings.State

/** Built-in defaults, read straight off a fresh [State] so they can never drift out of sync. */
private val DEFAULTS = State()

/**
 * Binds one [ColorPanel] (plus an optional alpha spinner) to a packed-ARGB field of [State].
 *
 * Keeping the picker together with its state accessors lets `reset`/`apply`/`isModified`/
 * `resetToDefaults` all be driven by a single list instead of repeating the same ten
 * colours four times over.
 */
private class ColorBinding(
    val picker: ColorPanel,
    val alphaSpinner: JSpinner?,
    private val read: (State) -> Int,
    private val write: (State, Int) -> Unit,
) {
    /** Current UI value as packed ARGB; [fallback] is used while no colour is selected. */
    private fun uiArgb(fallback: Int): Int {
        val c = picker.selectedColor ?: return fallback
        val alpha = alphaSpinner?.let { (it.value as Int).coerceIn(0, 255) } ?: 255
        return Color(c.red, c.green, c.blue, alpha).rgb
    }

    private fun load(argb: Int) {
        val c = Color(argb, true)
        // ColorPanel has no alpha channel, so RGB and alpha are edited separately.
        picker.selectedColor = Color(c.red, c.green, c.blue)
        alphaSpinner?.value = c.alpha
    }

    fun reset(state: State) = load(read(state))
    fun loadDefault() = load(read(DEFAULTS))
    fun apply(state: State) = write(state, uiArgb(read(state)))
    fun isModified(state: State) = uiArgb(read(state)) != read(state)
}

/**
 * Settings UI for Spring Helm Env Hints.
 *
 * Sections:
 *  • Matching – Spring property-key matching, cross-module matching and excluded-folder
 *    scanning toggles.
 *  • Color Mode – background highlight vs font color; selects which colour section is shown.
 *  • Highlight / Font Colors (Light + Dark theme).
 *  • Debug accordion – collapsible view of the current project's env-var match state.
 */
class HelmEnvHintsSettingsPanel(private val project: Project) {

    // ─── Matching toggles ────────────────────────────────────────────────────
    private val springKeyMatchingCheckBox = JBCheckBox(
        "Match Spring property keys to Helm env vars (e.g. server.port → SERVER_PORT)"
    )
    private val matchAcrossModulesCheckBox = JBCheckBox(
        "Match env vars across all project modules"
    )
    private val includeExcludedFoldersCheckBox = JBCheckBox(
        "Scan folders excluded in the project structure (build, target, out, …)"
    )

    // ─── Mode radio buttons ──────────────────────────────────────────────────
    private val useHighlightRadio = JBRadioButton("Background highlight")
    private val useFontColorRadio = JBRadioButton("Font color")

    // ─── Colour bindings (declaration order is independent of the layout) ────
    private val matchedBgLight   = binding(alpha = true,
        { it.matchedBgLightArgb },       { s, v -> s.matchedBgLightArgb = v })
    private val matchedBgDark    = binding(alpha = true,
        { it.matchedBgDarkArgb },        { s, v -> s.matchedBgDarkArgb = v })
    private val unmatchedBgLight = binding(alpha = true,
        { it.unmatchedBgLightArgb },     { s, v -> s.unmatchedBgLightArgb = v })
    private val unmatchedBgDark  = binding(alpha = true,
        { it.unmatchedBgDarkArgb },      { s, v -> s.unmatchedBgDarkArgb = v })
    private val springUlLight    = binding(alpha = false,
        { it.springUnderlineLightArgb }, { s, v -> s.springUnderlineLightArgb = v })
    private val springUlDark     = binding(alpha = false,
        { it.springUnderlineDarkArgb },  { s, v -> s.springUnderlineDarkArgb = v })
    private val matchedFgLight   = binding(alpha = false,
        { it.matchedFgLightArgb },       { s, v -> s.matchedFgLightArgb = v })
    private val matchedFgDark    = binding(alpha = false,
        { it.matchedFgDarkArgb },        { s, v -> s.matchedFgDarkArgb = v })
    private val unmatchedFgLight = binding(alpha = false,
        { it.unmatchedFgLightArgb },     { s, v -> s.unmatchedFgLightArgb = v })
    private val unmatchedFgDark  = binding(alpha = false,
        { it.unmatchedFgDarkArgb },      { s, v -> s.unmatchedFgDarkArgb = v })

    private val bindings = listOf(
        matchedBgLight, matchedBgDark, unmatchedBgLight, unmatchedBgDark,
        springUlLight, springUlDark,
        matchedFgLight, matchedFgDark, unmatchedFgLight, unmatchedFgDark,
    )

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
        val bgSection = panel {
            group("Highlight Colors – Light Theme") {
                colorRow("Matched background:", matchedBgLight)
                colorRow("Unmatched background:", unmatchedBgLight)
                colorRow("Spring underline:", springUlLight)
            }
            group("Highlight Colors – Dark Theme") {
                colorRow("Matched background:", matchedBgDark)
                colorRow("Unmatched background:", unmatchedBgDark)
                colorRow("Spring underline:", springUlDark)
            }
        }
        val fgSection = panel {
            group("Font Colors – Light Theme") {
                colorRow("Matched font color:", matchedFgLight)
                colorRow("Unmatched font color:", unmatchedFgLight)
            }
            group("Font Colors – Dark Theme") {
                colorRow("Matched font color:", matchedFgDark)
                colorRow("Unmatched font color:", unmatchedFgDark)
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
                row { cell(includeExcludedFoldersCheckBox) }
                row {
                    comment(
                        "When enabled, YAML files inside excluded folders (generated / build " +
                        "output) are indexed too. Disable to skip them, which is usually what " +
                        "you want since they are copies of the real sources."
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
        includeExcludedFoldersCheckBox.isSelected = s.includeExcludedFolders
        if (s.useTextColor) useFontColorRadio.isSelected = true else useHighlightRadio.isSelected = true
        bindings.forEach { it.reset(s) }
        // Sync section visibility in case createPanel() was already called
        updateColorSectionVisibility()
    }

    /** Persist UI control values into settings. */
    fun apply() {
        val s = HelmEnvHintsSettings.instance.state
        s.springKeyMatchingEnabled = springKeyMatchingCheckBox.isSelected
        s.matchAcrossModules       = matchAcrossModulesCheckBox.isSelected
        s.includeExcludedFolders   = includeExcludedFoldersCheckBox.isSelected
        s.useTextColor             = useFontColorRadio.isSelected
        bindings.forEach { it.apply(s) }
    }

    /** Returns true when the UI differs from the persisted settings. */
    fun isModified(): Boolean {
        val s = HelmEnvHintsSettings.instance.state
        return springKeyMatchingCheckBox.isSelected != s.springKeyMatchingEnabled ||
               matchAcrossModulesCheckBox.isSelected != s.matchAcrossModules ||
               includeExcludedFoldersCheckBox.isSelected != s.includeExcludedFolders ||
               useFontColorRadio.isSelected != s.useTextColor ||
               bindings.any { it.isModified(s) }
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    private fun binding(
        alpha: Boolean,
        read: (State) -> Int,
        write: (State, Int) -> Unit,
    ) = ColorBinding(ColorPanel(), if (alpha) makeAlphaSpinner() else null, read, write)

    private fun Panel.colorRow(label: String, binding: ColorBinding) {
        row(label) {
            cell(binding.picker)
            binding.alphaSpinner?.let {
                label("  Alpha (0–255):")
                cell(it)
            }
        }
    }

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

    /** Resets all controls to the built-in defaults declared on [State]. */
    private fun resetToDefaults() {
        springKeyMatchingCheckBox.isSelected = DEFAULTS.springKeyMatchingEnabled
        matchAcrossModulesCheckBox.isSelected = DEFAULTS.matchAcrossModules
        includeExcludedFoldersCheckBox.isSelected = DEFAULTS.includeExcludedFolders
        if (DEFAULTS.useTextColor) useFontColorRadio.isSelected = true else useHighlightRadio.isSelected = true
        bindings.forEach { it.loadDefault() }
    }

    /**
     * Queries the project's env-var index and renders the results in [debugArea].
     * The index walks PSI, so it must be read under a read action — this runs on the EDT.
     */
    private fun refreshDebug() {
        val infos = ReadAction.compute<List<ModuleDebugInfo>, RuntimeException> { getDebugInfo(project) }
        if (infos.isEmpty()) {
            debugArea.text = "(No env var data found.\n" +
                "Make sure the project contains Spring application*.yml and Helm templates/ YAML files.)"
            return
        }
        debugArea.text = buildString {
            for (m in infos) {
                appendLine("══════════════════════════════════════════")
                appendLine("  Module: ${m.moduleName}")
                appendLine("══════════════════════════════════════════")
                val sections = listOf(
                    "✓  Matched (both Helm + Spring)" to m.matchedVars,
                    "⚠  Helm-only  (no Spring counterpart)" to m.helmOnlyVars,
                    "⚠  Spring-only (no Helm counterpart)" to m.springOnlyVars,
                )
                if (sections.all { it.second.isEmpty() }) {
                    appendLine("  (No env vars found in this module)")
                } else {
                    for ((header, vars) in sections) {
                        appendLine("  $header (${vars.size}):")
                        if (vars.isEmpty()) appendLine("    (none)") else vars.forEach { appendLine("    · $it") }
                    }
                }
                appendLine()
            }
        }
        debugArea.caretPosition = 0
    }

    private fun makeAlphaSpinner() = JSpinner(SpinnerNumberModel(50, 0, 255, 1)).apply {
        preferredSize = Dimension(65, preferredSize.height)
    }
}
