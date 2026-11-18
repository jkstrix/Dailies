package comedymonkey.dailies.library;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * A shared session (FILE-15): one recording and what Moments logged while it was made, in one file a friend can be
 * sent. It is a zip with the extension {@code .dailiesshare}:
 *
 * <pre>
 * share.json                   what is in it: format, the recording's id and name, who recorded it, when it was packed
 * recording.dailies            the recording, as it is
 * moments/&lt;session id&gt;/...    each moments session the recording was made in, as its folder holds it
 * </pre>
 *
 * A recording is self-contained (plan principle 2), so this needs nothing else to play on the friend's computer. Kept
 * apart from the game, so that it can be tested; the library and Moments decide what goes in and where it lands.
 */
public final class SharePack {

    public static final String EXTENSION = ".dailiesshare";
    public static final String INFO = "share.json";
    public static final String RECORDING = "recording.dailies";
    public static final String MOMENTS = "moments/";
    public static final int FORMAT = 1;

    /** Larger than this, a pack is not unpacked: a recording of a long evening is a few hundred megabytes */
    static final long MAX_UNPACKED_BYTES = 16L * 1024 * 1024 * 1024;
    /** More files than this, a pack is not unpacked: a recording and a few sessions of moments with their voice clips */
    static final int MAX_ENTRIES = 50_000;
    /** Room left free on the disk after unpacking, so a pack never fills it */
    private static final long KEEP_FREE_BYTES = 1024L * 1024 * 1024;

    /**
     * One file in a pack: either a file on disk or bytes made for the pack (a moments log with other speakers' lines
     * taken out, say).
     */
    public record Item(String name, @Nullable Path file, @Nullable byte[] bytes) {

        public Item {
            if (!safe(name)) {
                throw new IllegalArgumentException("A shared file cannot be called \"" + name + "\"");
            }
            if ((file == null) == (bytes == null)) {
                throw new IllegalArgumentException("A shared file is either a file or bytes");
            }
        }

        public static Item of(String name, Path file) {
            return new Item(name, file, null);
        }

        public static Item of(String name, byte[] bytes) {
            return new Item(name, null, bytes);
        }
    }

    private SharePack() {
    }

    /** Writes a pack. The recording and the audio are compressed already, so the zip only squeezes the text */
    public static void write(Path out, JsonObject info, List<Item> items) throws IOException {
        Path temporary = out.resolveSibling(out.getFileName() + ".packing");
        Files.createDirectories(out.toAbsolutePath().getParent());
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(temporary))) {
            JsonObject described = info.deepCopy();
            described.addProperty("format", FORMAT);
            put(zip, INFO, described.toString().getBytes(StandardCharsets.UTF_8), true);
            for (Item item : items) {
                boolean text = item.name().endsWith(".json") || item.name().endsWith(".jsonl");
                if (item.bytes() != null) {
                    put(zip, item.name(), item.bytes(), text);
                } else {
                    zip.setLevel(text ? Deflater.DEFAULT_COMPRESSION : Deflater.NO_COMPRESSION);
                    zip.putNextEntry(new ZipEntry(item.name()));
                    Files.copy(item.file(), zip);
                    zip.closeEntry();
                }
            }
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(temporary);
            throw e;
        }
        Files.move(temporary, out, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void put(ZipOutputStream zip, String name, byte[] bytes, boolean squeeze) throws IOException {
        zip.setLevel(squeeze ? Deflater.DEFAULT_COMPRESSION : Deflater.NO_COMPRESSION);
        zip.putNextEntry(new ZipEntry(name));
        zip.write(bytes);
        zip.closeEntry();
    }

    /** Just what a pack says about itself, without unpacking it */
    public static JsonObject info(Path pack) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(pack))) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (entry.getName().equals(INFO)) {
                    return readInfo(zip);
                }
            }
        }
        throw new IOException("This is not a shared session: it has no " + INFO);
    }

    /**
     * Unpacks a pack into an empty folder: {@link #RECORDING}, and the moments sessions under {@link #MOMENTS}. Names
     * that would land outside the folder are refused, as is a pack too large to be real, one with more files than any
     * real one, and one that would not leave a gigabyte free on the disk.
     *
     * @return what the pack says about itself
     */
    public static JsonObject unpack(Path pack, Path into) throws IOException {
        Files.createDirectories(into);
        long allowed = MAX_UNPACKED_BYTES;
        try {
            allowed = Math.min(allowed, Files.getFileStore(into.toAbsolutePath()).getUsableSpace() - KEEP_FREE_BYTES);
        } catch (IOException | RuntimeException unknown) {
            // How much room there is cannot be told: the largest a pack may be is the only limit
        }
        if (allowed <= 0) {
            throw new IOException("There is not enough room on the disk to take in a shared session");
        }
        return unpack(pack, into, allowed, MAX_ENTRIES);
    }

    /**
     * Unpacks a pack, as {@link #unpack(Path, Path)} does, within limits given
     *
     * @param maxBytes   the most it may write, all files together
     * @param maxEntries the most entries it may hold, folders and the description included
     */
    static JsonObject unpack(Path pack, Path into, long maxBytes, int maxEntries) throws IOException {
        Files.createDirectories(into);
        Path root = into.toAbsolutePath().normalize();
        JsonObject info = null;
        long written = 0;
        int entries = 0;
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(pack))) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (++entries > maxEntries) {
                    throw new IOException("The shared session holds more files than any real one");
                }
                String name = entry.getName();
                if (entry.isDirectory()) {
                    continue;
                }
                if (name.equals(INFO)) {
                    info = readInfo(zip);
                    continue;
                }
                if (!safe(name)) {
                    throw new IOException("The shared session holds a file that would land outside its folder: " + name);
                }
                Path target = root.resolve(name).normalize();
                if (!target.startsWith(root)) {
                    throw new IOException("The shared session holds a file that would land outside its folder: " + name);
                }
                Files.createDirectories(target.getParent());
                written += copy(zip, target, maxBytes - written);
            }
        }
        if (info == null) {
            throw new IOException("This is not a shared session: it has no " + INFO);
        }
        if (info.has("format") && info.get("format").getAsInt() > FORMAT) {
            throw new IOException("This shared session was made by a newer Dailies; update Dailies to open it");
        }
        if (!Files.isRegularFile(root.resolve(RECORDING))) {
            throw new IOException("The shared session has no recording in it");
        }
        return info;
    }

    private static JsonObject readInfo(InputStream in) throws IOException {
        String text = new String(in.readNBytes(1024 * 1024), StandardCharsets.UTF_8);
        try {
            return JsonParser.parseString(text).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IOException("The description of the shared session cannot be read", e);
        }
    }

    private static long copy(InputStream in, Path target, long allowed) throws IOException {
        long total = 0;
        byte[] buffer = new byte[64 * 1024];
        try (OutputStream out = Files.newOutputStream(target)) {
            int read;
            while ((read = in.read(buffer)) >= 0) {
                total += read;
                if (total > allowed) {
                    throw new IOException("The shared session is larger than any real one, or than the room on the disk");
                }
                out.write(buffer, 0, read);
            }
        }
        return total;
    }

    /** A name inside a pack: relative, with forward slashes, no step up, nothing a file system would read specially */
    static boolean safe(String name) {
        if (name == null || name.isBlank() || name.startsWith("/") || name.startsWith("\\") || name.contains("\\") || name.contains(":")) {
            return false;
        }
        for (String part : name.split("/")) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) {
                return false;
            }
        }
        return true;
    }

}
