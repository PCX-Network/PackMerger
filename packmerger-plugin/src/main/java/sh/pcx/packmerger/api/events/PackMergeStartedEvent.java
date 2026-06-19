package sh.pcx.packmerger.api.events;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.List;

/**
 * Fired asynchronously on the merge thread when a merge pipeline starts,
 * before any pack files are read.
 *
 * <p>Listeners receive the merge order (highest priority first) that the
 * engine resolved from config + per-server include/exclude rules. This event
 * is purely informational — there is no way to modify the pack order from a
 * listener.</p>
 */
public class PackMergeStartedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final List<String> packOrder;

    public PackMergeStartedEvent(List<String> packOrder) {
        super(true); // asynchronous
        this.packOrder = List.copyOf(packOrder);
    }

    /** @return the resolved pack order (highest priority first) */
    public List<String> getPackOrder() {
        return packOrder;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
