package sh.pcx.packmerger.upload;

import com.jcraft.jsch.*;
import sh.pcx.packmerger.PackMerger;
import sh.pcx.packmerger.config.ConfigManager;

import java.io.File;
import java.io.FileInputStream;

/**
 * Upload provider that transfers the merged resource pack to a remote server via SFTP.
 *
 * <p>Useful when the pack needs to be served from a dedicated web server or CDN origin
 * that is accessible via SSH/SFTP. Supports both password and private key authentication.</p>
 *
 * <p>The upload process: connect to the SFTP server, create any missing directories in
 * the remote path, upload the file (overwriting any existing file), then disconnect.
 * A new SSH session is established for each upload to avoid stale connection issues.</p>
 *
 * <p>Uses the JSch library for SSH/SFTP operations. Note that strict host key checking
 * is disabled for simplicity — in production, this means the first connection to a
 * new host will be accepted without verification.</p>
 *
 * @see UploadProvider
 * @see ConfigManager
 */
public class SFTPUploadProvider implements UploadProvider {

    /** Reference to the owning plugin for config access and logging. */
    private final PackMerger plugin;

    /**
     * Creates a new SFTP upload provider.
     *
     * @param plugin the owning PackMerger plugin
     */
    public SFTPUploadProvider(PackMerger plugin) {
        this.plugin = plugin;
    }

    /**
     * Uploads the merged pack to the configured SFTP server and returns the public download URL.
     *
     * <p>Authentication priority: if a private key path is configured, it is used for
     * key-based auth. If a password is also configured, it serves as the key passphrase
     * or as a fallback password auth method.</p>
     *
     * @param file    the merged pack zip file to upload
     * @param sha1Hex the SHA-1 hash (not used in the remote path, but available for
     *                future filename versioning)
     * @return the configured public download URL for the uploaded pack
     * @throws Exception if the SFTP connection or upload fails
     */
    @Override
    public String upload(File file, String sha1Hex) throws Exception {
        ConfigManager config = plugin.getConfigManager();

        JSch jsch = new JSch();

        // Add private key identity if configured (for key-based authentication)
        String privateKeyPath = config.getSftpPrivateKeyPath();
        if (privateKeyPath != null && !privateKeyPath.isEmpty()) {
            jsch.addIdentity(privateKeyPath);
        }

        Session session = jsch.getSession(config.getSftpUsername(), config.getSftpHost(), config.getSftpPort());

        // Set password if configured (used for password auth or as key passphrase)
        String password = config.getSftpPassword();
        if (password != null && !password.isEmpty()) {
            session.setPassword(password);
        }

        // Disable strict host key checking to avoid interactive prompts on first connection.
        // This trades some security for operational simplicity in automated environments.
        session.setConfig("StrictHostKeyChecking", "no");
        session.connect(30000); // 30 second connection timeout

        try {
            ChannelSftp channel = (ChannelSftp) session.openChannel("sftp");
            channel.connect(30000); // 30 second channel open timeout

            try {
                String remotePath = config.getSftpRemotePath();

                // Ensure all parent directories exist on the remote server
                String remoteDir = remotePath.substring(0, remotePath.lastIndexOf('/'));
                mkdirs(channel, remoteDir);

                // Upload the file, overwriting any existing file at the remote path
                try (FileInputStream fis = new FileInputStream(file)) {
                    channel.put(fis, remotePath, ChannelSftp.OVERWRITE);
                }

                plugin.getLogger().info("Successfully uploaded via SFTP to: " + remotePath);
            } finally {
                channel.disconnect();
            }
        } finally {
            session.disconnect();
        }

        return config.getSftpPublicUrl();
    }

    /**
     * Recursively creates directories on the remote SFTP server, similar to {@code mkdir -p}.
     *
     * <p>Walks each path component and attempts to stat it. If the stat fails (directory
     * doesn't exist), creates it. The mkdir may fail with an {@link SftpException} if
     * another process created the directory concurrently — this race condition is handled
     * by swallowing the exception.</p>
     *
     * @param channel the connected SFTP channel
     * @param path    the full remote directory path to ensure exists
     */
    private void mkdirs(ChannelSftp channel, String path) {
        String[] parts = path.split("/");
        StringBuilder current = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) {
                // Leading slash — start from filesystem root
                current.append("/");
                continue;
            }
            current.append(part).append("/");
            try {
                // Check if this directory already exists
                channel.stat(current.toString());
            } catch (SftpException e) {
                // Directory doesn't exist — try to create it
                try {
                    channel.mkdir(current.toString());
                } catch (SftpException ex) {
                    // May already exist from a concurrent operation — safe to ignore
                }
            }
        }
    }
}
