package com.emailclient.model;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Data model representing one email message.
 *
 * This object is serialized to JSON using Gson.
 *
 * TRD required fields:
 * - serverMsgId
 * - sender
 * - recipient
 * - subject
 * - receivedTimestamp
 * - rawEmailContent
 */
public class EmailMessage {

    private int serverMsgId;
    private String sender;
    private String recipient;
    private String subject;
    private String receivedTimestamp;
    private String rawEmailContent;

    /**
     * Required by Gson.
     */
    public EmailMessage() {
    }

    public EmailMessage(
            int serverMsgId,
            String sender,
            String recipient,
            String subject,
            String receivedTimestamp,
            String rawEmailContent
    ) {
        this.serverMsgId = serverMsgId;
        this.sender = sender;
        this.recipient = recipient;
        this.subject = subject;
        this.receivedTimestamp = receivedTimestamp;
        this.rawEmailContent = rawEmailContent;
    }

    /**
     * Builds an EmailMessage from the raw email returned by POP3 RETR.
     *
     * Raw email format:
     * Header-Key: value
     * Header-Key: value
     *
     * Body text
     */
    public static EmailMessage fromRawEmail(int serverMsgId, String rawEmailContent) {
        if (rawEmailContent == null) {
            rawEmailContent = "";
        }

        Map<String, String> headers = parseHeaders(rawEmailContent);

        String sender = valueOrDefault(headers.get("from"), "(unknown sender)");
        String recipient = valueOrDefault(headers.get("to"), "(unknown recipient)");
        String subject = valueOrDefault(headers.get("subject"), "(no subject)");

        String receivedTimestamp = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        return new EmailMessage(
                serverMsgId,
                sender,
                recipient,
                subject,
                receivedTimestamp,
                rawEmailContent
        );
    }

    /**
     * Convenience method for later CLI display.
     * Returns only the body portion of the raw email.
     */
    public String getBody() {
        return extractBody(rawEmailContent);
    }

    private static Map<String, String> parseHeaders(String rawEmailContent) {
        Map<String, String> headers = new HashMap<>();

        String[] headerAndBody = splitHeaderAndBody(rawEmailContent);
        String headerBlock = headerAndBody[0];

        // Normalize line endings.
        String normalized = headerBlock
                .replace("\r\n", "\n")
                .replace('\r', '\n');

        String[] lines = normalized.split("\n", -1);

        // Handle basic header folding.
        // Example:
        // Subject: This is
        //   a continued subject
        List<String> unfoldedLines = new ArrayList<>();

        for (String line : lines) {
            if ((line.startsWith(" ") || line.startsWith("\t")) && !unfoldedLines.isEmpty()) {
                int lastIndex = unfoldedLines.size() - 1;
                String previous = unfoldedLines.get(lastIndex);
                unfoldedLines.set(lastIndex, previous + " " + line.trim());
            } else {
                unfoldedLines.add(line);
            }
        }

        for (String line : unfoldedLines) {
            int colonIndex = line.indexOf(':');

            if (colonIndex > 0) {
                String key = line.substring(0, colonIndex).trim().toLowerCase();
                String value = line.substring(colonIndex + 1).trim();

                headers.put(key, value);
            }
        }

        return headers;
    }

    private static String extractBody(String rawEmailContent) {
        String[] headerAndBody = splitHeaderAndBody(rawEmailContent);
        return headerAndBody[1];
    }

    private static String[] splitHeaderAndBody(String rawEmailContent) {
        if (rawEmailContent == null || rawEmailContent.isEmpty()) {
            return new String[]{"", ""};
        }

        int crlfIndex = rawEmailContent.indexOf("\r\n\r\n");
        if (crlfIndex >= 0) {
            return new String[]{
                    rawEmailContent.substring(0, crlfIndex),
                    rawEmailContent.substring(crlfIndex + 4)
            };
        }

        int lfIndex = rawEmailContent.indexOf("\n\n");
        if (lfIndex >= 0) {
            return new String[]{
                    rawEmailContent.substring(0, lfIndex),
                    rawEmailContent.substring(lfIndex + 2)
            };
        }

        // No blank line found: treat everything as headers.
        return new String[]{rawEmailContent, ""};
    }

    private static String valueOrDefault(String value, String defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return value;
    }

    public int getServerMsgId() {
        return serverMsgId;
    }

    public void setServerMsgId(int serverMsgId) {
        this.serverMsgId = serverMsgId;
    }

    public String getSender() {
        return sender;
    }

    public void setSender(String sender) {
        this.sender = sender;
    }

    public String getRecipient() {
        return recipient;
    }

    public void setRecipient(String recipient) {
        this.recipient = recipient;
    }

    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public String getReceivedTimestamp() {
        return receivedTimestamp;
    }

    public void setReceivedTimestamp(String receivedTimestamp) {
        this.receivedTimestamp = receivedTimestamp;
    }

    public String getRawEmailContent() {
        return rawEmailContent;
    }

    public void setRawEmailContent(String rawEmailContent) {
        this.rawEmailContent = rawEmailContent;
    }

    @Override
    public String toString() {
        return "EmailMessage{" +
                "serverMsgId=" + serverMsgId +
                ", sender='" + sender + '\'' +
                ", recipient='" + recipient + '\'' +
                ", subject='" + subject + '\'' +
                ", receivedTimestamp='" + receivedTimestamp + '\'' +
                '}';
    }
}