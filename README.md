# intellij-spring-helm-env-hints

IntelliJ plugin that links Spring `application*.yml|yaml` configuration to the environment
variables declared in Helm chart templates, with highlighting and two-way navigation.

## What it does

| Feature | Behaviour |
| --- | --- |
| Highlighting | Env var occurrences are coloured differently depending on whether they exist on **both** sides (matched) or only one (unmatched). No tooltips are added. |
| Reference count tag | An inline `N refs` tag is shown next to every env var that resolves to at least one occurrence on the opposite side. Hover for the target `file:line` list, click to navigate. |
| Go to Declaration | <kbd>Ctrl</kbd>/<kbd>Cmd</kbd>+<kbd>B</kbd> jumps Spring → Helm and Helm → Spring. |
| Ctrl+Click / Rename | Spring and Helm occurrences are real PSI references, so click-navigation and *Refactor → Rename* work across files. |

## How matching works

**File classification** (by name/path only, no chart parsing):

- *Spring file* — name starts with `application` and ends in `.yaml`/`.yml`.
- *Helm template* — any `.yaml`/`.yml` whose path contains `/templates/`.

**Environment variable extraction:**

- Helm: the value of every `name:` key nested under both `containers` and `env`
  (i.e. `spec.containers[*].env[*].name`). Quotes are stripped.
- Spring: every `${NAME}` / `${NAME:default}` placeholder in a value.
- Spring (optional, off by default): property key paths converted to env var style —
  `my.service.url` → `MY_SERVICE_URL`.

**A var is "matched" only when it appears on the Helm side *and* the Spring side within the
same scope.** One symmetric match is computed per var per scope and drives both highlighting
and navigation in both directions.

**Scopes** default to a single IntelliJ module. Because a Gradle import splits one service
into `<service>`, `<service>.main`, `<service>.test` — with Helm templates under the parent
and `application.yaml` under a child — source-set modules are merged into their parent when
the name relationship (`<parent>.<sourceSet>`) *and* physical nesting both hold. Merging by
path alone is deliberately avoided: a root/aggregate module's content root contains every
sub-project and would collapse all services into one scope. The *Match env vars across all
project modules* setting replaces per-module scopes with a single project-wide scope.

Navigation targets located in the source file itself are filtered out, so a file that
qualifies as both a Spring file and a Helm template cannot self-resolve.

## Settings

<kbd>Settings</kbd> → <kbd>Other Settings</kbd> → <kbd>Spring Helm Env Hints</kbd>

- **Match Spring property keys to Helm env vars** — enables the key → env var conversion above.
- **Match env vars across all project modules** — project-wide instead of per-module scope.
- **Scan folders excluded in the project structure** — off by default. Excluded folders
  (`build`, `target`, `out`, …) usually only contain generated copies of resources, which would
  duplicate or falsely create matches; enable this if your YAML lives in such a folder.
- **Show "N refs" tag** — inline reference-count tag, on by default. It is a declarative inlay
  hint, so it can also be switched off under <kbd>Settings</kbd> → <kbd>Editor</kbd> →
  <kbd>Inlay Hints</kbd> → <kbd>Other</kbd>. The count is the number of *deduplicated*
  targets outside the current file — exactly what <kbd>Ctrl</kbd>/<kbd>Cmd</kbd>+<kbd>B</kbd>
  would offer — so an env var without a counterpart is never tagged.
- **Color Mode** — background highlight (with per-theme alpha) or font colour; separate light
  and dark theme colours for matched/unmatched plus the Spring reference underline.
- **Debug: Current Matches** — dumps the live index per scope as matched / Helm-only /
  Spring-only, which is the quickest way to diagnose why something is not highlighted.

Colours are application-level; the debug view is per-project. Applying settings bumps a
modification tracker that invalidates the cached index and restarts the code analyzer.

## Implementation notes

```
src/main/kotlin/.../navigation/
  EnvVarUtils.kt                file classification, YAML parsing, scope index, resolveEnvMatch()
  EnvVarAnnotator.kt            highlighting (settings-driven enforced text attributes)
  EnvRefCountInlayProvider.kt   inline "N refs" tag (declarative inlay hint) + its click handler
  YamlLikeMetaLanguage.kt       meta language the inlay provider is registered for
  EnvVarGotoHandler.kt          Ctrl+B, merging PSI references with index lookups
  EnvVarReferenceContributor.kt PSI references for Ctrl+Click and rename
src/main/kotlin/.../settings/   persistent state, Configurable, settings panel
src/main/resources/META-INF/plugin.xml
src/main/resources/messages/HelmEnvHintsBundle.properties   inlay provider name/description
```

- `resolveEnvMatch()` in `EnvVarUtils.kt` is the single matching routine; everything else is a
  thin wrapper. Change matching semantics there and both directions follow.
- The index is a `CachedValuesManager` value over the whole project, invalidated by
  `PsiModificationTracker.MODIFICATION_COUNT` and the settings tracker. It is therefore
  rebuilt after any PSI edit — fine for typical repos, but it is the first thing to look at
  if highlighting feels slow in a very large project.
- Helm templates owned by a template-language plugin (e.g. *Go Template*, or the Kubernetes
  plugin's `HelmYAML`) are read via `viewProvider.getPsi(YAMLLanguage)` rather than
  `PsiManager.findFile`, whose base language would not be YAML.
- For the same reason the inlay provider is registered against a `MetaLanguage`
  (`YamlLikeMetaLanguage`, id `SpringHelmEnvHintsYamlLike`) that matches YAML dialects **and**
  template languages: declarative inlay providers are selected by the file's *base* language
  only, so a `yaml`-registered provider is never instantiated for a `HelmYAML` file. It is
  also an `OwnBypassCollector`, walking the YAML root itself, because the platform only walks
  the base-language PSI root — and because a value-driven walk emits one duplicate tag per
  enclosing `YAMLMapping` level.
- Each YAML file is indexed once under its deepest owning module, which is what stops an
  aggregate module from indexing the whole repository.
- Content roots are walked with `VfsUtilCore.iterateChildrenRecursively`; unless *Scan folders
  excluded in the project structure* is on, a `VirtualFileFilter` backed by
  `ProjectFileIndex.isExcluded` rejects excluded files, which also prunes the whole sub-tree.
- Registered extensions: `annotator` (yaml), `psi.referenceContributor` (yaml),
  `gotoDeclarationHandler`, `metaLanguage`, `codeInsight.declarativeInlayProvider`
  (`SpringHelmEnvHintsYamlLike`) plus its `codeInsight.inlayActionHandler`,
  `applicationService`, `projectConfigurable`.
  *Find Usages* is **not** implemented.
- The reference-count tag reuses `resolveEnvMatch()` through `counterpartRefs()`, so it can
  never disagree with the highlight or with <kbd>Ctrl</kbd>/<kbd>Cmd</kbd>+<kbd>B</kbd>. It is
  anchored at `EnvSpan.tagOffset` (after the closing `}` of `${…}`, or after the Helm value's
  closing quote) so it never splits the highlighted text.

## Build and run

```bash
./gradlew buildPlugin   # → build/distributions/IntelliJ Spring Helm Env Hints-<version>.zip
./gradlew runIde        # sandbox IDE
```

Install a built ZIP via <kbd>Settings</kbd> → <kbd>Plugins</kbd> → gear icon →
<kbd>Install Plugin from Disk…</kbd>.

Platform target `2025.2.6.2`; depends on the bundled `org.jetbrains.plugins.yaml` plugin.

## License

[Apache License 2.0](LICENSE)

