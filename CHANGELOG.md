<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# intellij-spring-helm-env-hints Changelog

## [1.2.0]
### Added
- Dedicated *Refactor → Rename* handler for env vars: renames every Spring `${ENV_VAR}`
  placeholder and every Helm `env[*].name` entry of the var in the matching scope at once,
  as a single undoable, multi-file command.

### Changed

### Fixed
- Rename did nothing. Two independent causes: the PSI reference provider only ran for leaf
  elements, while YAML asks for contributed references on `YAMLScalar` only (so no reference
  was ever created), and the rename targets are plain scalars, which the platform's default
  rename rejects because they are not `PsiNamedElement`s.
- References are now contributed per `${...}` placeholder, so a scalar holding several
  placeholders gets one reference each instead of only the first one.

