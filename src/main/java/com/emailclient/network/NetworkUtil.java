package com.emailclient.network;

import com.emailclient.exception.MailClientIOException;
import com.emailclient.exception.ProtocolException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.Base64;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Shared network helper methods.
 *
 * Important protocol rules implemented here:
 * - SMTP/POP3 commands must end with CRLF.
 * - Socket read timeout must be configured.
 * - POP3 multiline responses end with a single dot line.
 * - POP3 byte-stuffing: lines beginning with ".." become ".".
 * - SMTP DATA dot-stuffing: lines beginning with "." become "..".
 */
public final class NetworkUtil {

    public static final String CRLF = "\r\n";
    public static final int DEFAULT_TIMEOUT_MS = 5000;

    private NetworkUtil() {
        // Utility class. Do not instantiate.
    }

    /**
     * Creates a TCP socket connection and configures timeout.
     */
    public static Socket connect(String host, int port) throws MailClientIOException {
        try {
            Socket socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), DEFAULT_TIMEOUT_MS);
            socket.setSoTimeout(DEFAULT_TIMEOUT_MS);
            return socket;
        } catch (IOException e) {
            throw new MailClientIOException(
                    "Failed to connect to " + host + ":" + port,
                    e
            );
        }
    }

    /**
     * Opens a UTF-8 reader for the socket input stream.
     */
    public static BufferedReader openReader(Socket socket) throws MailClientIOException {
        try {
            return new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)
            );
        } catch (IOException e) {
            throw new MailClientIOException("Failed to open socket reader", e);
        }
    }

    /**
     * Opens the socket output stream.
     */
    public static OutputStream openOutputStream(Socket socket) throws MailClientIOException {
        try {
            return socket.getOutputStream();
        } catch (IOException e) {
            throw new MailClientIOException("Failed to open socket output stream", e);
        }
    }

    /**
     * Sends one protocol line.
     * Automatically appends CRLF.
     *
     * Example:
     * sendLine(out, "USER testuser")
     * actually sends:
     * USER testuser\r\n
     */
    public static void sendLine(OutputStream out, String line) throws MailClientIOException {
        if (out == null) {
            throw new MailClientIOException("Output stream is null");
        }

        if (line == null) {
            throw new MailClientIOException("Command line is null");
        }

        try {
            out.write((line + CRLF).getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (IOException e) {
            throw new MailClientIOException("Failed to send command: ", e);
        }
    }

    /**
     * Reads one line from the server.
     * Throws if the server closes the connection.
     */
    public static String readLine(BufferedReader reader) throws MailClientIOException {
        try {
            String line = reader.readLine();

            if (line == null) {
                throw new MailClientIOException("Server closed the connection unexpectedly");
            }

            return line;
        } catch (SocketTimeoutException e) {
            throw new MailClientIOException("Timeout while reading from server", e);
        } catch (IOException e) {
            throw new MailClientIOException("IO error while reading from server", e);
        }
    }

    /**
     * Reads a full SMTP response.
     *
     * SMTP responses can be multiline:
     * 250-mail.example.com
     * 250-SIZE 10240000
     * 250 HELP
     *
     * Continuation lines usually have:
     * code + '-'
     *
     * Final line usually has:
     * code + ' '
     */
    public static String readSmtpResponse(BufferedReader reader) throws MailClientIOException {
        StringBuilder response = new StringBuilder();

        try {
            String line;

            while ((line = reader.readLine()) != null) {
                if (response.length() > 0) {
                    response.append('\n');
                }

                response.append(line);

                if (isSmtpFinalLine(line)) {
                    break;
                }
            }
        } catch (SocketTimeoutException e) {
            throw new MailClientIOException("Timeout while reading SMTP response", e);
        } catch (IOException e) {
            throw new MailClientIOException("IO error while reading SMTP response", e);
        }

        if (response.length() == 0) {
            throw new MailClientIOException("SMTP server closed connection before sending response");
        }

        return response.toString();
    }

    public static boolean isSmtpFinalLine(String line) {
        if (line == null) {
            return true;
        }

        // Some malformed or minimal responses may be only 3 chars.
        if (line.length() <= 3) {
            return true;
        }

        // SMTP final line example: "250 OK"
        // SMTP continuation example: "250-STARTTLS"
        return line.charAt(3) == ' ';
    }

    /**
     * Extracts the numeric SMTP reply code.
     *
     * Example:
     * "250 OK" -> 250
     * "354 End data with <CR><LF>.<CR><LF>" -> 354
     */
    public static int getSmtpReplyCode(String smtpResponse) throws ProtocolException {
        if (smtpResponse == null || smtpResponse.length() < 3) {
            throw new ProtocolException("Empty or malformed SMTP response");
        }

        String firstLine = smtpResponse.split("\n", 2)[0];

        if (firstLine.length() < 3) {
            throw new ProtocolException("Malformed SMTP response: " + firstLine);
        }

        try {
            return Integer.parseInt(firstLine.substring(0, 3));
        } catch (NumberFormatException e) {
            throw new ProtocolException(
                    "SMTP response does not start with numeric code: " + firstLine,
                    e
            );
        }
    }

    /**
     * Validates that an SMTP response has the expected code.
     */
    public static void requireSmtpCode(String response, int expectedCode, String stage)
            throws ProtocolException {
        int actualCode = getSmtpReplyCode(response);

        if (actualCode != expectedCode) {
            String compactResponse = response.replace('\n', ' ');
            throw new ProtocolException(
                    stage + " failed. Expected SMTP code " + expectedCode +
                    ", got " + actualCode + ". Server response: " + compactResponse
            );
        }
    }

    /**
     * Validates that a POP3 response starts with +OK.
     */
    public static void requirePop3Ok(String response, String stage) throws ProtocolException {
        if (response == null || !response.startsWith("+OK")) {
            throw new ProtocolException(
                    stage + " failed. Server response: " +
                    (response == null ? "null" : response)
            );
        }
    }

    /**
     * Reads a POP3 multiline response.
     *
     * Used for:
     * - LIST
     * - RETR
     *
     * POP3 multiline responses end with:
     * .
     *
     * POP3 byte-stuffing rule:
     * If a line starts with "..", remove one dot.
     */
    public static String readPop3Multiline(BufferedReader reader) throws MailClientIOException {
        StringBuilder payload = new StringBuilder();
        boolean terminated = false;

        try {
            String line;

            while ((line = reader.readLine()) != null) {
                if (".".equals(line)) {
                    terminated = true;
                    break;
                }

                // POP3 byte-stuffing rule.
                if (line.startsWith("..")) {
                    line = line.substring(1);
                }

                payload.append(line).append(CRLF);
            }
        } catch (SocketTimeoutException e) {
            throw new MailClientIOException("Timeout while reading POP3 multiline response", e);
        } catch (IOException e) {
            throw new MailClientIOException("IO error while reading POP3 multiline response", e);
        }

        if (!terminated) {
            throw new MailClientIOException("POP3 multiline response did not terminate with '.'");
        }

        return payload.toString();
    }

    /**
     * Encodes text as Base64.
     * Required for SMTP AUTH LOGIN.
     *
     * Important:
     * Do not add newline characters to the encoded output.
     */
    public static String base64Encode(String value) {
        if (value == null) {
            return "";
        }

        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * SMTP DATA dot-stuffing.
     *
     * If any body line starts with ".", it must become "..".
     * This prevents the server from confusing the body with
     * the end-of-data single dot line.
     */
    public static String escapeSmtpData(String body) {
        if (body == null) {
            return CRLF;
        }

        // Normalize line endings before applying SMTP CRLF rules.
        String normalized = body
                .replace("\r\n", "\n")
                .replace('\r', '\n');

        String[] lines = normalized.split("\n", -1);

        StringBuilder escaped = new StringBuilder();

        for (String line : lines) {
            if (line.startsWith(".")) {
                escaped.append('.');
            }

            escaped.append(line).append(CRLF);
        }

        return escaped.toString();
    }

    // ==================== TLS / SSL HELPERS ====================

    /**
     * Creates an SSLSocketFactory that trusts ALL certificates.
     *
     * WARNING: This is ONLY safe for local testing with self-signed certs.
     * Never use this in production against real mail servers.
     *
     * For local Postfix/Dovecot testing, the server uses a self-signed
     * certificate that Java's default trust store rejects. This factory
     * bypasses that check so STARTTLS handshakes succeed locally.
     */
    public static SSLSocketFactory createTrustAllSslSocketFactory() throws MailClientIOException {
        try {
            TrustManager[] trustAllCerts = new TrustManager[]{
                    new X509TrustManager() {
                        @Override
                        public void checkClientTrusted(X509Certificate[] chain, String authType) {
                            // Trust all client certs (not relevant for email client)
                        }

                        @Override
                        public void checkServerTrusted(X509Certificate[] chain, String authType) {
                            // Trust all server certs — LOCAL TESTING ONLY
                        }

                        @Override
                        public X509Certificate[] getAcceptedIssuers() {
                            return new X509Certificate[0];
                        }
                    }
            };

            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustAllCerts, new java.security.SecureRandom());
            return sslContext.getSocketFactory();

        } catch (Exception e) {
            throw new MailClientIOException("Failed to create SSL socket factory", e);
        }
    }

    /**
     * Wraps an existing plaintext socket into an SSLSocket.
     *
     * Used after STARTTLS/STLS command succeeds.
     * The original socket's streams become invalid after this call —
     * you MUST open new reader/outputStream from the returned SSLSocket.
     *
     * @param plainSocket The existing connected plaintext socket
     * @param host        Server hostname for SNI (Server Name Indication)
     * @param port        Server port
     * @return SSLSocket wrapping the original connection
     */
    public static SSLSocket upgradeToTls(Socket plainSocket, String host, int port)
            throws MailClientIOException {
        try {
            SSLSocketFactory factory = createTrustAllSslSocketFactory();

            // Create SSL socket layered over the existing plaintext socket
            SSLSocket sslSocket = (SSLSocket) factory.createSocket(
                    plainSocket,
                    host,
                    port,
                    true  // autoClose: close underlying socket when SSL socket closes
            );

            // Enable modern TLS protocols only
            sslSocket.setEnabledProtocols(new String[]{"TLSv1.2", "TLSv1.3"});

            // Perform the TLS handshake
            sslSocket.startHandshake();

            return sslSocket;

        } catch (IOException e) {
            throw new MailClientIOException(
                    "TLS handshake failed for " + host + ":" + port, e
            );
        }
    }

    /**
     * Creates a direct TLS socket connection (for ports 465/995).
     *
     * Unlike upgradeToTls(), this creates a brand-new SSL socket
     * without any prior plaintext communication.
     */
    public static SSLSocket connectTls(String host, int port) throws MailClientIOException {
        try {
            SSLSocketFactory factory = createTrustAllSslSocketFactory();
            SSLSocket sslSocket = (SSLSocket) factory.createSocket(host, port);
            sslSocket.setEnabledProtocols(new String[]{"TLSv1.2", "TLSv1.3"});
            sslSocket.setSoTimeout(DEFAULT_TIMEOUT_MS);
            sslSocket.startHandshake();
            return sslSocket;
        } catch (IOException e) {
            throw new MailClientIOException(
                    "Failed to establish direct TLS connection to " + host + ":" + port, e
            );
        }
    }
}