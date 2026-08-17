<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# intellij-spring-helm-env-hints Changelog

## [Unreleased]
### Added
- Settings toggle "Hide the tag when there is only one reference (\"1 ref\")", on by default and nested under the "N refs" tag toggle. Single-reference occurrences were previously always skipped; the behaviour is now configurable, so the `1 ref` tag can be rendered as well.
- Inline "N refs" tag rendered next to a highlighted env var, stating how many occurrences it resolves to on the opposite side (Helm entries for a Spring `${ENV_VAR}`, Spring occurrences for a Helm `env[].name`). The tag's tooltip lists the target `file:line`s, and clicking it navigates to the target or opens the chooser popup. It can be switched off in the plugin settings or under Settings → Editor → Inlay Hints → Other.
- Settings toggle "Scan folders excluded in the project structure (build, target, out, …)", off by default. When disabled, YAML files under excluded roots are no longer indexed, so generated copies of Spring/Helm resources cannot duplicate or falsely create matches.
- Settings toggle "Match env vars across all project modules" to control whether Spring/Helm env vars are matched project-wide or only within each module.
- Initial scaffold created from [IntelliJ Platform Plugin Template](https://github.com/JetBrains/intellij-platform-plugin-template)

### Changed
- Reworked env-var matching to a single, symmetric match resolver: each file is indexed once under its deepest owning module and modules are grouped into matching scopes, so a match is computed once and used for both Helm→Spring and Spring→Helm.
- Spring `${ENV_VAR}` detection now scans only the element being inspected instead of the whole file for every element, removing quadratic behaviour in the annotator and reference contributor.
- Condensed duplicated logic: one parameterised highlight builder replaces five colour accessors and three attribute builders, the Spring/Helm reference branches share one code path, and the settings panel drives reset/apply/isModified/defaults from a single colour-binding list.
- Rewrote `README.md` around the current implementation and added notes on file classification, scope grouping, the cached index and its invalidation.

### Fixed
- The "N refs" tag is no longer rendered once per enclosing YAML mapping level: a reference such as `spring.datasource.url: ${DATASOURCE_URL}` produced three identical tags, because a nested `YAMLMapping` is the *value* of its parent key and its text spans every `${ENV_VAR}` of its subtree. The inlay collector now owns its traversal (`OwnBypassCollector`) and walks scalars, which cannot nest, so every occurrence is tagged exactly once. As a side effect `${ENV_VAR}` references inside YAML sequences are highlighted and tagged too, matching what the index already recorded.
- The "N refs" tag now appears in Helm templates. Declarative inlay providers are resolved from the file's *base* language, and with the Kubernetes / "Go Template" plugins installed a Helm template's base language is `HelmYAML` (a `GoTemplate` dialect), not YAML — so the provider registered for `yaml` was never instantiated, and the platform's element walk never reached the YAML template-data PSI root either. The provider is now registered for a `MetaLanguage` matching YAML dialects and template languages, and resolves the YAML root through the view provider.
- The tag is shown whenever an env var has at least one counterpart, instead of only for two or more. On the Helm side a `name:` entry usually has exactly one Spring occurrence, which made the Helm→Spring direction of the feature unreachable in practice.
- The annotator no longer re-annotates the same env var occurrence once per enclosing YAML mapping (same root cause as the duplicated tags); it now only looks at scalars.
- The settings "Reset to Defaults" button no longer sets Spring key matching to a value that differs from the actual built-in default; defaults now come from a single source.
- The settings debug view now reads the PSI-backed index under a read action instead of directly on the EDT.
- Removed dead code: unused `TextAttributesKey` declarations, an unused `PsiFile.yamlRoot()` helper, duplicated default-colour constants, and the unused `MyBundle` template resource bundle.
- Spring `application.yaml` no longer matches Helm entries defined in unrelated project modules when cross-module matching is disabled.
- Scope grouping no longer collapses every module into one scope via a shared aggregate/root module; source-set sub-modules are now merged into their service by module name (`<service>.main`) rather than by path containment.
- Matching no longer breaks when the "Go Template" plugin (or any template-language plugin) owns Helm template files: the YAML PSI root is now resolved through the file's view provider (`getPsi(YAMLLanguage)`) instead of assuming the file's base language is YAML.
- Navigating from a Spring `${ENV_VAR}` reference no longer resolves back into the originating file (a self-loop). This occurred when a file qualified as both a Spring app file and a Helm template; navigation targets located in the source file itself are now filtered out.

