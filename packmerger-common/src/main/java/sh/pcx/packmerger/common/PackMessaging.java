package sh.pcx.packmerger.common;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Wire format for the backend → proxy "current pack" handoff.
 *
 * <p>The backend (Bukkit) plugin sends a {@link PackInfo} on the
 * {@link #CHANNEL} plugin-messaging channel whenever it uploads a new pack; the
 * proxy (Velocity) caches it and offers it to joining players. Both sides share
 * this codec so the format can't drift.</p>
 */
public final class PackMessaging {

    /** Namespaced plugin-messaging channel (lowercase, as Bukkit/Velocity require). */
    public static final String CHANNEL = "packmerger:pack";

    private PackMessaging() {}

    /** Serializes a {@link PackInfo} to the channel payload. */
    public static byte[] encode(PackInfo info) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(baos)) {
            out.writeUTF(info.url() == null ? "" : info.url());
            out.writeUTF(info.sha1Hex() == null ? "" : info.sha1Hex());
        } catch (IOException e) {
            throw new UncheckedIOException(e); // ByteArrayOutputStream never throws
        }
        return baos.toByteArray();
    }

    /** Parses a channel payload back into a {@link PackInfo}. */
    public static PackInfo decode(byte[] bytes) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            String url = in.readUTF();
            String hash = in.readUTF();
            return new PackInfo(url.isEmpty() ? null : url, hash.isEmpty() ? null : hash);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
