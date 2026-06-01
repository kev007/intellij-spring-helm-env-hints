# intellij-spring-helm-env-hints

IntelliJ plugin that links Spring `application.yaml` properties and Helm container environment variables so you can navigate between both sides.

## What it does

- `Go to Declaration` / `Go to Usage` style navigation between:
  - Spring keys in `application*.yml` or `application*.yaml`
  - Helm env vars (`name: MY_ENV_VAR`) in files under `/templates/`
- Bidirectional mapping support:
  - `my.service.url` -> `MY_SERVICE_URL`
  - `MY_SERVICE_URL` -> `my.service.url`
- PSI references for YAML content to improve IDE navigation behavior.

## Implemented features

- `GotoDeclarationHandler` integration for mapped variables.
- `PsiReferenceContributor` for YAML-based cross-file references.
- `ReferencesSearch` query executor to support cross-name usage lookups.
- Tests covering both directions:
  - Spring -> Helm
  - Helm -> Spring

## Current matching rules

- Spring files are detected by filename starting with `application` and extension `.yml` or `.yaml`.
- Helm files are detected by path containing `/templates/` and extension `.yml` or `.yaml`.
- Helm env vars are matched on lines like:
  - `name: MY_ENV_VAR`
  - `- name: MY_ENV_VAR`
- Spring key normalization:
  - Non-alphanumeric characters become `_`
  - Repeated `_` are collapsed
  - Result is uppercased

## Project structure (key files)

- `src/main/kotlin/com/github/kev007/intellijspringhelmenvhints/navigation/EnvVarMappingSupport.kt`
- `src/main/kotlin/com/github/kev007/intellijspringhelmenvhints/navigation/EnvVarMappingGotoDeclarationHandler.kt`
- `src/main/kotlin/com/github/kev007/intellijspringhelmenvhints/navigation/EnvVarMappingReferenceContributor.kt`
- `src/main/kotlin/com/github/kev007/intellijspringhelmenvhints/navigation/EnvVarMappingReferencesSearch.kt`
- `src/main/resources/META-INF/plugin.xml`

## Packaging

Build the installable plugin ZIP:

```powershell
Set-Location "C:/workspace/intellij-spring-helm-env-hints"
./gradlew.bat buildPlugin
```

Generated artifact:

- `build/distributions/IntelliJ Platform Plugin Template-0.0.1.zip`

## Installation

Install the ZIP in IntelliJ IDEA:

1. Open <kbd>Settings/Preferences</kbd> -> <kbd>Plugins</kbd>
2. Click the gear icon -> <kbd>Install Plugin from Disk...</kbd>
3. Select `build/distributions/IntelliJ Platform Plugin Template-0.0.1.zip`
4. Restart the IDE when prompted

## Local development

Run focused feature tests:

```powershell
Set-Location "C:/workspace/intellij-spring-helm-env-hints"
./gradlew.bat test --tests "*EnvVarMappingGotoDeclarationHandlerTest" --tests "*EnvVarMappingReferenceContributorTest"
```

Run all tests:

```powershell
Set-Location "C:/workspace/intellij-spring-helm-env-hints"
./gradlew.bat test
```

Run the plugin in sandbox IDE:

```powershell
Set-Location "C:/workspace/intellij-spring-helm-env-hints"
./gradlew.bat runIde
```

## Known limitations

- Mapping currently focuses on direct env var names in Helm template YAML.
- Heavily templated env names (complex `{{ ... }}` composition) are not fully resolved.
- YAML parsing for Spring key detection uses lightweight line/indent parsing.

## Notes

This repository started from the IntelliJ Platform Plugin Template and is now adapted for Spring/Helm variable navigation.
