package com.emailclient.network;

import com.emailclient.exception.MailClientIOException;
import com.emailclient.exception.ProtocolException;
import com.emailclient.logger.SimpleFileLogger;
import com.emailclient.model.AccountConfig;
import com.emailclient.model.EmailMessage;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

import javax.net.ssl.SSLSocket;

/**
 * POP3 client using raw TCP sockets.
 * Refactored for Phase B1: accepts AccountConfig instead of individual parameters.
 *
 * Implements mandatory POP3 flow:
 * - connect
 * - USER
 * - PASS
 * - LIST
 * - RETR
 * - QUIT
 */
public class Pop3Client {

    private final AccountConfig account;
    private final SimpleFileLogger logger;

    public Pop3Client(AccountConfig account) {
        this.account = account;
        this.logger = SimpleFileLogger.getInstance();
    }

    /**
     * Fetches all messages from the POP3 server with optional TLS support.
     *
     * If account.isUseTls() is true:
     * - Port 995: Direct TLS connection
     * - Port 110: STLS upgrade after greeting
     *
     * If account.isUseTls() is false:
     * - Plaintext connection (original behavior)
     *
     * @return List of parsed EmailMessage objects
     */
    public List<EmailMessage> fetchAll() throws ProtocolException, IOException {
        logger.info("Connecting to POP3 server " + account.getPop3Host() + ":" + account.getPop3Port()
                + (account.isUseTls() ? " [TLS]" : " [PLAINTEXT]")
                + " [account: " + account.getName() + "]");

        // Port 995 uses implicit (direct) TLS — no plaintext phase at all.
        boolean directTls = account.isUseTls() && account.getPop3Port() == 995;

        try (
                Socket rawSocket = directTls
                        ? NetworkUtil.connectTls(account.getPop3Host(), account.getPop3Port())
                        : NetworkUtil.connect(account.getPop3Host(), account.getPop3Port())
        ) {
            // This is reassigned after the TLS upgrade if STLS is used.
            Socket activeSocket = rawSocket;
            BufferedReader reader = NetworkUtil.openReader(activeSocket);
            OutputStream out = NetworkUtil.openOutputStream(activeSocket);

            // Server greeting
            String greeting = NetworkUtil.readLine(reader);
            logger.info("POP3 <<< " + greeting);
            NetworkUtil.requirePop3Ok(greeting, "POP3 server greeting");

            // STLS if enabled and not already using direct TLS
            if (account.isUseTls() && !directTls) {
                logger.info("POP3 >>> STLS");
                NetworkUtil.sendLine(out, "STLS");
                String stlsResp = NetworkUtil.readLine(reader);
                logger.info("POP3 <<< " + stlsResp);
                NetworkUtil.requirePop3Ok(stlsResp, "POP3 STLS");

                logger.info("Performing POP3 TLS handshake...");
                SSLSocket sslSocket = NetworkUtil.upgradeToTls(
                        activeSocket,
                        account.getPop3Host(),
                        account.getPop3Port()
                );

                // IMPORTANT: Old streams are invalid after the TLS upgrade.
                // Open NEW reader/writer from the SSL socket.
                activeSocket = sslSocket;
                reader = NetworkUtil.openReader(activeSocket);
                out = NetworkUtil.openOutputStream(activeSocket);

                logger.info("POP3 TLS handshake successful");
            }

            // USER — never log the real password
            sendCommand(
                    out, reader,
                    "USER " + account.getUsername(),
                    "USER " + account.getUsername(),
                    "POP3 USER command"
            );

            // PASS
            sendCommand(
                    out, reader,
                    "PASS " + account.getPassword(),
                    "PASS [REDACTED]",
                    "POP3 PASS command"
            );

            // LIST
            List<Integer> messageIds = listMessageIds(out, reader);

            List<EmailMessage> messages = new ArrayList<>();

            if (messageIds.isEmpty()) {
                logger.info("POP3 inbox has no messages [account: " + account.getName() + "]");
            }

            // RETR each message
            for (int messageId : messageIds) {
                String rawEmail = retrieveMessage(out, reader, messageId);
                EmailMessage message = EmailMessage.fromRawEmail(messageId, rawEmail);
                messages.add(message);
                logger.info("Retrieved message " + messageId + " [account: " + account.getName() + "]");
            }

            // QUIT
            sendCommand(
                    out, reader,
                    "QUIT",
                    "QUIT",
                    "POP3 QUIT command"
            );

            return messages;
        } catch (ProtocolException | IOException e) {
            logger.error("POP3 fetch failed [account: " + account.getName() + "]", e);
            throw e;
        }
    }

    private List<Integer> listMessageIds(OutputStream out, BufferedReader reader)
            throws IOException, ProtocolException {

        sendCommand(out, reader, "LIST", "LIST", "POP3 LIST command");

        String multilineResponse = NetworkUtil.readPop3Multiline(reader);
        List<Integer> messageIds = new ArrayList<>();

        String[] lines = multilineResponse.split("\r\n");

        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;

            // LIST line example: "1 1234"
            String[] parts = line.split("\\s+", 2);

            try {
                int messageId = Integer.parseInt(parts[0]);
                messageIds.add(messageId);
            } catch (NumberFormatException e) {
                logger.error("Ignoring malformed POP3 LIST line: " + line);
            }
        }

        return messageIds;
    }

    private String retrieveMessage(OutputStream out, BufferedReader reader, int messageId)
            throws IOException, ProtocolException {

        sendCommand(
                out, reader,
                "RETR " + messageId,
                "RETR " + messageId,
                "POP3 RETR command"
        );

        return NetworkUtil.readPop3Multiline(reader);
    }

    private void sendCommand(
            OutputStream out,
            BufferedReader reader,
            String command,
            String safeLogCommand,
            String stage
    ) throws IOException, ProtocolException {

        logger.info("POP3 >>> " + safeLogCommand);
        NetworkUtil.sendLine(out, command);

        String response = NetworkUtil.readLine(reader);
        logger.info("POP3 <<< " + response);

        NetworkUtil.requirePop3Ok(response, stage);
    }
}