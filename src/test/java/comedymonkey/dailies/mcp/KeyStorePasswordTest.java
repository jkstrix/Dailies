package comedymonkey.dailies.mcp;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KeyStorePasswordTest {

    @TempDir
    Path folder;

    @Test
    void aPasswordIsMadeOnceAndKept() throws Exception {
        Path file = this.folder.resolve("config").resolve("key.secret");
        char[] first = KeyStorePassword.loadOrCreate(file);
        assertEquals(43, first.length);
        assertTrue(new String(first).matches("[A-Za-z0-9_-]{43}"));
        assertArrayEquals(first, KeyStorePassword.loadOrCreate(file));
        // Another game folder gets a password of its own
        assertNotEquals(new String(first), new String(KeyStorePassword.loadOrCreate(this.folder.resolve("other.secret"))));
    }

    @Test
    void somethingThatIsNotAPasswordIsReplaced() throws Exception {
        Path file = this.folder.resolve("key.secret");
        Files.writeString(file, "dailies", StandardCharsets.US_ASCII);
        char[] made = KeyStorePassword.loadOrCreate(file);
        assertNotEquals("dailies", new String(made));
        assertEquals(new String(made), Files.readString(file, StandardCharsets.US_ASCII));
    }

    @Test
    void thePasswordFileIsTheOwnersAlone() throws Exception {
        Path file = this.folder.resolve("key.secret");
        KeyStorePassword.loadOrCreate(file);
        PosixFileAttributeView posix = Files.getFileAttributeView(file, PosixFileAttributeView.class);
        AclFileAttributeView acl = Files.getFileAttributeView(file, AclFileAttributeView.class);
        if (posix != null) {
            assertEquals(PosixFilePermissions.fromString("rw-------"), posix.readAttributes().permissions());
        } else if (acl != null) {
            List<AclEntry> entries = acl.getAcl();
            assertEquals(1, entries.size(), entries.toString());
            assertEquals(acl.getOwner(), entries.get(0).principal());
        }
        // No temporary file is left beside it
        try (var left = Files.list(this.folder)) {
            assertEquals(List.of(file), left.toList());
        }
    }

    @Test
    void aKeyFileLockedWithTheOldWordIsMadeAgain() throws Exception {
        Path key = this.folder.resolve("mcp").resolve("certificate.p12");
        Path password = this.folder.resolve("config").resolve("key.secret");
        List<InetAddress> addresses = List.of(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}));

        // A key file as every copy of Dailies once wrote it, locked with the same written-down word
        ServerCertificate old = ServerCertificate.create(List.of("localhost"), addresses, Instant.now());
        char[] word = "dailies".toCharArray();
        KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(null, null);
        store.setKeyEntry("dailies-mcp", oldKey(old), word, new Certificate[]{old.certificate()});
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        store.store(bytes, word);
        Files.createDirectories(key.getParent());
        Files.write(key, bytes.toByteArray());

        ServerCertificate made = ServerCertificate.loadOrCreate(key, password, List.of("localhost"), addresses, null);
        assertNotEquals(old.fingerprint(), made.fingerprint());

        // The new key file opens with the kept password and not with the old word
        KeyStore reopened = KeyStore.getInstance("PKCS12");
        try (var in = Files.newInputStream(key)) {
            reopened.load(in, KeyStorePassword.loadOrCreate(password));
        }
        assertTrue(reopened.containsAlias("dailies-mcp"));
        boolean openedWithWord;
        try (var in = Files.newInputStream(key)) {
            KeyStore.getInstance("PKCS12").load(in, word);
            openedWithWord = true;
        } catch (Exception e) {
            openedWithWord = false;
        }
        assertFalse(openedWithWord, "the new key file still opens with the old word");

        // And it is kept from then on
        assertEquals(made.fingerprint(), ServerCertificate.loadOrCreate(key, password, List.of("localhost"), addresses, null).fingerprint());
    }

    /** The private key of a certificate made in memory, taken back out of the store its TLS context is built from */
    private static java.security.PrivateKey oldKey(ServerCertificate certificate) throws Exception {
        java.lang.reflect.Field field = ServerCertificate.class.getDeclaredField("key");
        field.setAccessible(true);
        return (java.security.PrivateKey) field.get(certificate);
    }

}
