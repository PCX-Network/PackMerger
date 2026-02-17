package sh.pcx.packmerger.upload;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;
import sh.pcx.packmerger.PackMerger;
import sh.pcx.packmerger.config.ConfigManager;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Upload provider that serves the merged resource pack from a built-in HTTP server.
 *
 * <p>This is the simplest upload option — no external service is needed. The plugin starts
 * an embedded {@link HttpServer} on the configured port and serves the pack file directly
 * at the {@code /pack} endpoint. The "upload" operation simply swaps the file reference
 * atomically so that subsequent HTTP requests serve the new version.</p>
 *
 * <p>Features:</p>
 * <ul>
 *   <li><strong>Concurrent download limiting</strong> — a {@link Semaphore} caps the number
 *       of simultaneous downloads to prevent the server from being overwhelmed</li>
 *   <li><strong>Auto URL detection</strong> — if no public URL is configured, the server IP
 *       is read from {@code server.properties} to construct the download URL</li>
 *   <li><strong>Thread pool</strong> — uses a fixed thread pool sized to the number of
 *       available processors (minimum 4) for handling HTTP requests</li>
 * </ul>
 *
 * <p>Lifecycle: started by {@link PackMerger#initUploadProvider()} when the self-host
 * provider is selected, stopped by {@link PackMerger#onDisable()} or when switching
 * providers.</p>
 *
 * @see UploadProvider
 */
public class SelfHostProvider implements UploadProvider {

    /** Reference to the owning plugin for config access and logging. */
    private final PackMerger plugin;

    /** The embedded HTTP server instance, or {@code null} if not started. */
    private HttpServer server;

    /**
     * Atomic reference to the currently served pack file. Updated on each "upload"
     * (merge completion) so that in-flight requests continue serving the old file
     * while new requests get the latest version.
     */
    private final AtomicReference<File> currentFile = new AtomicReference<>();

    /**
     * Semaphore for limiting concurrent downloads. {@code null} if rate limiting is disabled
     * (rate-limit set to 0).
     */
    private Semaphore rateLimiter;

    /**
     * Creates a new self-host provider.
     *
     * @param plugin the owning PackMerger plugin
     */
    public SelfHostProvider(PackMerger plugin) {
        this.plugin = plugin;
    }

    /**
     * Starts the built-in HTTP server on the configured port.
     *
     * <p>The server binds to all interfaces (0.0.0.0) and registers a single context
     * at {@code /pack} that serves the current merged pack file. If the port is already
     * in use, an error is logged but the plugin continues to function (uploads will
     * work but self-hosting won't).</p>
     */
    public void start() {
        ConfigManager config = plugin.getConfigManager();
        int port = config.getSelfHostPort();
        int rateLimit = config.getSelfHostRateLimit();

        // Initialize rate limiter if a limit is configured (0 = unlimited)
        if (rateLimit > 0) {
            rateLimiter = new Semaphore(rateLimit);
        }

        try {
            server = HttpServer.create(new InetSocketAddress(port), 0);
            server.createContext("/pack", this::handleRequest);
            // Thread pool sized to available processors, minimum 4 for reasonable concurrency
            server.setExecutor(Executors.newFixedThreadPool(Math.max(4, Runtime.getRuntime().availableProcessors())));
            server.start();
            plugin.getLogger().info("Self-host HTTP server started on port " + port);
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to start self-host HTTP server on port " + port + ": " + e.getMessage());
        }
    }

    /**
     * Stops the HTTP server with a 2-second grace period for in-flight requests.
     * Safe to call multiple times.
     */
    public void stop() {
        if (server != null) {
            server.stop(2); // 2-second grace period for in-flight downloads
            server = null;
            plugin.getLogger().info("Self-host HTTP server stopped");
        }
    }

    /**
     * Handles incoming HTTP requests to the {@code /pack} endpoint.
     *
     * <p>Only GET requests are accepted. The handler:</p>
     * <ol>
     *   <li>Rejects non-GET methods with 405</li>
     *   <li>Returns 503 if no pack is available yet</li>
     *   <li>Returns 429 if the rate limit is exceeded</li>
     *   <li>Streams the pack file with appropriate Content-Type and Content-Disposition headers</li>
     * </ol>
     *
     * <p>The rate limiter semaphore is acquired before streaming and released in a
     * finally block to ensure permits are always returned, even on I/O errors.</p>
     *
     * @param exchange the HTTP exchange object
     * @throws IOException if writing the response fails
     */
    private void handleRequest(HttpExchange exchange) throws IOException {
        // Only accept GET requests
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1); // 405 Method Not Allowed
            exchange.close();
            return;
        }

        File file = currentFile.get();
        if (file == null || !file.exists()) {
            // No pack has been merged yet — return 503 Service Unavailable
            byte[] msg = "Resource pack not available yet".getBytes();
            exchange.sendResponseHeaders(503, msg.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(msg);
            }
            exchange.close();
            return;
        }

        // Check rate limit before starting the download
        if (rateLimiter != null && !rateLimiter.tryAcquire()) {
            // Too many concurrent downloads — return 429 Too Many Requests
            byte[] msg = "Too many concurrent downloads, try again later".getBytes();
            exchange.sendResponseHeaders(429, msg.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(msg);
            }
            exchange.close();
            return;
        }

        try {
            long fileSize = file.length();
            exchange.getResponseHeaders().set("Content-Type", "application/zip");
            exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"resource-pack.zip\"");
            exchange.sendResponseHeaders(200, fileSize);

            // Stream the file in 8 KB chunks to avoid loading the entire pack into memory
            try (OutputStream os = exchange.getResponseBody();
                 FileInputStream fis = new FileInputStream(file)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = fis.read(buffer)) != -1) {
                    os.write(buffer, 0, read);
                }
            }
        } finally {
            // Always release the rate limiter permit, even if the download was interrupted
            if (rateLimiter != null) {
                rateLimiter.release();
            }
            exchange.close();
        }
    }

    /**
     * Makes the given file available for download via the HTTP server.
     *
     * <p>For self-host, "uploading" simply means swapping the file reference that the
     * HTTP handler serves. No actual network transfer occurs. The swap is atomic via
     * {@link AtomicReference#set}.</p>
     *
     * @param file    the merged pack zip file to serve
     * @param sha1Hex the SHA-1 hash (not used by self-host, but required by the interface)
     * @return the public download URL for the pack (e.g. {@code http://1.2.3.4:8080/pack})
     */
    @Override
    public String upload(File file, String sha1Hex) throws Exception {
        // Atomically swap the served file reference
        currentFile.set(file);

        String publicUrl = plugin.getConfigManager().getSelfHostPublicUrl();
        if (publicUrl == null || publicUrl.isEmpty()) {
            // Auto-detect the URL from server.properties server-ip and configured port
            publicUrl = buildAutoUrl();
        }

        // Ensure the URL ends with /pack to match the HTTP server context
        if (!publicUrl.endsWith("/pack")) {
            if (publicUrl.endsWith("/")) {
                publicUrl += "pack";
            } else {
                publicUrl += "/pack";
            }
        }

        return publicUrl;
    }

    /**
     * Builds the public URL automatically from the server IP and configured port.
     *
     * @return the auto-detected URL (e.g. {@code http://1.2.3.4:8080})
     */
    private String buildAutoUrl() {
        int port = plugin.getConfigManager().getSelfHostPort();
        String serverIp = readServerIp();
        return "http://" + serverIp + ":" + port;
    }

    /**
     * Reads the {@code server-ip} property from {@code server.properties}.
     *
     * <p>If the property is empty or the file cannot be read, defaults to "localhost".
     * In production, operators should configure the {@code public-url} setting explicitly
     * to ensure players can reach the server from outside the network.</p>
     *
     * @return the server IP address, or "localhost" as a fallback
     */
    private String readServerIp() {
        File serverProperties = new File("server.properties");
        if (serverProperties.exists()) {
            Properties props = new Properties();
            try (FileInputStream fis = new FileInputStream(serverProperties)) {
                props.load(fis);
                String ip = props.getProperty("server-ip", "").trim();
                if (!ip.isEmpty()) {
                    return ip;
                }
            } catch (IOException e) {
                // Fall through to default
            }
        }
        return "localhost";
    }
}
