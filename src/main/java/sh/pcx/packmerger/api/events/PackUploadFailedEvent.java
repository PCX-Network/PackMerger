package sh.pcx.packmerger.api.events;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fired asynchronously when the configured upload provider throws while
 * attempting to upload the merged pack.
 *
 * <p>Listeners can use this to page on-call, open an incident ticket, or
 * simply log the failure to an external observability stack.</p>
 */
public class PackUploadFailedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Throwable cause;

    public PackUploadFailedEvent(Throwable cause) {
        super(true);
        this.cause = cause;
    }

    /** @return the exception that aborted the upload; never {@code null} */
    public Throwable getCause() { return cause; }

    @Override
    public HandlerList getHandlers() { return HANDLERS; }

    public static HandlerList getHandlerList() { return HANDLERS; }
}
