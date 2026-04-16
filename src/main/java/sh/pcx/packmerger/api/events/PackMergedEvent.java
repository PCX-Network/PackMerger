package sh.pcx.packmerger.api.events;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import sh.pcx.packmerger.merge.MergeProvenance;
import sh.pcx.packmerger.merge.PackValidator;

import java.io.File;

/**
 * Fired asynchronously on the merge thread after a merge has produced an
 * output zip and been validated, but <em>before</em> the pack is uploaded.
 *
 * <p>Use this for custom inspection, diagnostics, or to kick off
 * side-effects that should complete before players are notified of a new
 * pack (the upload + distribute steps follow this event).</p>
 */
public class PackMergedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final File outputFile;
    private final byte[] sha1;
    private final MergeProvenance provenance;
    private final PackValidator.ValidationResult validationResult;

    public PackMergedEvent(File outputFile, byte[] sha1, MergeProvenance provenance,
                           PackValidator.ValidationResult validationResult) {
        super(true);
        this.outputFile = outputFile;
        this.sha1 = sha1 == null ? null : sha1.clone();
        this.provenance = provenance;
        this.validationResult = validationResult;
    }

    /** @return the merged pack zip written to {@code plugins/PackMerger/output/} */
    public File getOutputFile() { return outputFile; }

    /** @return a defensive copy of the raw SHA-1 bytes (20 bytes) */
    public byte[] getSha1() { return sha1 == null ? null : sha1.clone(); }

    /** @return per-file merge provenance (winner, contributors, strategy) */
    public MergeProvenance getProvenance() { return provenance; }

    /** @return the validation result produced for this output; may have warnings or errors */
    public PackValidator.ValidationResult getValidationResult() { return validationResult; }

    @Override
    public HandlerList getHandlers() { return HANDLERS; }

    public static HandlerList getHandlerList() { return HANDLERS; }
}
