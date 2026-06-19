package sh.pcx.packmerger.api.events;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fired asynchronously after the merged pack has been successfully uploaded
 * to the configured provider and a public URL is known.
 *
 * <p>Distinct from {@link PackMergedEvent} in that the pack is now
 * reachable from clients — use this for "post a URL to Discord" style
 * integrations that need the final distribution URL.</p>
 */
public class PackUploadedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final String url;
    private final byte[] sha1;

    public PackUploadedEvent(String url, byte[] sha1) {
        super(true);
        this.url = url;
        this.sha1 = sha1 == null ? null : sha1.clone();
    }

    /** @return the public download URL */
    public String getUrl() { return url; }

    /** @return a defensive copy of the raw SHA-1 bytes (20 bytes) */
    public byte[] getSha1() { return sha1 == null ? null : sha1.clone(); }

    @Override
    public HandlerList getHandlers() { return HANDLERS; }

    public static HandlerList getHandlerList() { return HANDLERS; }
}
