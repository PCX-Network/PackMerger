package sh.pcx.packmerger.api.events;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import sh.pcx.packmerger.merge.PackValidator;

/**
 * Fired asynchronously when a merge produces a pack that fails validation
 * (validation errors, not warnings).
 *
 * <p>The {@link #wasRolledBack()} flag tells listeners whether PackMerger
 * preserved the previous merged pack (keeping players on the old, working
 * version) or let the broken pack through anyway (typically only on the very
 * first merge when no previous output exists).</p>
 */
public class PackValidationFailedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final PackValidator.ValidationResult result;
    private final boolean wasRolledBack;

    public PackValidationFailedEvent(PackValidator.ValidationResult result, boolean wasRolledBack) {
        super(true);
        this.result = result;
        this.wasRolledBack = wasRolledBack;
    }

    /** @return the validation result listing the errors that fired this event */
    public PackValidator.ValidationResult getResult() { return result; }

    /**
     * @return {@code true} if PackMerger reverted to the previous merged pack
     *         (no player-visible change), {@code false} if the broken pack was
     *         shipped anyway
     */
    public boolean wasRolledBack() { return wasRolledBack; }

    @Override
    public HandlerList getHandlers() { return HANDLERS; }

    public static HandlerList getHandlerList() { return HANDLERS; }
}
