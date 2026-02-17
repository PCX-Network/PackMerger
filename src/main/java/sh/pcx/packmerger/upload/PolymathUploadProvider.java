package sh.pcx.packmerger.upload;

import sh.pcx.packmerger.PackMerger;
import sh.pcx.packmerger.PluginLogger;
import sh.pcx.packmerger.config.ConfigManager;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;

/**
 * Upload provider that sends the merged resource pack to a
 * <a href="https://github.com/oraxen/polymath">Polymath</a> server.
 *
 * <p>Polymath is a lightweight Flask-based Python web server originally created by the
 * Oraxen/Nexo project for hosting Minecraft resource packs. It can be self-hosted or
 * used via the public Oraxen instance at {@code atlas.oraxen.com}.</p>
 *
 * <p>The upload is a {@code multipart/form-data} POST to the server's {@code /upload}
 * endpoint with three fields: {@code secret} (shared authentication token), {@code id}
 * (unique identifier for this server's pack), and {@code pack} (the zip file). On
 * success, the server returns a JSON response containing the download URL.</p>
 *
 * <p>The multipart request body is constructed manually since Java's {@link HttpClient}
 * does not include built-in multipart support.</p>
 *
 * @see UploadProvider
 * @see ConfigManager
 */
public class PolymathUploadProvider implements UploadProvider {

    /** Reference to the owning plugin for config access and logging. */
    private final PackMerger plugin;

    /** Colored console logger. */
    private final PluginLogger logger;

    /**
     * Creates a new Polymath upload provider.
     *
     * @param plugin the owning PackMerger plugin
     */
    public PolymathUploadProvider(PackMerger plugin) {
        this.plugin = plugin;
        this.logger = plugin.getPluginLogger();
    }

    /**
     * Uploads the merged pack to a Polymath server and returns the public download URL.
     *
     * <p>The upload uses Java's built-in {@link HttpClient} with a manually constructed
     * multipart/form-data request body. The response is expected to be JSON containing
     * the download URL. If the returned URL is a relative path, the configured Polymath
     * server URL is prepended to form the full download URL.</p>
     *
     * @param file    the merged pack zip file to upload
     * @param sha1Hex the SHA-1 hash (not used by Polymath, but available for future use)
     * @return the public download URL for the uploaded pack
     * @throws Exception if the upload fails (connection refused, auth failure, timeout, etc.)
     */
    @Override
    public String upload(File file, String sha1Hex) throws Exception {
        ConfigManager config = plugin.getConfigManager();

        String serverUrl = config.getPolymathServer();
        String secret = config.getPolymathSecret();
        String id = config.getPolymathId();

        if (serverUrl == null || serverUrl.isEmpty()) {
            throw new RuntimeException(
                    "Polymath server URL is not configured. Set 'upload.polymath.server' in config.yml "
                    + "to your Polymath instance URL, or switch to provider: \"self-host\".");
        }

        // Default the ID to the server name if not explicitly configured
        if (id == null || id.isEmpty()) {
            id = config.getServerName();
        }

        // Prepend https:// if no protocol was specified
        if (!serverUrl.startsWith("http://") && !serverUrl.startsWith("https://")) {
            serverUrl = "https://" + serverUrl;
        }

        // Strip trailing slash from server URL for consistent URL construction
        if (serverUrl.endsWith("/")) {
            serverUrl = serverUrl.substring(0, serverUrl.length() - 1);
        }

        byte[] fileBytes = Files.readAllBytes(file.toPath());
        byte[] multipartBody = buildMultipartBody(secret, id, fileBytes);

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(serverUrl + "/upload"))
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .POST(HttpRequest.BodyPublishers.ofByteArray(multipartBody))
                .timeout(Duration.ofSeconds(60))
                .build();

        HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (java.net.ConnectException e) {
            throw new RuntimeException("Failed to connect to Polymath server at " + serverUrl
                    + " — is the server running? (" + e.getMessage() + ")");
        } catch (java.net.http.HttpTimeoutException e) {
            throw new RuntimeException("Upload to Polymath server at " + serverUrl
                    + " timed out after 60 seconds");
        }

        int statusCode = response.statusCode();
        String body = response.body();

        if (statusCode == 403 || statusCode == 401) {
            throw new RuntimeException("Polymath upload rejected — authentication failed (HTTP "
                    + statusCode + "). Check that your secret matches the server's configured secret.");
        }

        if (statusCode < 200 || statusCode >= 300) {
            throw new RuntimeException("Polymath upload failed with HTTP " + statusCode + ": " + body);
        }

        // Parse the download URL from the JSON response.
        // The response is simple JSON, so we extract the URL with basic string parsing
        // to avoid adding a JSON dependency.
        String downloadUrl = extractUrlFromJson(body);
        if (downloadUrl == null || downloadUrl.isEmpty()) {
            throw new RuntimeException("Polymath server returned an unexpected response (no URL found): " + body);
        }

        // If the URL is a relative path, prepend the server URL
        if (!downloadUrl.startsWith("http://") && !downloadUrl.startsWith("https://")) {
            if (!downloadUrl.startsWith("/")) {
                downloadUrl = "/" + downloadUrl;
            }
            downloadUrl = serverUrl + downloadUrl;
        }

        logger.upload("Successfully uploaded to Polymath: " + downloadUrl);
        return downloadUrl;
    }

    /** Boundary string used to delimit multipart form fields. */
    private static final String BOUNDARY = "----PackMergerBoundary" + System.currentTimeMillis();

    /**
     * Builds a multipart/form-data request body containing the secret, id, and pack file.
     *
     * <p>The body follows the RFC 2046 multipart format:</p>
     * <pre>
     * --boundary\r\n
     * Content-Disposition: form-data; name="secret"\r\n\r\n
     * secret_value\r\n
     * --boundary\r\n
     * Content-Disposition: form-data; name="id"\r\n\r\n
     * id_value\r\n
     * --boundary\r\n
     * Content-Disposition: form-data; name="pack"; filename="merged-pack.zip"\r\n
     * Content-Type: application/zip\r\n\r\n
     * [file bytes]\r\n
     * --boundary--\r\n
     * </pre>
     *
     * @param secret    the shared authentication secret
     * @param id        the unique identifier for this server's pack
     * @param fileBytes the raw bytes of the zip file to upload
     * @return the complete multipart body as a byte array
     * @throws Exception if an I/O error occurs while assembling the body
     */
    private byte[] buildMultipartBody(String secret, String id, byte[] fileBytes) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        // Field: secret
        writeTextField(baos, "secret", secret);

        // Field: id
        writeTextField(baos, "id", id);

        // Field: pack (file upload)
        baos.write(("--" + BOUNDARY + "\r\n").getBytes(StandardCharsets.UTF_8));
        baos.write(("Content-Disposition: form-data; name=\"pack\"; filename=\"merged-pack.zip\"\r\n")
                .getBytes(StandardCharsets.UTF_8));
        baos.write("Content-Type: application/zip\r\n\r\n".getBytes(StandardCharsets.UTF_8));
        baos.write(fileBytes);
        baos.write("\r\n".getBytes(StandardCharsets.UTF_8));

        // Closing boundary
        baos.write(("--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.UTF_8));

        return baos.toByteArray();
    }

    /**
     * Writes a single text form field to the multipart output stream.
     *
     * @param baos  the output stream to write to
     * @param name  the form field name
     * @param value the form field value
     * @throws Exception if an I/O error occurs
     */
    private void writeTextField(ByteArrayOutputStream baos, String name, String value) throws Exception {
        baos.write(("--" + BOUNDARY + "\r\n").getBytes(StandardCharsets.UTF_8));
        baos.write(("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        baos.write(value.getBytes(StandardCharsets.UTF_8));
        baos.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Extracts a URL value from a simple JSON response string.
     *
     * <p>Looks for common URL field names ({@code "url"}, {@code "download"}, {@code "pack"})
     * in the JSON response. Uses basic string parsing to avoid requiring a JSON library
     * dependency for this single use case.</p>
     *
     * @param json the JSON response body from the Polymath server
     * @return the extracted URL, or {@code null} if no URL field was found
     */
    private String extractUrlFromJson(String json) {
        // Try common field names that Polymath might use for the download URL
        String[] fieldNames = {"url", "download", "pack"};
        for (String field : fieldNames) {
            String url = extractJsonStringField(json, field);
            if (url != null && !url.isEmpty()) {
                return url;
            }
        }
        return null;
    }

    /**
     * Extracts a string value for the given field name from a JSON string.
     *
     * <p>Handles the pattern {@code "fieldName": "value"} with optional whitespace.
     * This is intentionally simple — Polymath responses are small, well-formed JSON
     * objects with string values.</p>
     *
     * @param json      the JSON string to search
     * @param fieldName the field name to look for
     * @return the field's string value, or {@code null} if not found
     */
    private String extractJsonStringField(String json, String fieldName) {
        // Match "fieldName" followed by : and a quoted string value
        String pattern = "\"" + fieldName + "\"";
        int fieldIndex = json.indexOf(pattern);
        if (fieldIndex == -1) return null;

        // Find the colon after the field name
        int colonIndex = json.indexOf(':', fieldIndex + pattern.length());
        if (colonIndex == -1) return null;

        // Find the opening quote of the value
        int openQuote = json.indexOf('"', colonIndex + 1);
        if (openQuote == -1) return null;

        // Find the closing quote of the value
        int closeQuote = json.indexOf('"', openQuote + 1);
        if (closeQuote == -1) return null;

        return json.substring(openQuote + 1, closeQuote);
    }
}
