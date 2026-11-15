package com.emailclient.storage;

import com.emailclient.logger.SimpleFileLogger;
import com.emailclient.model.AccountConfig;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Manages email account configurations.
 *
 * Storage: ./accounts.json
 * Format: JSON array of AccountConfig objects.
 */
public class AccountManager {

    private static final Path ACCOUNTS_FILE = Path.of("accounts.json");

    private final Gson gson;
    private final SimpleFileLogger logger;
    private List<AccountConfig> accounts;

    public AccountManager() {
        this.gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        this.logger = SimpleFileLogger.getInstance();
        this.accounts = loadAccounts();
    }

    /**
     * Loads accounts from disk. Returns empty list if file doesn't exist.
     */
    private List<AccountConfig> loadAccounts() {
        if (!Files.exists(ACCOUNTS_FILE)) {
            return new ArrayList<>();
        }
        try {
            String json = Files.readString(ACCOUNTS_FILE, StandardCharsets.UTF_8);
            Type listType = new TypeToken<List<AccountConfig>>() {}.getType();
            List<AccountConfig> loaded = gson.fromJson(json, listType);
            return loaded != null ? loaded : new ArrayList<>();
        } catch (Exception e) {
            logger.error("Failed to load accounts.json", e);
            return new ArrayList<>();
        }
    }

    /**
     * Persists current account list to disk.
     */
    private void saveAccounts() throws IOException {
        String json = gson.toJson(accounts);
        Files.writeString(ACCOUNTS_FILE, json, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    public List<AccountConfig> getAllAccounts() {
        return new ArrayList<>(accounts);
    }

    public AccountConfig getAccount(int index) {
        if (index < 0 || index >= accounts.size()) return null;
        return accounts.get(index);
    }

    public int getAccountCount() {
        return accounts.size();
    }

    /**
     * Adds a new account and persists to disk.
     */
    public void addAccount(AccountConfig account) throws IOException {
        accounts.add(account);
        saveAccounts();
        logger.info("Account added: " + account.getName());
    }

    /**
     * Removes an account by index and persists to disk.
     * Does NOT delete the mailbox directory (safety measure).
     */
    public void removeAccount(int index) throws IOException {
        if (index < 0 || index >= accounts.size()) {
            throw new IllegalArgumentException("Invalid account index: " + index);
        }
        AccountConfig removed = accounts.remove(index);
        saveAccounts();
        logger.info("Account removed: " + removed.getName());
    }

    /**
     * Creates a default test account if no accounts exist.
     * Useful for first-time setup migration.
     */
    public void ensureDefaultAccount() throws IOException {
        if (accounts.isEmpty()) {
            AccountConfig defaultAccount = new AccountConfig(
                    "Local Test",
                    "127.0.0.1", 25,
                    "127.0.0.1", 110,
                    "testuser", "test123",
                    false,
                    60  // Default: poll every 60 seconds
            );
            addAccount(defaultAccount);
            System.out.println("Created default test account: " + defaultAccount.getName());
        }
    }
}