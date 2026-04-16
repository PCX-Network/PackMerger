package sh.pcx.packmerger.remote;

/**
 * Declarative configuration for one remote pack source.
 *
 * <p>The {@link #alias} is what operators reference in {@code priority:} and
 * {@code server-packs:} lists — the plugin downloads the pack to
 * {@code packs/.remote-cache/<alias>.zip} and treats {@code <alias>} as the
 * pack name during discovery.</p>
 *
 * @param alias     short identifier referenced in priority lists
 * @param url       HTTP(S) URL to fetch the pack zip from
 * @param refresh   one of {@code "on-startup"}, {@code "on-reload"},
 *                  {@code "manual"}. Controls when {@link RemotePackManager}
 *                  will re-download this pack.
 * @param auth      optional authentication spec; may be {@code null} for public
 *                  resources
 * @param allowHttp if {@code false} (default) rejects non-HTTPS URLs; operators
 *                  opt in for internal artifact stores
 */
public record RemoteSpec(
        String alias,
        String url,
        String refresh,
        AuthSpec auth,
        boolean allowHttp) {

    /**
     * Authentication material for a remote fetch.
     *
     * @param type     {@code "none"}, {@code "bearer"}, or {@code "basic"}
     * @param token    bearer token (used when {@code type == "bearer"})
     * @param username basic-auth username
     * @param password basic-auth password
     */
    public record AuthSpec(String type, String token, String username, String password) {

        public static final AuthSpec NONE = new AuthSpec("none", null, null, null);

        public boolean isNone() {
            return type == null || type.equalsIgnoreCase("none");
        }
    }

    public boolean shouldFetchOnStartup() { return "on-startup".equalsIgnoreCase(refresh); }
    public boolean shouldFetchOnReload()  { return "on-reload".equalsIgnoreCase(refresh); }
}
