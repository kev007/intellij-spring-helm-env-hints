package com.github.kev007.intellijspringhelmenvhints.navigation

import com.intellij.lang.Language
import com.intellij.lang.MetaLanguage
import com.intellij.psi.templateLanguages.TemplateLanguage
import org.jetbrains.yaml.YAMLLanguage

/** Language id of [YamlLikeMetaLanguage]; kept in sync with `plugin.xml`. */
internal const val YAML_LIKE_META_LANGUAGE_ID = "SpringHelmEnvHintsYamlLike"

/**
 * Language matcher used to register the declarative inlay provider.
 *
 * Declarative inlay providers are looked up by the BASE language of the file
 * (`DeclarativeInlayHintsPassFactory` → `InlayHintsProviderFactory.getProvidersForLanguage(file.language)`),
 * and a provider registered for `yaml` only matches languages that are `isKindOf(YAML)`.
 *
 * A Helm template is not one of those. With the Kubernetes plugin (plus "Go Template")
 * installed, YAML files of a chart are parsed as `HelmYAML`, a dialect of `GoTemplate`
 * that keeps YAML as its *template-data* language in a second PSI root. `HelmYAML.isKindOf(YAML)`
 * is false, so a `yaml`-registered provider is never even instantiated for those files —
 * which is why the "N refs" tag never showed up in Helm templates, while the annotator and
 * the reference contributor (which the daemon runs against every root of the view provider)
 * kept working.
 *
 * Matching YAML dialects *and* template languages covers plain YAML, `HelmYAML`,
 * `GoTemplate`, `WerfYAML` and anything similar without a compile-time dependency on those
 * plugins. Files that merely happen to be templates are rejected right away by
 * [EnvRefCountInlayProvider.createCollector], which only accepts `application*` YAML files
 * and YAML files under a `templates/` directory.
 */
class YamlLikeMetaLanguage : MetaLanguage(YAML_LIKE_META_LANGUAGE_ID) {

    override fun matchesLanguage(language: Language): Boolean =
        language.isKindOf(YAMLLanguage.INSTANCE) || language is TemplateLanguage

    override fun getDisplayName(): String = "YAML (including Helm templates)"
}




