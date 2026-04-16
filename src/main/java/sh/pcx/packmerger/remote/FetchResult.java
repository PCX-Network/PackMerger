package sh.pcx.packmerger.remote;

/**
 * Outcome of a single remote pack fetch.
 *
 * @param alias  the pack alias that was fetched
 * @param status what happened (see {@link Status})
 * @param detail a short human-readable diagnostic (bytes fetched, error
 *               message, cache-hit notice, etc.) — goes straight to logs and
 *               {@code /pm fetch} output
 */
public record FetchResult(String alias, Status status, String detail) {

    public enum Status {
        /** Successfully downloaded new bytes. */
        FETCHED,
        /** Origin returned 304; existing cache bytes kept. */
        NOT_MODIFIED,
        /** Fetch failed but cached bytes are still usable. */
        ERROR_USING_CACHE,
        /** Fetch failed and there was no prior cache. */
        ERROR_NO_CACHE,
        /** Fetch was requested but the spec's refresh policy excluded it. */
        SKIPPED_BY_POLICY
    }

    public boolean isSuccess() { return status == Status.FETCHED || status == Status.NOT_MODIFIED; }
    public boolean isFailure() { return status == Status.ERROR_USING_CACHE || status == Status.ERROR_NO_CACHE; }
}
