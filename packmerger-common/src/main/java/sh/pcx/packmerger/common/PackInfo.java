package sh.pcx.packmerger.common;

/**
 * The minimal pack-distribution contract shared between the backend (Bukkit/Paper)
 * plugin and the proxy (Velocity) plugin: where the merged pack lives and its hash.
 *
 * @param url     the public download URL of the merged pack
 * @param sha1Hex the hex-encoded SHA-1 of the pack, or {@code null}/blank if unknown
 */
public record PackInfo(String url, String sha1Hex) {

    public boolean hasUrl() {
        return url != null && !url.isBlank();
    }

    public boolean hasHash() {
        return sha1Hex != null && !sha1Hex.isBlank();
    }
}
