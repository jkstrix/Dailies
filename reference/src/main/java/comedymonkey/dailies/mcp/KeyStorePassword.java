package comedymonkey.dailies.mcp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The password that locks the MCP server's private key in its key file. It is made at random the first time it is
 * needed, once for each game folder, and kept in a different folder from the key file (the game's config folder, where
 * the key lives in Dailies' own folder), so a copy of Dailies' folder, a backup of it or something shared from it never
 * carries a key that can be opened. Both files are made readable by their owner alone where the file system allows it.
 * <p>
 * The game has to open the key with nobody there to type a password, so this cannot keep the key from someone who can
 * read both folders as the player: nothing on this computer could. What it does stop is the key being opened from the
 * key file on its own, as anyone could while every copy of Dailies locked it with the same written-down word.
 */
public final class KeyStorePassword {

    private static final int BYTES = 32;
    /** 32 bytes as unpadded URL-safe Base64 */
    private static final Pattern SHAPE = Pattern.compile("[A-Za-z0-9_-]{43}");

    private KeyStorePassword() {
    }

    /**
     * The kept password, or a new one when there is none or what is there is not one. A new password means any key
     * file locked with an earlier one no longer opens, and the server makes itself a new certificate
     */
    public static char[] loadOrCreate(Path file) throws IOException {
        if (Files.isRegularFile(file)) {
            String kept = Files.readString(file, StandardCharsets.US_ASCII).trim();
            if (SHAPE.matcher(kept).matches()) {
                ownerOnly(file);
                return kept.toCharArray();
            }
        }
        byte[] random = new byte[BYTES];
        new SecureRandom().nextBytes(random);
        String made = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        writeOwnerOnly(file, made.getBytes(StandardCharsets.US_ASCII));
        return made.toCharArray();
    }

    /**
     * Writes a file that only its owner can read: the bytes go into a file beside it that is made owner-only while
     * still empty, which then takes the file's place, so the contents are never readable by anyone else even briefly
     */
    public static void writeOwnerOnly(Path file, byte[] bytes) throws IOException {
        Path folder = file.toAbsolutePath().getParent();
        Files.createDirectories(folder);
        Path temp = Files.createTempFile(folder, file.getFileName().toString(), ".tmp");
        try {
            ownerOnly(temp);
            Files.write(temp, bytes);
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
        ownerOnly(file);
    }

    /**
     * Makes a file readable and writable by its owner alone: rw------- where the file system has Unix permissions, and
     * a list that names only the owner where it has Windows ones. Where neither can be set, the file is left as it is
     *
     * @return whether it could be done
     */
    public static boolean ownerOnly(Path file) {
        try {
            PosixFileAttributeView posix = Files.getFileAttributeView(file, PosixFileAttributeView.class);
            if (posix != null) {
                posix.setPermissions(PosixFilePermissions.fromString("rw-------"));
                return true;
            }
            AclFileAttributeView acl = Files.getFileAttributeView(file, AclFileAttributeView.class);
            if (acl != null) {
                UserPrincipal owner = acl.getOwner();
                AclEntry only = AclEntry.newBuilder()
                    .setType(AclEntryType.ALLOW)
                    .setPrincipal(owner)
                    .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                    .build();
                acl.setAcl(List.of(only));
                return true;
            }
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            // Not this file system's to give: the file keeps what its folder gives it
        }
        return false;
    }

}
