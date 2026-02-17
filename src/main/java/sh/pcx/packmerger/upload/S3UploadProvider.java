package sh.pcx.packmerger.upload;

import sh.pcx.packmerger.PackMerger;
import sh.pcx.packmerger.config.ConfigManager;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.File;
import java.net.URI;

/**
 * Upload provider that stores the merged resource pack in S3-compatible object storage.
 *
 * <p>Supports any S3-compatible service including AWS S3, Cloudflare R2, MinIO, and
 * DigitalOcean Spaces. The pack is uploaded as a single object using the AWS SDK v2
 * synchronous client.</p>
 *
 * <p>For services like Cloudflare R2 that don't use standard AWS endpoints, the
 * {@code endpoint} config field overrides the SDK's default endpoint resolution,
 * and {@code forcePathStyle} is enabled to ensure compatibility with non-AWS
 * virtual-host routing.</p>
 *
 * <p>The returned download URL is constructed from the configured {@code public-url}
 * base and the {@code object-key}, making it easy to use a CDN domain in front of
 * the storage bucket.</p>
 *
 * @see UploadProvider
 * @see ConfigManager
 */
public class S3UploadProvider implements UploadProvider {

    /** Reference to the owning plugin for config access and logging. */
    private final PackMerger plugin;

    /**
     * Creates a new S3 upload provider.
     *
     * @param plugin the owning PackMerger plugin
     */
    public S3UploadProvider(PackMerger plugin) {
        this.plugin = plugin;
    }

    /**
     * Uploads the merged pack to S3-compatible storage and returns the public download URL.
     *
     * <p>The S3 client is created fresh for each upload and closed immediately after to
     * avoid holding long-lived connections. The object is uploaded with
     * {@code Content-Type: application/zip}.</p>
     *
     * @param file    the merged pack zip file to upload
     * @param sha1Hex the SHA-1 hash (not currently used in the S3 key, but available for
     *                future cache-busting)
     * @return the public download URL (e.g. {@code https://cdn.example.com/packs/merged-pack.zip})
     * @throws Exception if the S3 upload fails (auth, network, permissions, etc.)
     */
    @Override
    public String upload(File file, String sha1Hex) throws Exception {
        ConfigManager config = plugin.getConfigManager();

        var builder = S3Client.builder()
                .region(Region.of(config.getS3Region()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(config.getS3AccessKey(), config.getS3SecretKey())
                ));

        // Override the endpoint for non-AWS S3-compatible services (R2, MinIO, etc.)
        String endpoint = config.getS3Endpoint();
        if (endpoint != null && !endpoint.isEmpty()) {
            builder.endpointOverride(URI.create(endpoint));
            // Path-style access is required for most S3-compatible services that don't
            // support virtual-hosted bucket addressing
            builder.forcePathStyle(true);
        }

        // Build, upload, and close the client in a try-with-resources
        try (S3Client s3 = builder.build()) {
            String objectKey = config.getS3ObjectKey();

            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(config.getS3Bucket())
                    .key(objectKey)
                    .contentType("application/zip")
                    .build();

            s3.putObject(putRequest, RequestBody.fromFile(file));

            plugin.getLogger().info("Successfully uploaded to S3: " + objectKey);

            // Construct the public download URL from the configured CDN/public URL base
            String publicUrl = config.getS3PublicUrl();
            if (publicUrl.endsWith("/")) {
                publicUrl = publicUrl.substring(0, publicUrl.length() - 1);
            }
            return publicUrl + "/" + objectKey;
        }
    }
}
