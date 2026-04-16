package sh.pcx.packmerger.merge.strategy;

import java.util.function.Consumer;

/**
 * Per-call context passed to {@link MergeStrategy#merge} so strategies can emit
 * path-scoped warnings without holding engine references.
 *
 * <p>Uses a {@link Consumer} sink rather than a concrete logger type so tests
 * can capture warnings without pulling the Bukkit/Adventure runtime onto the
 * test classpath.</p>
 *
 * @param path        the normalized pack-relative path of the file being merged
 * @param warningSink where warnings are delivered; may be {@code null} to discard
 */
public record MergeContext(String path, Consumer<String> warningSink) {

    /**
     * Emits a warning through the attached sink, or silently drops it if the
     * sink is absent (e.g. in unit tests that don't assert on log output).
     */
    public void warn(String message) {
        if (warningSink != null) warningSink.accept(message);
    }
}
