package sh.pcx.packmerger.config;

import java.util.List;
import java.util.Map;

/**
 * Atomically-switchable bundle of priority + per-server pack configurations.
 *
 * <p>Profiles let operators flip between whole pack compositions (default,
 * halloween, event-x) with a single {@code /pm profile switch} rather than
 * hand-editing the priority list and restoring it later.</p>
 *
 * <p>When the active profile is defined, its {@link #priority} and
 * {@link #serverPacks} replace the root-level {@code priority:} and
 * {@code server-packs:} sections. When no profile is active (or the
 * {@code profiles:} section is absent entirely), PackMerger uses the root-
 * level keys for full backwards compatibility with pre-1.1.0 configs.</p>
 *
 * @param name        the profile key from {@code profiles:} (e.g. "default", "halloween")
 * @param priority    ordered list of pack filenames; first = highest priority
 * @param serverPacks per-server overrides keyed by lowercase server name
 */
public record ProfileConfig(
        String name,
        List<String> priority,
        Map<String, ConfigManager.ServerPackConfig> serverPacks) {

    public ProfileConfig {
        priority = List.copyOf(priority);
        serverPacks = Map.copyOf(serverPacks);
    }
}
