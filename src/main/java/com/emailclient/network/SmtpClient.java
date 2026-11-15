package com.emailclient.network;

import com.emailclient.exception.MailClientIOException;
import com.emailclient.exception.ProtocolException;
import com.emailclient.logger.SimpleFileLogger;
import com.emailclient.model.AccountConfig;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

import javax.net.ssl.SSLSocket;

/**
 * SMTP client using raw TCP sockets.
 * Refactored for Phase B1: accepts AccountConfig instead of individual parameters.
 *
 * Implements mandatory SMTP flow:
 * - connect
 * - EHLO
 * - AUTH LOGIN (Base64) — optional, skipped if server returns 503/504
 * - MAIL FROM
 * - RCPT TO
 * - DATA (headers + body + dot-stuffing)
 * - QUIT
 */
public class SmtpClient {

    private final AccountConfig account;
    private final SimpleFileLogger logger;

    public SmtpClient(AccountConfig account) {
        this.account = account;
        this.logger = SimpleFileLogger.getInstance();
    }

    /**
     * Sends an email with optional STARTTLS support.
     *
     * If account.isUseTls() is true:
     * - Port 465: Direct TLS connection
     * - Port 25/587: STARTTLS upgrade after EHLO
     *
     * If account.isUseTls() is false:
     * - Plaintext connection (original behavior)
     *
     * @param from    Sender email address (e.g., testuser@localhost)
     * @param to      Recipient email address
     * @param subject Email subject
     * @param body    Plain text email body
     * @return Time taken to send the email in milliseconds
     */
    public long sendEmail(String from, String to, String subject, String body)
            throws ProtocolException, IOException {

        logger.info("Connecting to SMTP server " + account.getSmtpHost() + ":" + account.getSmtpPort()
                + (account.isUseTls() ? " [TLS]" : " [PLAINTEXT]")
                + " [account: " + account.getName() + "]");

        long startTime = System.currentTimeMillis();

        // Port 465 uses implicit (direct) TLS — no plaintext phase at all.
        boolean directTls = account.isUseTls() && account.getSmtpPort() == 465;

        try (
                Socket rawSocket = directTls
                        ? NetworkUtil.connectTls(account.getSmtpHost(), account.getSmtpPort())
                        : NetworkUtil.connect(account.getSmtpHost(), account.getSmtpPort())
        ) {
            // These are reassigned after the TLS upgrade if STARTTLS is used.
            Socket activeSocket = rawSocket;
            BufferedReader reader = NetworkUtil.openReader(activeSocket);
            OutputStream out = NetworkUtil.openOutputStream(activeSocket);

            // 1. Server Greeting (Expect 220)
            String greeting = NetworkUtil.readSmtpResponse(reader);
            logger.info("SMTP <<< " + greeting.replace('\n', ' '));
            NetworkUtil.requireSmtpCode(greeting, 220, "SMTP Greeting");

            // 2. Initial EHLO (Expect 250)
            String ehloResponse = sendCommandAndGetResponse(
                    out, reader, "EHLO localhost", "SMTP EHLO");
            NetworkUtil.requireSmtpCode(ehloResponse, 250, "SMTP EHLO");

            // 3. STARTTLS if enabled and not already using direct TLS
            if (account.isUseTls() && !directTls) {
                if (ehloResponse.contains("STARTTLS")) {
                    sendCommandAndGetResponse(out, reader, "STARTTLS", "SMTP STARTTLS");

                    // Upgrade the existing plaintext socket to TLS.
                    logger.info("Performing TLS handshake...");
                    SSLSocket sslSocket = NetworkUtil.upgradeToTls(
                            activeSocket,
                            account.getSmtpHost(),
                            account.getSmtpPort()
                    );

                    // IMPORTANT: Old streams are invalid after the TLS upgrade.
                    // Open NEW reader/writer from the SSL socket.
                    activeSocket = sslSocket;
                    reader = NetworkUtil.openReader(activeSocket);
                    out = NetworkUtil.openOutputStream(activeSocket);

                    logger.info("TLS handshake successful");

                    // RFC 3207 §4.2 mandates re-EHLO after STARTTLS.
                    String ehloAfterTls = sendCommandAndGetResponse(
                            out, reader, "EHLO localhost", "SMTP EHLO (post-TLS)");
                    NetworkUtil.requireSmtpCode(ehloAfterTls, 250, "SMTP EHLO (post-TLS)");
                } else {
                    // Fail fast rather than silently downgrading to plaintext.
                    logger.error("SMTP STARTTLS requested but server does not advertise STARTTLS capability");
                    throw new ProtocolException(
                            "Account requires TLS but SMTP server does not advertise STARTTLS in EHLO response"
                    );
                }
            }

            // 4. Attempt AUTH LOGIN (Optional for local/trusted servers)
            logger.info("SMTP >>> AUTH LOGIN");
            NetworkUtil.sendLine(out, "AUTH LOGIN");
            String authResponse = NetworkUtil.readSmtpResponse(reader);
            logger.info("SMTP <<< " + authResponse.replace('\n', ' '));

            int authCode = NetworkUtil.getSmtpReplyCode(authResponse);

            if (authCode == 334) {
                // Server expects authentication — proceed with Base64 credentials
                String b64User = NetworkUtil.base64Encode(account.getUsername());
                sendCommandRaw(out, reader, b64User, 334, "SMTP Username");

                // IMPORTANT: Never log the actual Base64 password string
                String b64Pass = NetworkUtil.base64Encode(account.getPassword());
                logger.info("SMTP >>> [Base64 Password REDACTED]");
                NetworkUtil.sendLine(out, b64Pass);
                String passResp = NetworkUtil.readSmtpResponse(reader);
                logger.info("SMTP <<< " + passResp.replace('\n', ' '));
                NetworkUtil.requireSmtpCode(passResp, 235, "SMTP Authentication");

                logger.info("SMTP authentication successful");
            } else if (authCode == 503 || authCode == 504 || authCode == 502) {
                // Server does not require/support authentication.
                // Drain any remaining multi-line response data to sync stream.
                while (!NetworkUtil.isSmtpFinalLine(authResponse)) {
                    authResponse = NetworkUtil.readSmtpResponse(reader);
                    logger.info("SMTP <<< (drained) " + authResponse.replace('\n', ' '));
                }
                logger.info("SMTP authentication not required by server — proceeding without AUTH");
            } else {
                throw new ProtocolException(
                        "Unexpected SMTP AUTH LOGIN response: " + authCode + " - " + authResponse);
            }

            // 5. RSET — Safety reset to clear any stale server state
            sendCommandRaw(out, reader, "RSET", 250, "SMTP RSET");

            // 6. MAIL FROM (Expect 250)
            sendCommandRaw(out, reader, "MAIL FROM:<" + from + ">", 250, "SMTP MAIL FROM");

            // 7. RCPT TO (Expect 250)
            sendCommandRaw(out, reader, "RCPT TO:<" + to + ">", 250, "SMTP RCPT TO");

            // 8. DATA (Expect 354)
            sendCommandRaw(out, reader, "DATA", 354, "SMTP DATA");

            // 9. Construct Payload (Headers + Body)
            StringBuilder payload = new StringBuilder();
            payload.append("From: ").append(from).append(NetworkUtil.CRLF);
            payload.append("To: ").append(to).append(NetworkUtil.CRLF);
            payload.append("Subject: ").append(subject).append(NetworkUtil.CRLF);
            payload.append("Date: ").append(ZonedDateTime.now()
                    .format(DateTimeFormatter.RFC_1123_DATE_TIME)).append(NetworkUtil.CRLF);
            payload.append(NetworkUtil.CRLF); // Blank line separating headers and body
            payload.append(body);

            // 10. Apply Dot-Stuffing and Send Payload
            String escapedPayload = NetworkUtil.escapeSmtpData(payload.toString());
            try {
                out.write(escapedPayload.getBytes(StandardCharsets.UTF_8));
                out.flush();
            } catch (IOException e) {
                throw new MailClientIOException("Failed to send email payload", e);
            }

            // 11. Terminate DATA with single dot (Expect 250)
            sendCommandRaw(out, reader, ".", 250, "SMTP DATA Termination");

            // 12. QUIT (Expect 221)
            sendCommandRaw(out, reader, "QUIT", 221, "SMTP QUIT");

            long latency = System.currentTimeMillis() - startTime;

            logger.info("Email sent successfully via account '"
                    + account.getName() + "' in " + latency + " ms"
                    + (account.isUseTls() ? " [TLS]" : ""));
            return latency;

        } catch (ProtocolException | IOException e) {
            logger.error("SMTP send failed [account: " + account.getName() + "]", e);
            throw e;
        }
    }

    /**
     * Sends a command and returns the raw response string.
     * Does NOT validate the response code — the caller decides what to check.
     * Needed by STARTTLS, which inspects the EHLO response for the STARTTLS keyword.
     */
    private String sendCommandAndGetResponse(
            OutputStream out, BufferedReader reader,
            String command, String stage
    ) throws IOException, ProtocolException {
        logger.info("SMTP >>> " + command);
        NetworkUtil.sendLine(out, command);
        String response = NetworkUtil.readSmtpResponse(reader);
        logger.info("SMTP <<< " + response.replace('\n', ' '));
        return response;
    }

    /**
     * Sends a command and validates that the response has the expected code.
     */
    private void sendCommandRaw(
            OutputStream out, BufferedReader reader,
            String command, int expectedCode, String stage
    ) throws IOException, ProtocolException {
        String response = sendCommandAndGetResponse(out, reader, command, stage);
        NetworkUtil.requireSmtpCode(response, expectedCode, stage);
    }
}