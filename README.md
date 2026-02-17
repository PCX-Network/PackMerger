# PackMerger

A Paper plugin that merges multiple Minecraft resource packs into a single pack, uploads it to a configurable hosting provider, and automatically distributes it to players on join. Designed for servers and networks that need to combine packs from different sources into one seamless download.

## Features

- **Priority-based merging** — control which pack's files take precedence when packs overlap
- **Intelligent JSON merging** — deep merges model and blockstate JSON to preserve non-conflicting entries; concatenates sounds.json arrays so sounds from multiple packs coexist
- **Multiple upload providers** — Polymath server or built-in HTTP server
- **Hot reload** — watches the packs folder for changes and auto-merges with configurable debounce
- **Player cache tracking** — remembers which pack version each player has downloaded to skip redundant re-sends on rejoin
- **Per-server packs** — supports multi-server networks where each backend needs a different pack composition
- **Pack validation** — checks JSON syntax, pack.mcmeta structure, and missing texture/model references after every merge
- **Custom overrides** — drop a `pack.mcmeta` or `pack.png` in the packs folder to override the merged pack's metadata and icon
- **Concurrent download limiting** — built-in rate limiter for the self-host HTTP server
- **Brigadier commands** — full tab completion via Paper's command API

## Requirements

- **Paper 1.21+** (built against Paper API 1.21.4)
- **Java 21** or newer
- Tested on Paper and Folia-compatible forks

## Installation

1. Build the plugin (see [Building](#building)) or download the release jar
2. Place `PackMerger-1.0.1.jar` into your server's `plugins/` folder
3. Start the server — the plugin generates `config.yml` and creates the `packs/`, `output/`, and `cache/` directories under `plugins/PackMerger/`
4. Place your resource pack `.zip` files or unzipped pack folders into `plugins/PackMerger/packs/`
5. Edit `plugins/PackMerger/config.yml` to configure priority order, upload provider, and distribution settings
6. Run `/packmerger reload` or restart the server

## Building

```bash
./gradlew shadowJar
```

The shaded jar (with all dependencies bundled) is output to:

```
build/libs/PackMerger-1.0.1.jar
```

Requires Java 21 to compile.

## Configuration

The plugin generates a fully commented `config.yml` on first run. Below is a walkthrough of each section.

### Server Name

```yaml
server-name: ""
```

Identifies this server for per-server pack configs. If empty, auto-detects from `server.properties`. Only relevant for multi-server networks.

### Priority

```yaml
priority:
  - "main-pack.zip"
  - "ui-overhaul.zip"
  - "custom-models.zip"
```

Controls merge order. The first entry has the **highest priority** — its files win when two packs contain the same non-mergeable file (e.g. textures). Packs found on disk but not listed here are automatically merged at the lowest priority, sorted alphabetically by filename. Use numeric prefixes (e.g. `01_pack.zip`, `02_pack.zip`) to control their order without needing to list them in the config.

### Per-Server Packs

```yaml
server-packs:
  lobby:
    additional:
      - "lobby-textures.zip"
    exclude: []
  survival:
    additional:
      - "survival-textures.zip"
    exclude:
      - "ui-pack.zip"
```

Allows each server to include extra packs or exclude packs from the global list. Matched by the `server-name` value (case-insensitive).

### Merge Settings

```yaml
merge:
  auto-merge-on-startup: true    # Merge automatically when the plugin enables
  optimization:
    strip-junk-files: true       # Remove .DS_Store, Thumbs.db, __MACOSX, .git, etc.
    compression-level: 6         # ZIP compression (0-9, default 6)
    size-warning-mb: 100         # Warn if output exceeds this size (0 = disabled)
  hot-reload:
    enabled: true                # Watch packs/ folder for changes
    debounce-seconds: 5          # Wait this long after last change before merging
```

### Upload Provider

```yaml
upload:
  auto-upload: true              # Upload after every merge
  provider: "self-host"          # "self-host" or "polymath"
```

#### Self-Host (Default)

```yaml
upload:
  provider: "self-host"
  self-host:
    port: 8080
    public-url: ""     # Auto-detects from server.properties; set explicitly if behind NAT/proxy
    rate-limit: 50     # Max concurrent downloads (0 = unlimited)
```

The self-host provider starts a built-in HTTP server. Players download from `http://<your-ip>:<port>/pack`. Make sure the port is open in your firewall.

#### Polymath (Self-Hosted)

[Polymath](https://github.com/oraxen/polymath) is a lightweight Python web server originally created by the Oraxen/Nexo project for hosting Minecraft resource packs. You must self-host your own Polymath instance — the public Oraxen instance (`atlas.oraxen.com`) has been shut down.

```yaml
upload:
  provider: "polymath"
  polymath:
    server: "https://your-polymath-host:5000"
    secret: "your-custom-secret"
    id: "my-server"
```

To self-host Polymath, you need Python installed. See the [Polymath GitHub repo](https://github.com/oraxen/polymath) for setup instructions.

### Distribution

```yaml
distribution:
  enabled: true                    # Send pack to players on join
  required: false                  # Kick players who decline (true/false)
  prompt-message: ""               # Custom MiniMessage prompt (empty = Minecraft default)
  use-add-resource-pack: false     # true = add alongside existing packs; false = replace
  join-delay-ticks: 20             # Delay before sending (20 ticks = 1 second)
  cache:
    enabled: true                  # Skip re-sending to players who already have current pack
  on-new-pack:
    action: "notify"               # "none", "notify", or "resend"
    notify-message: "<yellow>[PackMerger]</yellow> <gray>A new resource pack is available. Rejoin or use F3+T to reload.</gray>"
```

### Debug

```yaml
debug: false
```

Enables verbose logging for troubleshooting. Logs every file merge, pack send, validation check, and file watcher event.

## Commands & Permissions

All commands require `packmerger.admin` permission (default: op).

| Command | Alias | Description |
|---|---|---|
| `/packmerger reload` | `/pm reload` | Reload config, re-initialize upload provider, and trigger a full merge-upload cycle |
| `/packmerger validate` | `/pm validate` | Run validation on the current merged pack (checks JSON syntax, missing textures/models) |
| `/packmerger status` | `/pm status` | Show plugin status: server name, provider, last merge time, pack URL, SHA-1 hash, file size |
| `/packmerger apply` | `/pm apply` | Force-send the current pack to all online players (bypasses cache) |
| `/packmerger apply <player>` | `/pm apply <player>` | Force-send the current pack to a specific player (supports selectors like `@a`) |

### Permissions

| Permission | Default | Description |
|---|---|---|
| `packmerger.admin` | op | Access to all `/packmerger` commands |
| `packmerger.notify` | true (all players) | Receive chat notifications when a new pack is available |

## How It Works

### Merge → Upload → Distribute Pipeline

1. **Discover** — scans `plugins/PackMerger/packs/` for `.zip` files and directories containing `pack.mcmeta` or `assets/`
2. **Order** — builds a merge order based on the priority config, per-server includes/excludes, and any unlisted packs
3. **Merge** — iterates packs from lowest to highest priority, collecting files into memory:
   - **Non-JSON files** (textures, sounds, shaders, etc.) use last-write-wins — higher priority overwrites lower
   - **Model and blockstate JSON** (`assets/<ns>/models/`, `assets/<ns>/blockstates/`) are deep merged — non-conflicting keys from both packs are preserved
   - **sounds.json** (`assets/<ns>/sounds.json`) sound arrays are concatenated so sounds from all packs coexist
4. **Override** — if `pack.mcmeta` or `pack.png` exists directly in the packs folder (not inside a pack), it replaces whatever the merge produced
5. **Validate** — the merged zip is checked for pack.mcmeta structure, JSON syntax errors, and missing texture/model references
6. **Upload** — the merged zip is sent to the configured provider (Polymath or self-host)
7. **Distribute** — if the pack's SHA-1 hash changed, online players are handled according to the `on-new-pack` action

### Priority Order & Conflict Resolution

Packs are listed highest-priority first in the config. When two packs contain the same file path:

- **Non-JSON files**: the higher-priority pack's version is used
- **Mergeable JSON** (models, blockstates): keys are deep-merged recursively — conflicting keys use the higher-priority value, non-conflicting keys from both are preserved
- **sounds.json**: sound arrays for the same event are concatenated (higher-priority sounds listed first)

### Per-Server Packs

On a multi-server network, each backend server can have its own merged pack by defining `server-packs` entries in the config. Each server's `additional` packs are merged at lowest priority (below the global list), and `exclude` packs are skipped entirely. The output file is named `<server-name>-merged-pack.zip` to avoid conflicts.

### Hot Reload

When enabled, a background thread watches the `packs/` folder for file system events (create, modify, delete). To avoid excessive re-merges during bulk operations, events are **debounced** — the merge only triggers after no new events have arrived for the configured debounce period (default: 5 seconds).

### Player Cache Tracking

After a player successfully loads a resource pack, the plugin records the pack's SHA-1 hash against their UUID in `plugins/PackMerger/cache/player-cache.json`. On subsequent joins, if the player's cached hash matches the current pack hash, the download is skipped entirely. The cache is saved to disk every 5 minutes and on plugin disable.

## Troubleshooting

### Merged pack not sending to players

- Check that `distribution.enabled` is `true` in config
- Verify a merge has completed: run `/pm status` and check that Pack URL is not "N/A"
- Check the console for upload errors — if the upload failed, no URL is available to send
- Ensure `upload.auto-upload` is `true`

### Self-host port already in use

- Another process is using the configured port (default 8080)
- Change `upload.self-host.port` to an unused port
- Check with `netstat -tlnp | grep 8080` (Linux) or `netstat -an | findstr 8080` (Windows) to find the conflicting process

### Pack validation warnings

- **"Invalid JSON"** — a JSON file has syntax errors. Open it in a JSON validator to find the issue
- **"Missing texture"** — a model references a texture path that doesn't exist in the merged pack. The texture may be in a pack that was excluded or not added
- **"Missing model"** — a blockstate references a model that doesn't exist. Same cause as missing textures
- Validation warnings don't prevent the pack from being sent — they indicate potential visual issues in-game

### Hot reload not detecting changes

- Check that `merge.hot-reload.enabled` is `true`
- The file watcher only monitors the top-level `packs/` directory — changes inside subdirectories of an unzipped pack may not be detected
- For unzipped packs, try re-saving the pack's root directory or touching a file in the `packs/` folder
- Check the console for "File watcher key invalidated" — this means the packs directory was deleted or moved while the watcher was running

### Players re-downloading the pack every join (cache issue)

- Ensure `distribution.cache.enabled` is `true`
- The cache only updates after the client reports `SUCCESSFULLY_LOADED` — if the client reports a different status, no cache entry is saved
- If using `use-add-resource-pack: true`, Minecraft may handle caching differently than the replace mode
- Delete `plugins/PackMerger/cache/player-cache.json` to reset the cache if it becomes corrupted
- Enable `debug: true` to see detailed pack send/skip logs per player

### Pack too large / download timeouts

- Check the output file size with `/pm status`
- Increase `merge.optimization.compression-level` (up to 9) for smaller files
- Remove unnecessary packs or textures from the packs folder
- For very large packs (200+ MB), consider using a CDN or Polymath for faster downloads

## License

This project is licensed under the [MIT License](LICENSE).
