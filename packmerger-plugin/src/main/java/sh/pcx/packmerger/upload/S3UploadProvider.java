package sh.pcx.packmerger.upload;

import io.minio.GetPresignedObjectUrlArgs;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import io.minio.Result;
import io.minio.UploadObjectArgs;
import io.minio.http.Method;
import io.minio.messages.Item;
import sh.pcx.packmerger.PackMergerBootstrap;
import sh.pcx.packmerger.PluginLogger;
import sh.pcx.packmerger.config.ConfigManager;
import sh.pcx.packmerger.remote.RemotePackManager;

import java.io.File;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Upload provider that pushes the merged pack to any S3-compatible object
 * store (AWS S3, Cloudflare R2, Backblaze B2). Uses the MinIO Java SDK so
 * one implementation covers all three — they all speak the same API.
 *
 * <p>Two key strategies are supported:</p>
 * <ul>
 *   <li><b>content-addressed</b> (default) — object key is {@code &lt;prefix&gt;&lt;sha1&gt;.zip}.
 *       Each new merge writes a new key, so clients that have cached the
 *       previous URL keep their cached copy until a hash change forces a
 *       re-download. A retention policy (see {@code keep-latest}) keeps
 *       storage bounded by deleting older keys after each successful upload.</li>
 *   <li><b>stable</b> — object key is {@code &lt;prefix&gt;&lt;server-name&gt;.zip}; every
 *       merge overwrites the same object. Simpler, but breaks client-side
 *       HTTP caching since the URL doesn't change on new merges.</li>
 * </ul>
 *
 * <p>For private buckets, set {@code acl: "private"} and the provider will
 * return a presigned URL instead of a public one, valid for
 * {@code presign-duration-hours} hours. Public buckets return a plain URL
 * constructed either from {@code public-url-base} (typically a CDN domain
 * pointing at the bucket) or from endpoint + bucket + key.</p>
 */
public class S3UploadProvider implements UploadProvider {

    private final PackMergerBootstrap plugin;
    private final PluginLogger logger;
    private final MinioClient client;
    private final ConfigManager.S3Config cfg;

    public S3UploadProvider(PackMergerBootstrap plugin) {
        this.plugin = plugin;
        this.logger = plugin.getPluginLogger();
        this.cfg = plugin.getConfigManager().getS3Config();

        if (cfg.endpoint().isEmpty() || cfg.bucket().isEmpty()) {
            throw new IllegalStateException("S3 upload provider requires upload.s3.endpoint and upload.s3.bucket to be set");
        }
        String access = RemotePackManager.substituteEnv(cfg.accessKey());
        String secret = RemotePackManager.substituteEnv(cfg.secretKey());
        if (access == null || access.isEmpty() || secret == null || secret.isEmpty()) {
            throw new IllegalStateException("S3 upload provider requires upload.s3.access-key and upload.s3.secret-key to be set");
        }

        this.client = MinioClient.builder()
                .endpoint(cfg.endpoint())
                .region(cfg.region())
                .credentials(access, secret)
                .build();

        logger.upload("S3 provider ready: endpoint=" + cfg.endpoint()
                + " bucket=" + cfg.bucket()
                + " key-strategy=" + (cfg.isContentAddressed() ? "content-addressed" : "stable")
                + " acl=" + cfg.acl());
    }

    @Override
    public String upload(File file, String sha1Hex) throws Exception {
        String key = resolveKey(sha1Hex);

        Map<String, String> headers = new HashMap<>();
        headers.put("Cache-Control", cfg.cacheControl());

        UploadObjectArgs.Builder argsBuilder = UploadObjectArgs.builder()
                .bucket(cfg.bucket())
                .object(key)
                .filename(file.getAbsolutePath())
                .contentType("application/zip")
                .headers(headers);

        // MinIO sends ACL via extra headers; "public-read" is what public buckets expect
        if (!cfg.acl().equalsIgnoreCase("private")) {
            Map<String, String> userMeta = new HashMap<>();
            // Most S3-compatible stores interpret x-amz-acl; R2 ignores it (rely on bucket policy)
            userMeta.put("x-amz-acl", cfg.acl());
            argsBuilder.extraHeaders(userMeta);
        }

        long start = System.currentTimeMillis();
        client.uploadObject(argsBuilder.build());
        long elapsed = System.currentTimeMillis() - start;
        logger.upload("S3 uploaded: " + key + " (" + file.length() + " bytes, " + elapsed + "ms)");

        // Retention: trim older content-addressed objects under the prefix.
        if (cfg.isContentAddressed() && cfg.retainLatest() > 0) {
            try {
                enforceRetention(key);
            } catch (Exception e) {
                logger.warning("[s3] retention pass failed (" + e.getMessage() + "); continuing");
            }
        }

        return buildPublicUrl(key);
    }

    /** Resolves the object key based on the configured strategy. */
    private String resolveKey(String sha1Hex) {
        String prefix = cfg.pathPrefix() == null ? "" : cfg.pathPrefix();
        if (cfg.isContentAddressed()) {
            return prefix + sha1Hex + ".zip";
        }
        String serverName = plugin.getConfigManager().getServerName();
        String stable = (serverName == null || serverName.isEmpty() || serverName.equalsIgnoreCase("default"))
                ? "merged-pack.zip" : serverName + "-merged-pack.zip";
        return prefix + stable;
    }

    /** Public URL construction: prefer public-url-base; otherwise endpoint-style. */
    private String buildPublicUrl(String key) throws Exception {
        if (cfg.isPrivateAcl()) {
            return client.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(cfg.bucket())
                    .object(key)
                    .expiry(cfg.presignHours(), TimeUnit.HOURS)
                    .build());
        }
        if (cfg.publicUrlBase() != null && !cfg.publicUrlBase().isEmpty()) {
            String base = cfg.publicUrlBase();
            return (base.endsWith("/") ? base : base + "/") + key;
        }
        // Fall back to <endpoint>/<bucket>/<key>
        String ep = cfg.endpoint();
        if (ep.endsWith("/")) ep = ep.substring(0, ep.length() - 1);
        return ep + "/" + cfg.bucket() + "/" + key;
    }

    /**
     * Deletes older content-addressed objects so only the N most-recent remain.
     * {@code keepKey} (the one we just uploaded) is always retained.
     */
    private void enforceRetention(String keepKey) throws Exception {
        String prefix = cfg.pathPrefix() == null ? "" : cfg.pathPrefix();
        ListObjectsArgs listArgs = ListObjectsArgs.builder()
                .bucket(cfg.bucket())
                .prefix(prefix)
                .recursive(false)
                .build();

        List<Item> objects = new ArrayList<>();
        for (Result<Item> r : client.listObjects(listArgs)) {
            try {
                Item item = r.get();
                if (item.isDir()) continue;
                // Only manage keys that look like content-addressed pack zips
                String name = item.objectName();
                if (!name.endsWith(".zip")) continue;
                objects.add(item);
            } catch (Exception e) {
                logger.debug("[s3] list error: " + e.getMessage());
            }
        }

        if (objects.size() <= cfg.retainLatest()) return;

        // Newest first
        objects.sort(Comparator.comparing((Item i) -> {
            ZonedDateTime mod = i.lastModified();
            return mod == null ? ZonedDateTime.now() : mod;
        }).reversed());

        int removed = 0;
        for (int i = cfg.retainLatest(); i < objects.size(); i++) {
            Item stale = objects.get(i);
            if (stale.objectName().equals(keepKey)) continue;
            try {
                client.removeObject(RemoveObjectArgs.builder()
                        .bucket(cfg.bucket())
                        .object(stale.objectName())
                        .build());
                removed++;
            } catch (Exception e) {
                logger.debug("[s3] could not remove " + stale.objectName() + ": " + e.getMessage());
            }
        }
        if (removed > 0) {
            logger.upload("S3 retention: deleted " + removed + " older object(s); kept " + cfg.retainLatest());
        }
    }
}
