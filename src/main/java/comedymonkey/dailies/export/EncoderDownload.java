package comedymonkey.dailies.export;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;

/**
 * The video encoder fetched on first use, for a build of Dailies that carries no encoder of its own (the one made for
 * sites that take no programs inside a mod's jar). It is downloaded from Dailies' own GitHub release of the encoder,
 * and kept only when its length and SHA-256 are the ones this build was made with; nothing else is ever run. Kept in
 * the game's dailies/encoder/downloads folder and used from there, so it is fetched once
 */
final class EncoderDownload {

    private static volatile boolean downloading = false;

    private EncoderDownload() {
    }

    /** Whether an encoder is being downloaded just now: what waits for it should go on waiting */
    static boolean downloading() {
        return downloading;
    }

    /**
     * The downloaded packed encoder, fetching it first when it is not already kept
     *
     * @param url    where it is downloaded from
     * @param sha256 its SHA-256, as hex
     * @param length its length in bytes
     * @param folder where downloads are kept
     * @param name   the name it is kept under
     * @param agent  how the download introduces itself
     * @param log    where to say how it is going
     */
    static Path fetch(String url, String sha256, long length, Path folder, String name, String agent, java.util.function.Consumer<String> log)
        throws IOException {
        Files.createDirectories(folder);
        Path kept = folder.resolve(name);
        if (Files.isRegularFile(kept) && Files.size(kept) == length && sha256(kept).equalsIgnoreCase(sha256)) {
            return kept;
        }
        Path partial = folder.resolve(name + "." + UUID.randomUUID() + Encoder.UNPACKING);
        downloading = true;
        try {
            log.accept("Dailies: downloading the video encoder (" + length / 1_000_000 + " MB) from " + url);
            MessageDigest digest = digest();
            long got = 0;
            int told = 0;
            URLConnection connection = URI.create(url).toURL().openConnection();
            connection.setConnectTimeout(30_000);
            connection.setReadTimeout(60_000);
            connection.setRequestProperty("User-Agent", agent);
            try (InputStream in = connection.getInputStream(); OutputStream out = Files.newOutputStream(partial)) {
                byte[] buffer = new byte[1 << 16];
                for (int read = in.read(buffer); read >= 0; read = in.read(buffer)) {
                    out.write(buffer, 0, read);
                    digest.update(buffer, 0, read);
                    got += read;
                    if (got > length) {
                        throw new IOException("The video encoder downloaded from " + url + " is longer than it should be; nothing was kept");
                    }
                    int quarter = (int) (got * 4 / Math.max(1, length));
                    if (quarter > told && quarter < 4) {
                        told = quarter;
                        log.accept("Dailies: video encoder " + quarter * 25 + "% downloaded");
                    }
                }
            }
            if (got != length) {
                throw new IOException("The video encoder download from " + url + " stopped at " + got + " of " + length + " bytes; nothing was kept");
            }
            String found = HexFormat.of().formatHex(digest.digest());
            if (!found.equalsIgnoreCase(sha256)) {
                throw new IOException("The video encoder downloaded from " + url + " is not the one this Dailies was made with (SHA-256 "
                    + found.toLowerCase(Locale.ROOT) + "); nothing was kept");
            }
            Files.move(partial, kept, StandardCopyOption.REPLACE_EXISTING);
            log.accept("Dailies: video encoder downloaded and checked");
            return kept;
        } finally {
            downloading = false;
            Files.deleteIfExists(partial);
        }
    }

    static String sha256(Path file) throws IOException {
        MessageDigest digest = digest();
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[1 << 16];
            for (int read = in.read(buffer); read >= 0; read = in.read(buffer)) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest digest() throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 is missing from this Java", e);
        }
    }
}
