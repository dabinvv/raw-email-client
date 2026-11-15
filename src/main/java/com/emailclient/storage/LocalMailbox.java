package com.emailclient.storage;

import com.emailclient.logger.SimpleFileLogger;
import com.emailclient.model.AccountConfig;
import com.emailclient.model.EmailMessage;
import com.emailclient.model.SearchCriteria;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/**
 * Per-account local mailbox persistence using JSON files.
 *
 * Storage layout:
 * ./mailbox/<accountName>/msg_001_<hash>.json
 *
 * Each account has its own isolated subdirectory.
 */
public class LocalMailbox {

    private static final Path MAILBOX_ROOT = Path.of("mailbox");

    private final Path accountDir;
    private final Gson gson;
    private final SimpleFileLogger logger;

    /**
     * Creates a LocalMailbox scoped to a specific account.
     */
    public LocalMailbox(AccountConfig account) {
        // Sanitize account name for filesystem safety
        String safeName = account.getName()
                .replaceAll("[^a-zA-Z0-9._-]", "_")
                .toLowerCase();
        this.accountDir = MAILBOX_ROOT.resolve(safeName);
        this.logger = SimpleFileLogger.getInstance();
        this.gson = new GsonBuilder()
                .setPrettyPrinting()
                .disableHtmlEscaping()
                .create();
    }

    /**
     * Saves one email message as a JSON file in this account's directory.
     */
    public synchronized void saveMessage(EmailMessage message) throws IOException {
        if (message == null) return;

        Files.createDirectories(accountDir);

        String fileName = buildFileName(message);
        Path targetFile = accountDir.resolve(fileName);

        String json = gson.toJson(message);

        Files.writeString(targetFile, json, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

        logger.info("Saved message to " + targetFile);
    }

    /**
     * Loads all saved emails for this account.
     */
    public List<EmailMessage> loadAllMessages() {
        List<EmailMessage> messages = new ArrayList<>();

        if (!Files.isDirectory(accountDir)) {
            return messages;
        }

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(accountDir, "*.json")) {
            for (Path file : stream) {
                try {
                    String json = Files.readString(file, StandardCharsets.UTF_8);
                    EmailMessage message = gson.fromJson(json, EmailMessage.class);
                    if (message != null) {
                        messages.add(message);
                    }
                } catch (Exception e) {
                    logger.error("Failed to load mailbox file: " + file, e);
                }
            }
        } catch (IOException e) {
            logger.error("Failed to read mailbox directory: " + accountDir, e);
        }

        messages.sort(
                Comparator
                        .comparing(EmailMessage::getReceivedTimestamp,
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparingInt(EmailMessage::getServerMsgId)
        );

        return messages;
    }

    /**
     * Searches locally stored messages for this account.
     *
     * Matches against: subject, sender, recipient, and body content.
     * Matching is case-insensitive.
     *
     * @param criteria Search parameters
     * @return List of matching messages, sorted by received timestamp
     */
    public List<EmailMessage> searchMessages(SearchCriteria criteria) {
        List<EmailMessage> allMessages = loadAllMessages();

        if (!criteria.hasQuery()) {
            return allMessages; // No query = return everything
        }

        List<EmailMessage> results = new ArrayList<>();

        for (EmailMessage message : allMessages) {
            boolean matchesSubject = criteria.matches(message.getSubject());
            boolean matchesSender = criteria.matches(message.getSender());
            boolean matchesRecipient = criteria.matches(message.getRecipient());
            boolean matchesBody = criteria.matches(message.getBody());

            if (matchesSubject || matchesSender || matchesRecipient || matchesBody) {
                results.add(message);
            }
        }

        logger.info("Search '" + criteria.getQuery() + "' returned "
                + results.size() + " of " + allMessages.size() + " messages");

        return results;
    }

    /**
     * Saves a message only if it doesn't already exist locally.
     * Thread-safe: synchronized to prevent duplicate writes from
     * concurrent poll cycles or simultaneous CLI fetch operations.
     *
     * @param message The email message to save
     * @return true if message was saved (new), false if already existed
     */
    public synchronized boolean saveMessageIfNew(EmailMessage message) throws IOException {
        if (message == null) return false;

        Files.createDirectories(accountDir);

        String fileName = buildFileName(message);
        Path targetFile = accountDir.resolve(fileName);

        // If file already exists, this message was previously saved
        if (Files.exists(targetFile)) {
            return false;
        }

        String json = gson.toJson(message);
        Files.writeString(targetFile, json, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW); // CREATE_NEW fails if exists (extra safety)

        logger.info("Background poll: saved new message " + message.getServerMsgId()
                + " to " + targetFile);
        return true;
    }

    /**
     * Returns the path to this account's mailbox directory.
     */
    public Path getAccountDir() {
        return accountDir;
    }

    private String buildFileName(EmailMessage message) {
        int id = message.getServerMsgId();
        String raw = message.getRawEmailContent() == null ? "" : message.getRawEmailContent();
        String hash = sha256Hex(raw);
        String shortHash = hash.substring(0, Math.min(16, hash.length()));
        return String.format("msg_%03d_%s.json", id, shortHash);
    }

    private String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(input.hashCode());
        }
    }
}