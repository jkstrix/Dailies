package comedymonkey.dailies.mcp;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.List;

/**
 * The certificate the MCP server shows when it is reached from another machine (specification MCP-8). It is made on
 * first use, signed by itself and kept, so its SHA-256 fingerprint stays the same and a client can pin it.
 * <p>
 * Java can check certificates but has no public way to make one, so the few dozen bytes of structure a self-signed
 * certificate needs are written out here by hand, and Java's own parser reads the result back before it is used.
 * That is all this needs; a library that could do it is thousands of times the size.
 */
public final class ServerCertificate {

    private static final String ALIAS = "dailies-mcp";
    // The key file is protected by where it is, not by this: the game has to open it without asking anyone
    private static final char[] PASSWORD = "dailies".toCharArray();
    private static final int YEARS = 10;

    private final PrivateKey key;
    private final X509Certificate certificate;

    private ServerCertificate(PrivateKey key, X509Certificate certificate) {
        this.key = key;
        this.certificate = certificate;
    }

    public X509Certificate certificate() {
        return this.certificate;
    }

    /** The certificate's SHA-256 fingerprint as browsers and curl show it: AB:CD:... */
    public String fingerprint() {
        return fingerprint(this.certificate);
    }

    public static String fingerprint(Certificate certificate) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded());
            StringBuilder text = new StringBuilder();
            for (byte b : digest) {
                if (!text.isEmpty()) {
                    text.append(':');
                }
                text.append(String.format("%02X", b));
            }
            return text.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to fingerprint the certificate", e);
        }
    }

    public SSLContext context() throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(null, null);
        store.setKeyEntry(ALIAS, this.key, PASSWORD, new Certificate[]{this.certificate});
        KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(store, PASSWORD);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keys.getKeyManagers(), null, null);
        return context;
    }

    /** Whether the certificate names this address, so a client that checks names is satisfied as well as one that pins */
    public boolean names(InetAddress address) {
        try {
            Collection<List<?>> names = this.certificate.getSubjectAlternativeNames();
            if (names == null) {
                return false;
            }
            for (List<?> name : names) {
                if (Integer.valueOf(7).equals(name.get(0)) && InetAddress.getByName(String.valueOf(name.get(1))).equals(address)) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /**
     * The kept certificate, or a new one when there is none, it has run out, or it does not name an address it must.
     *
     * @param file where the certificate and its key are kept
     * @param mustName an address the certificate has to name, or null
     */
    public static ServerCertificate loadOrCreate(Path file, List<String> dnsNames, List<InetAddress> addresses, InetAddress mustName) throws Exception {
        if (Files.isRegularFile(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                KeyStore store = KeyStore.getInstance("PKCS12");
                store.load(in, PASSWORD);
                ServerCertificate kept = new ServerCertificate((PrivateKey) store.getKey(ALIAS, PASSWORD), (X509Certificate) store.getCertificate(ALIAS));
                kept.certificate.checkValidity();
                if (kept.key != null && (mustName == null || kept.names(mustName))) {
                    return kept;
                }
            } catch (Exception e) {
                // Unreadable or out of date: a new one takes its place, and its new fingerprint is announced
            }
        }
        ServerCertificate made = create(dnsNames, addresses, Instant.now());
        KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(null, null);
        store.setKeyEntry(ALIAS, made.key, PASSWORD, new Certificate[]{made.certificate});
        Files.createDirectories(file.getParent());
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        try (OutputStream out = Files.newOutputStream(temp)) {
            store.store(out, PASSWORD);
        }
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        return made;
    }

    /** A new key and a certificate for it, valid from a day before the given moment for ten years */
    public static ServerCertificate create(List<String> dnsNames, List<InetAddress> addresses, Instant now) throws Exception {
        KeyPair pair;
        String signing;
        byte[] algorithm;
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            pair = generator.generateKeyPair();
            signing = "SHA256withECDSA";
            // ecdsa-with-SHA256
            algorithm = sequence(oid(0x2A, 0x86, 0x48, 0xCE, 0x3D, 0x04, 0x03, 0x02));
        } catch (Exception e) {
            // A cut-down Java runtime may lack elliptic curves; RSA is always there
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            pair = generator.generateKeyPair();
            signing = "SHA256withRSA";
            // sha256WithRSAEncryption, with the empty parameters that algorithm insists on
            algorithm = sequence(oid(0x2A, 0x86, 0x48, 0x86, 0xF7, 0x0D, 0x01, 0x01, 0x0B), new byte[]{0x05, 0x00});
        }

        byte[] name = sequence(tag(0x31, sequence(oid(0x55, 0x04, 0x03), tag(0x0C, "Dailies MCP".getBytes(StandardCharsets.UTF_8)))));
        Instant from = now.minusSeconds(24 * 3600);
        Instant until = now.atOffset(ZoneOffset.UTC).plusYears(YEARS).toInstant();

        ByteArrayOutputStream alternatives = new ByteArrayOutputStream();
        for (String dns : dnsNames) {
            alternatives.writeBytes(tag(0x82, dns.getBytes(StandardCharsets.US_ASCII)));
        }
        for (InetAddress address : addresses) {
            alternatives.writeBytes(tag(0x87, address.getAddress()));
        }
        byte[] extensions = sequence(
            // subjectAltName: the names and addresses the server answers to
            sequence(oid(0x55, 0x1D, 0x11), tag(0x04, sequence(alternatives.toByteArray()))),
            // basicConstraints, critical, CA: lets the certificate be trusted as its own issuer
            sequence(oid(0x55, 0x1D, 0x13), new byte[]{0x01, 0x01, (byte) 0xFF}, tag(0x04, sequence(new byte[]{0x01, 0x01, (byte) 0xFF}))));

        byte[] serial = new byte[16];
        new SecureRandom().nextBytes(serial);
        byte[] body = sequence(
            tag(0xA0, new byte[]{0x02, 0x01, 0x02}), // version 3
            tag(0x02, new BigInteger(1, serial).toByteArray()),
            algorithm,
            name,
            sequence(time(from), time(until)),
            name,
            pair.getPublic().getEncoded(),
            tag(0xA3, extensions));

        Signature signature = Signature.getInstance(signing);
        signature.initSign(pair.getPrivate());
        signature.update(body);
        byte[] signed = signature.sign();
        byte[] bits = new byte[signed.length + 1];
        System.arraycopy(signed, 0, bits, 1, signed.length);
        byte[] encoded = sequence(body, algorithm, tag(0x03, bits));

        // Java's own parser has the last word on whether what was written is a certificate
        X509Certificate certificate = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(encoded));
        certificate.verify(pair.getPublic());
        return new ServerCertificate(pair.getPrivate(), certificate);
    }

    // --- The little of DER that a certificate needs ---

    private static byte[] tag(int tag, byte[] content) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(content.length + 4);
        out.write(tag);
        if (content.length < 0x80) {
            out.write(content.length);
        } else if (content.length < 0x100) {
            out.write(0x81);
            out.write(content.length);
        } else {
            out.write(0x82);
            out.write(content.length >> 8);
            out.write(content.length);
        }
        out.writeBytes(content);
        return out.toByteArray();
    }

    private static byte[] sequence(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return tag(0x30, out.toByteArray());
    }

    private static byte[] oid(int... bytes) {
        byte[] content = new byte[bytes.length];
        for (int i = 0; i < bytes.length; i++) {
            content[i] = (byte) bytes[i];
        }
        return tag(0x06, content);
    }

    private static byte[] time(Instant instant) {
        // Certificates write years up to 2049 with two digits and later ones with four
        boolean twoDigits = instant.atOffset(ZoneOffset.UTC).getYear() < 2050;
        String text = DateTimeFormatter.ofPattern(twoDigits ? "yyMMddHHmmss'Z'" : "yyyyMMddHHmmss'Z'").withZone(ZoneOffset.UTC).format(instant);
        return tag(twoDigits ? 0x17 : 0x18, text.getBytes(StandardCharsets.US_ASCII));
    }

}
