package sh.pcx.packmerger.upload;

import java.io.File;

/**
 * Interface for resource pack upload providers.
 *
 * <p>Implementations handle the transport of a merged resource pack zip to a location
 * where players can download it. Each provider returns a public URL that is then used
 * by {@link sh.pcx.packmerger.distribution.PackDistributor} to send the pack to players
 * via Minecraft's resource pack protocol.</p>
 *
 * <p>Available implementations:</p>
 * <ul>
 *   <li>{@link S3UploadProvider} — uploads to S3-compatible storage (AWS S3, Cloudflare R2, MinIO)</li>
 *   <li>{@link SFTPUploadProvider} — uploads via SFTP to a remote web server</li>
 *   <li>{@link SelfHostProvider} — serves the pack from a built-in HTTP server</li>
 *   <li>{@link PolymathUploadProvider} — uploads to a Polymath server (public Oraxen instance or self-hosted)</li>
 * </ul>
 *
 * <p>The provider is selected by the {@code upload.provider} config setting and
 * initialized in {@link sh.pcx.packmerger.PackMerger#initUploadProvider()}.</p>
 *
 * @see sh.pcx.packmerger.PackMerger#initUploadProvider()
 */
public interface UploadProvider {

    /**
     * Uploads the given file and returns the public download URL.
     *
     * <p>Implementations may use the SHA-1 hash in the upload path for cache-busting
     * or versioning. The returned URL must be directly accessible by Minecraft clients
     * (no authentication required).</p>
     *
     * @param file    the merged resource pack zip file to upload; guaranteed to exist and be readable
     * @param sha1Hex the lowercase hex-encoded SHA-1 hash of the file (40 characters)
     * @return the public download URL that players will use to fetch the pack
     * @throws Exception if the upload fails for any reason (network, auth, disk, etc.)
     */
    String upload(File file, String sha1Hex) throws Exception;
}
