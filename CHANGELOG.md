# Changelog

All notable changes to PackMerger are documented here. The format is loosely
based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the
project follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.1.0] — 2026-04-16

Operator-experience release. Correctness of the merge engine is settled from
1.0.x; this one focuses on observability, validation, admin workflow, and
external integrations.

### Added

- **Per-file merge provenance log.** Every output path now records which pack
  wrote it, every contributor (in merge order), the merge strategy used, and
  whether it was a true merge vs single-contributor pass-through. Persisted
  to `output/<merged-pack>.zip.provenance.json` so restarts don't blank the
  state. Exposed via `PackMerger.getLastMergeProvenance()`.
- **`/pm inspect` command family.**
  - `/pm inspect` — top-line summary of the last merge
  - `/pm inspect <pack>` — per-pack detail (files won + files overridden)
  - `/pm inspect collisions` — list of every path touched by 2+ packs
  - `/pm inspect export` — writes the full plain-text report to
    `output/last-merge-report.txt`
- **Plugin API + Bukkit events.** New `sh.pcx.packmerger.api.PackMergerApi`
  interface accessible via `plugin.getApi()` with accessors for the current
  pack URL, SHA-1, last merge time, and provenance, plus `triggerMerge()`.
  Six new Bukkit events fire at their natural call sites:
  `PackMergeStartedEvent`, `PackMergedEvent`, `PackUploadedEvent`,
  `PackUploadFailedEvent`, `PackValidationFailedEvent`,
  `PackSentToPlayerEvent`. See `docs/api-example.java` for a sample listener.
  Tagged `@Experimental` until 1.2 to allow iteration in 1.1.x.
- **`pack_format` vs server-version guardrail.** PackValidator now warns
  when the merged pack's `pack_format` doesn't match the running Minecraft
  version. Honors `supported_formats` ranges (int, array, and
  `{min_inclusive, max_inclusive}` object shapes). Config key:
  `validation.pack-format-check` (`warn` | `error` | `off`).
- **Rollback on validation failure.** Merges now write to
  `<output>.new.zip` first; validation runs against the temp, and the
  previous output is kept live if validation errors trip. Fires
  `PackValidationFailedEvent` with `rolledBack=true` so listeners can
  page someone. Config: `validation.rollback-on-errors` (default `true`)
  and `validation.fail-on-warnings` (default `false`).
- **Orphan asset detection.** New `OrphanDetector` scans the merged output
  for unreferenced `.png` and `.ogg` files and reports them as warnings.
  Reference discovery covers models, item definitions, blockstates, atlases
  (with directory glob expansion), font providers, and `sounds.json`. Config:
  `validation.detect-orphans` (default `true`) and
  `validation.orphan-report-limit` (default `20`).
- **`/pm priority` in-game reordering.** No more YAML edit + reload:
  `/pm priority list|up|down|top|bottom|set <pack> <n>`. Persists via
  `plugin.saveConfig()` and triggers an immediate re-merge. Note: Bukkit's
  config writer doesn't preserve comments on save — documented tradeoff.
- **Profiles / presets.** New `profiles:` + `active-profile:` config keys
  let operators flip between whole pack compositions atomically via
  `/pm profile switch <name>`. When profiles aren't in use (or the section
  is absent), PackMerger falls back to root-level keys — fully backwards-
  compatible with 1.0.x configs.
- **Remote pack sources (HTTP).** New `remote-packs:` config section lets
  admins declare pack aliases whose contents come from an HTTP(S) URL.
  Packs are downloaded into `packs/.remote-cache/<alias>.zip` and
  recognized in the merge pipeline by their alias. ETag / Last-Modified
  caching, env-var substitution for secrets, bearer + basic auth, HTTPS-
  required-by-default. New `/pm fetch [alias]` command. Zero new runtime
  deps — uses the JDK's `HttpClient`.
- **S3-compatible upload provider.** New `provider: "s3"` setting with
  support for AWS S3, Cloudflare R2, and Backblaze B2 (all via the S3
  API). Bundled MinIO SDK, fully shaded + relocated. Supports
  content-addressed or stable key strategies, public-read or presigned
  (private) ACLs, and a retention policy for content-addressed buckets.
  Jar grows from ~270 KB to ~13 MB as a result; document in release notes.
- **Two new `PluginLogger` categories:** `validation()` (light purple) and
  `remote()` (blue).
- **Runtime dependency loader.** New `PackMergerLoader` downloads MinIO and
  its transitive closure from Maven Central on first enable, verifies each
  artifact against a build-time SHA-256, and loads them through an isolated
  classloader. Cached in `plugins/PackMerger/libraries/` for subsequent
  starts. Drops the shipped jar from ~13 MB to ~360 KB.
- **Update check.** On enable, the plugin polls `versions.json` in the repo
  to see if a newer release is available and surfaces it in the console +
  as a chat notice to admins on join. Advisory only — no auto-download.
  Config: `update-check.enabled` / `update-check.url`.
- **Actual Folia support.** Swapped every `BukkitScheduler` call to the
  right Folia scheduler (AsyncScheduler for periodic async work,
  GlobalRegionScheduler / entity scheduler where a specific thread matters).
  `PackDistributor.sendPack` now self-schedules on the player's region so
  callers don't have to know about threading. `plugin.yml` declares
  `folia-supported: true`. Paper behavior is unchanged — the scheduler
  APIs we use exist on both.

### Changed

- `PackMergeEngine.merge()` now accepts an optional target-file override
  (`merge(File)`), used by `PackMerger` for the write-to-temp-then-validate
  flow. Backwards-compatible: `merge()` keeps writing to
  `plugin.getOutputFile()`.
- Merge provenance moved from a single fixed-name file
  (`output/.merge-provenance.json`) to a sidecar keyed by output name
  (`output/<merged-pack>.zip.provenance.json`). Lets rollback rename the
  zip and sidecar together.
- `FileWatcher` now explicitly ignores dot-prefixed entries so the
  `.remote-cache/` subdirectory can't trigger cascading merges.

### Dependencies

- MinIO Java SDK 8.5.10 + its transitive closure (OkHttp, Okio, Kotlin
  stdlib, Jackson, BouncyCastle, Guava, Xerial Snappy) is now downloaded
  at runtime by the loader rather than shaded into the plugin jar.
  Manifest and SHA-256 digests live in `RuntimeDependencies.java`
  (auto-generated at build time from the `runtimeDownload` Gradle
  configuration).

## [1.0.5] — 2026-04-16

### Fixed

- **Language file merging.** `assets/<ns>/lang/*.json` translations now
  key-union across packs instead of last-write-wins, so stacking multiple
  plugins that localize messages into `en_us.json` no longer silently drops
  one side's strings.
- **`pack.mcmeta` `filter.block[]` preservation.** Filter entries (used
  to hide specific vanilla assets) are now concat-deduped by
  `namespace|path` across packs, mirroring the 1.0.4 `overlays.entries`
  fix.
- **CustomModelData collision warnings.** When two packs claim the same
  `custom_model_data` predicate on an item model, a warning is now logged
  at merge time with the file path and predicate — no more silent
  "my knife turned into a pistol" bug reports.

## [1.0.4] — 2026-04-16

### Fixed

- **`pack.mcmeta` overlays preservation.** `overlays.entries[]` declarations
  (used by QualityArmory and other packs for version-specific asset
  directories) are now concat-deduped by `directory` instead of being
  dropped when a higher-priority pack's `pack.mcmeta` wins.

## [1.0.3] — 2026-04-16

### Added

- **Path-specific merge strategies.** The JSON merger is split into
  dedicated strategies per Minecraft format: models, blockstates, atlases,
  item definitions, equipment, sounds, font, pack.mcmeta.
- **QualityArmory compatibility.** Item-model `overrides` arrays from
  multiple packs now concat-dedup by predicate instead of being replaced,
  so CustomModelData entries survive.
- **1.21.4 item definitions.** `assets/<ns>/items/*.json` files are now
  recognised and deep-merged.
- **1.21.2+ equipment models.** `assets/<ns>/equipment/*.json` deep-merged
  per slot.
- **1.19.3+ texture atlases.** `sources` arrays concatenated with
  structural dedup instead of replaced.
- **Font providers.** `assets/<ns>/font/*.json` `providers` arrays now
  concat so custom chat-icon packs compose cleanly.
- Comprehensive strategy-level test suite.

## [1.0.2] — earlier

- Fix `saveResource` warning when `messages_en.yml` already exists.

## [1.0.1] — earlier

- Externalize player-facing messages into configurable `messages_en.yml`.
- Add `PluginLogger` with colored categories and configurable log levels.
