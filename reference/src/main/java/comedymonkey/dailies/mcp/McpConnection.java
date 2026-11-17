package comedymonkey.dailies.mcp;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.SequenceInputStream;
import java.net.InetAddress;
import java.net.Socket;

/**
 * One port, two ways in (specification MCP-8). With remote access on, a connection that opens with an HTTPS greeting
 * is spoken to over HTTPS and held to the token; one that opens with plain HTTP is this computer's own agents and
 * page talking as they always have, and is answered only when it really does come from this computer.
 * <p>
 * The first byte tells the two apart: every TLS greeting starts with 0x16, and no HTTP request does.
 */
public final class McpConnection implements AutoCloseable {

    /** The first byte of a TLS handshake record */
    static final int TLS_HANDSHAKE = 0x16;

    /** What a plain connection from another machine is told, in full */
    public static final String USE_HTTPS = "This server speaks HTTPS to other machines: use https:// and send the token";

    private final Socket socket;
    private final InputStream in;
    private final boolean secure;
    private final InetAddress peer;

    private McpConnection(Socket socket, InputStream in, boolean secure, InetAddress peer) {
        this.socket = socket;
        this.in = in;
        this.secure = secure;
        this.peer = peer;
    }

    public static boolean looksLikeTls(int firstByte) {
        return firstByte == TLS_HANDSHAKE;
    }

    /**
     * Reads the first byte of a new connection and makes of it what it is. The socket's read timeout applies.
     *
     * @param tls the server's side of HTTPS
     * @throws IOException when the client says nothing, or goes away
     */
    public static McpConnection open(Socket raw, SSLContext tls) throws IOException {
        InetAddress peer = raw.getInetAddress();
        InputStream rawIn = raw.getInputStream();
        int first = rawIn.read();
        if (first < 0) {
            throw new IOException("The client closed the connection without sending anything");
        }
        ByteArrayInputStream seen = new ByteArrayInputStream(new byte[]{(byte) first});
        if (!looksLikeTls(first)) {
            return new McpConnection(raw, new SequenceInputStream(seen, rawIn), false, peer);
        }
        // The byte already read is handed to the HTTPS layer, which carries on from there
        SSLSocket secure = (SSLSocket) tls.getSocketFactory().createSocket(raw, seen, true);
        secure.setUseClientMode(false);
        return new McpConnection(secure, secure.getInputStream(), true, peer);
    }

    /** A connection of a server that has no HTTPS side at all */
    public static McpConnection plain(Socket raw) throws IOException {
        return new McpConnection(raw, raw.getInputStream(), false, raw.getInetAddress());
    }

    /** Whether the connection is HTTPS, and so answers to the token, the allowed clients and the lockout */
    public boolean secure() {
        return this.secure;
    }

    /** A plain connection is this computer talking to itself, or it is not answered */
    public boolean plainAllowed() {
        return !this.secure && plainAllowedFrom(this.peer);
    }

    /** Plain HTTP is answered to 127.0.0.1 and ::1 and to nobody else, whatever the allowed clients say */
    public static boolean plainAllowedFrom(InetAddress peer) {
        return peer != null && peer.isLoopbackAddress();
    }

    public InetAddress peer() {
        return this.peer;
    }

    /** The socket to write to and to close: the HTTPS one when there is one */
    public Socket socket() {
        return this.socket;
    }

    public InputStream in() {
        return this.in;
    }

    public OutputStream out() throws IOException {
        return this.socket.getOutputStream();
    }

    @Override
    public void close() {
        try {
            this.socket.close();
        } catch (IOException ignored) {
        }
    }

}
