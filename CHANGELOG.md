<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# intellij-spring-helm-env-hints Changelog

## [Unreleased]
### Added
- Settings toggle "Scan folders excluded in the project structure (build, target, out, …)", off by default. When disabled, YAML files under excluded roots are no longer indexed, so generated copies of Spring/Helm resources cannot duplicate or falsely create matches.
- Settings toggle "Match env vars across all project modules" to control whether Spring/Helm env vars are matched project-wide or only within each module.
- Initial scaffold created from [IntelliJ Platform Plugin Template](https://github.com/JetBrains/intellij-platform-plugin-template)

### Changed
- Reworked env-var matching to a single, symmetric match resolver: each file is indexed once under its deepest owning module and modules are grouped into matching scopes, so a match is computed once and used for both Helm→Spring and Spring→Helm.
- Spring `${ENV_VAR}` detection now scans only the element being inspected instead of the whole file for every element, removing quadratic behaviour in the annotator and reference contributor.
- Condensed duplicated logic: one parameterised highlight builder replaces five colour accessors and three attribute builders, the Spring/Helm reference branches share one code path, and the settings panel drives reset/apply/isModified/defaults from a single colour-binding list.
- Rewrote `README.md` around the current implementation and added notes on file classification, scope grouping, the cached index and its invalidation.

### Fixed
- The settings "Reset to Defaults" button no longer sets Spring key matching to a value that differs from the actual built-in default; defaults now come from a single source.
- The settings debug view now reads the PSI-backed index under a read action instead of directly on the EDT.
- Removed dead code: unused `TextAttributesKey` declarations, an unused `PsiFile.yamlRoot()` helper, duplicated default-colour constants, and the unused `MyBundle` template resource bundle.
- Spring `application.yaml` no longer matches Helm entries defined in unrelated project modules when cross-module matching is disabled.
- Scope grouping no longer collapses every module into one scope via a shared aggregate/root module; source-set sub-modules are now merged into their service by module name (`<service>.main`) rather than by path containment.
- Matching no longer breaks when the "Go Template" plugin (or any template-language plugin) owns Helm template files: the YAML PSI root is now resolved through the file's view provider (`getPsi(YAMLLanguage)`) instead of assuming the file's base language is YAML.
- Navigating from a Spring `${ENV_VAR}` reference no longer resolves back into the originating file (a self-loop). This occurred when a file qualified as both a Spring app file and a Helm template; navigation targets located in the source file itself are now filtered out.

