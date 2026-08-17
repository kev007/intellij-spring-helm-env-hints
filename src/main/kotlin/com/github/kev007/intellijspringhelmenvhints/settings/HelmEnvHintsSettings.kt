package com.github.kev007.intellijspringhelmenvhints.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.util.SimpleModificationTracker

/**
 * Packs a colour into the same ARGB layout as `java.awt.Color.getRGB()`.
 * Used only to express the persisted defaults; the light/dark pair is turned into a
 * theme-aware `JBColor` at render time by the annotator.
 */
private fun argb(r: Int, g: Int, b: Int, a: Int = 255): Int =
    (a shl 24) or (r shl 16) or (g shl 8) or b

/**
 * Application-level persistent settings for Spring Helm Env Hints.
 * Colours are stored as packed ARGB integers, one value per (role, theme) pair.
 */
@Service(Service.Level.APP)
@State(name = "HelmEnvHintsSettings", storages = [Storage("helmEnvHints.xml")])
class HelmEnvHintsSettings : PersistentStateComponent<HelmEnvHintsSettings.State> {

    /**
     * Incremented whenever a setting that affects the env-var index is changed,
     * so that the cached index is invalidated immediately.
     */
    val indexTracker = SimpleModificationTracker()

    /** Field initialisers here are the single source of truth for the built-in defaults. */
    class State {
        // Whether to use font (foreground) colour instead of background highlight
        @JvmField var useTextColor: Boolean = false
        // Whether Spring property key paths (e.g. server.port → SERVER_PORT) are
        // converted to env var names and matched against Helm templates
        @JvmField var springKeyMatchingEnabled: Boolean = false
        // Whether env vars are matched across ALL project modules. When false,
        // matching is confined to the module that owns each file (the default).
        @JvmField var matchAcrossModules: Boolean = false
        // Whether folders excluded in the IntelliJ project structure (build, target,
        // out, … – typically generated code) are scanned when building the env-var
        // index. Off by default: generated copies of resources would otherwise
        // duplicate (and can falsely create) matches.
        @JvmField var includeExcludedFolders: Boolean = false
        // Whether an inline "N refs" tag is rendered next to a highlighted env var, stating
        // how many occurrences it resolves to on the opposite side. Occurrences without a
        // counterpart are never tagged, since there is nothing to navigate to.
        @JvmField var showReferenceCountTag: Boolean = true
        // Whether the tag is suppressed when there is exactly one counterpart ("1 ref").
        // On by default: a single reference is already reachable via ctrl/cmd-click, so the
        // tag adds clutter without adding information.
        @JvmField var hideSingleReferenceTag: Boolean = true
        // Background colours for "matched" highlight (translucent blue)
        @JvmField var matchedBgLightArgb: Int   = argb(11, 87, 208, a = 50)
        @JvmField var matchedBgDarkArgb: Int    = argb(127, 178, 255, a = 60)
        // Background colours for "unmatched" highlight (translucent red)
        @JvmField var unmatchedBgLightArgb: Int = argb(217, 48, 37, a = 50)
        @JvmField var unmatchedBgDarkArgb: Int  = argb(255, 138, 128, a = 60)
        // Underline colour for Spring matched references (opaque)
        @JvmField var springUnderlineLightArgb: Int = argb(11, 87, 208)
        @JvmField var springUnderlineDarkArgb: Int  = argb(127, 178, 255)
        // Font (foreground) colours – used when useTextColor = true
        @JvmField var matchedFgLightArgb: Int   = argb(11, 87, 208)
        @JvmField var matchedFgDarkArgb: Int    = argb(100, 180, 255)
        @JvmField var unmatchedFgLightArgb: Int = argb(180, 30, 20)
        @JvmField var unmatchedFgDarkArgb: Int  = argb(255, 130, 100)
    }

    private var myState = State()

    override fun getState(): State = myState
    override fun loadState(state: State) { myState = state }

    companion object {
        val instance: HelmEnvHintsSettings
            get() = ApplicationManager.getApplication().getService(HelmEnvHintsSettings::class.java)
    }
}
