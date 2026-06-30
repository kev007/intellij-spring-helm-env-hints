package com.github.kev007.intellijspringhelmenvhints.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.util.SimpleModificationTracker
import java.awt.Color

/**
 * Application-level persistent settings for Spring Helm Env Hints.
 * Colors are stored as packed ARGB integers (matching [Color.getRGB]).
 */
@Service(Service.Level.APP)
@State(name = "HelmEnvHintsSettings", storages = [Storage("helmEnvHints.xml")])
class HelmEnvHintsSettings : PersistentStateComponent<HelmEnvHintsSettings.State> {

    /**
     * Incremented whenever a setting that affects the env-var index is changed,
     * so that the cached index is invalidated immediately.
     */
    val indexTracker = SimpleModificationTracker()

    class State {
        // Whether to use font (foreground) colour instead of background highlight
        @JvmField var useTextColor: Boolean = false
        // Whether Spring property key paths (e.g. server.port → SERVER_PORT) are
        // converted to env var names and matched against Helm templates
        @JvmField var springKeyMatchingEnabled: Boolean = true
        // Background colours for "matched" highlight (transparent blue)
        @JvmField var matchedBgLightArgb: Int  = Color(11, 87, 208, 50).rgb
        @JvmField var matchedBgDarkArgb: Int   = Color(127, 178, 255, 60).rgb
        // Background colours for "unmatched" highlight (transparent red)
        @JvmField var unmatchedBgLightArgb: Int = Color(217, 48, 37, 50).rgb
        @JvmField var unmatchedBgDarkArgb: Int  = Color(255, 138, 128, 60).rgb
        // Underline colour for Spring matched references (opaque)
        @JvmField var springUnderlineLightArgb: Int = Color(11, 87, 208).rgb
        @JvmField var springUnderlineDarkArgb: Int  = Color(127, 178, 255).rgb
        // Font (foreground) colours – used when useTextColor = true
        @JvmField var matchedFgLightArgb: Int   = Color(11, 87, 208).rgb
        @JvmField var matchedFgDarkArgb: Int    = Color(100, 180, 255).rgb
        @JvmField var unmatchedFgLightArgb: Int = Color(180, 30, 20).rgb
        @JvmField var unmatchedFgDarkArgb: Int  = Color(255, 130, 100).rgb
    }

    private var myState = State()

    override fun getState(): State = myState
    override fun loadState(state: State) { myState = state }

    companion object {
        val instance: HelmEnvHintsSettings
            get() = ApplicationManager.getApplication().getService(HelmEnvHintsSettings::class.java)

        // Defaults – used for "Reset to Defaults" button
        val DEF_MATCHED_BG_LIGHT:    Color = Color(11, 87, 208, 50)
        val DEF_MATCHED_BG_DARK:     Color = Color(127, 178, 255, 60)
        val DEF_UNMATCHED_BG_LIGHT:  Color = Color(217, 48, 37, 50)
        val DEF_UNMATCHED_BG_DARK:   Color = Color(255, 138, 128, 60)
        val DEF_SPRING_UL_LIGHT:     Color = Color(11, 87, 208)
        val DEF_SPRING_UL_DARK:      Color = Color(127, 178, 255)
        val DEF_MATCHED_FG_LIGHT:    Color = Color(11, 87, 208)
        val DEF_MATCHED_FG_DARK:     Color = Color(100, 180, 255)
        val DEF_UNMATCHED_FG_LIGHT:  Color = Color(180, 30, 20)
        val DEF_UNMATCHED_FG_DARK:   Color = Color(255, 130, 100)
    }
}
