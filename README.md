# intellij-spring-helm-env-hints

IntelliJ plugin for navigating between Spring `application*.yml|yaml` configuration and Helm template env vars under `templates/`.

## Current implementation (as of 2026-06-03)

- Bidirectional YAML navigation between:
  - Spring keys and env vars derived from those keys
  - Spring `${ENV_VAR[:default]}` value references
  - Helm `spec.containers[*].env[*].name` entries
- `Go to Declaration` support via `EnvVarMappingGotoDeclarationHandler`.
- PSI reference support via `EnvVarMappingReferenceContributor`.
- `Find Usages` integration via `EnvVarMappingReferencesSearch` (synthetic soft references).
- Inline YAML annotations via `EnvVarMappingAnnotator`:
  - Matched mappings are underlined with informational tooltips.
  - Missing mappings are marked as errors with explanatory tooltips.

## What navigation currently resolves

- Spring -> Helm
  - From a Spring property key (for example `my.service.url` -> `MY_SERVICE_URL`).
  - From a Spring env placeholder in a value (for example `${POSTGRES_HOST:localhost}`).
- Helm -> Spring
  - From Helm `name: SOME_ENV_VAR` entries under `spec.containers[*].env[*]`.
- `Find Usages`
  - Uses cross-mapping logic to return mapped elements as usage references.

## Plugin wiring

- Extension registrations are in `src/main/resources/META-INF/plugin.xml`:
  - `gotoDeclarationHandler`: `EnvVarMappingGotoDeclarationHandler`
  - `psi.referenceContributor` (yaml): `EnvVarMappingReferenceContributor`
  - `annotator` (yaml): `EnvVarMappingAnnotator`
  - `referencesSearch`: `EnvVarMappingReferencesSearch`


## Build and run

```powershell
Set-Location "C:/workspace/intellij-spring-helm-env-hints"
./gradlew.bat buildPlugin
./gradlew.bat runIde
```

Current ZIP artifact name in this repo:

- `build/distributions/IntelliJ Platform Plugin Template-0.0.1.zip`

## Install in IntelliJ IDEA

1. Open <kbd>Settings/Preferences</kbd> -> <kbd>Plugins</kbd>
2. Click the gear icon -> <kbd>Install Plugin from Disk...</kbd>
3. Select `build/distributions/IntelliJ Platform Plugin Template-0.0.1.zip`
4. Restart IDE when prompted

## Development notes

- IntelliJ platform target in Gradle: `2025.2.6.2`.
- YAML plugin dependency: `org.jetbrains.plugins.yaml` (bundled).
- This repository still carries template defaults (for example root project name/artifact naming).