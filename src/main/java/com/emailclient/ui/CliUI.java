package com.emailclient.ui;

import com.emailclient.exception.ProtocolException;
import com.emailclient.logger.SimpleFileLogger;
import com.emailclient.model.AccountConfig;
import com.emailclient.model.EmailMessage;
import com.emailclient.model.SearchCriteria;
import com.emailclient.network.BackgroundPoller;
import com.emailclient.network.Pop3Client;
import com.emailclient.network.SmtpClient;
import com.emailclient.storage.AccountManager;
import com.emailclient.storage.LocalMailbox;

import java.io.IOException;
import java.util.List;
import java.util.Scanner;

/**
 * Command-line user interface with multiple account support.
 *
 * Main Menu:
 * 1. Send email
 * 2. Fetch emails from POP3 server
 * 3. List local inbox
 * 4. View selected email
 * 5. Manage accounts
 * 6. Search inbox
 * 7. Exit
 */
public class CliUI {

    private final Scanner scanner;
    private final AccountManager accountManager;
    private final SimpleFileLogger logger;

    /** Currently selected account (null if none selected) */
    private AccountConfig currentAccount;

    /** Cached inbox for the current account */
    private List<EmailMessage> inboxCache;

    /** Background poller for the current account */
    private BackgroundPoller backgroundPoller;

    /** Flag indicating new mail arrived since last menu display */
    private volatile boolean newMailNotification;

    public CliUI() {
        this.scanner = new Scanner(System.in);
        this.accountManager = new AccountManager();
        this.logger = SimpleFileLogger.getInstance();
    }

    public void run() {
        System.out.println("========================================");
        System.out.println(" Lightweight Email Client");
        System.out.println(" Multi-Account Edition");
        System.out.println("========================================");

        // Ensure at least one account exists
        try {
            accountManager.ensureDefaultAccount();
        } catch (IOException e) {
            logger.error("Failed to initialize default account", e);
        }

        // Auto-select first account if available
        if (accountManager.getAccountCount() > 0) {
            currentAccount = accountManager.getAccount(0);
            System.out.println("Auto-selected account: " + currentAccount.getName());
            startBackgroundPoller();
        }

        try {
            while (true) {
                printMenu();
                String choice = prompt("Select option");

                if (choice == null) {
                    System.out.println("\nInput closed. Exiting.");
                    break;
                }

                switch (choice) {
                    case "1" -> sendEmail();
                    case "2" -> fetchEmails();
                    case "3" -> listInbox();
                    case "4" -> viewSelectedEmail();
                    case "5" -> manageAccounts();
                    case "6" -> searchInbox();
                    case "7" -> {
                        stopBackgroundPoller();
                        System.out.println("Exiting. Goodbye!");
                        logger.info("CLI application exited by user");
                        return;
                    }
                    default -> System.out.println("Invalid choice. Please enter 1-7.");
                }

                System.out.println();
            }
        } catch (Exception e) {
            logger.error("Unexpected CLI error", e);
            System.err.println("Unexpected error: " + e.getMessage());
        } finally {
            stopBackgroundPoller();
            scanner.close();
        }
    }

    // ==================== BACKGROUND POLLER ====================

    /**
     * Called by BackgroundPoller when new mail arrives.
     * Runs on the poller thread — must be lightweight and thread-safe.
     */
    private void onNewMailReceived(int newMessageCount) {
        newMailNotification = true;
        System.out.println("\n📬 New mail! " + newMessageCount
                + " message(s) received for account '"
                + currentAccount.getName() + "'. Use option 2 or 3 to view.");
    }

    private void startBackgroundPoller() {
        if (currentAccount == null) return;

        backgroundPoller = new BackgroundPoller(currentAccount, this::onNewMailReceived);
        backgroundPoller.start();
    }

    private void stopBackgroundPoller() {
        if (backgroundPoller != null) {
            backgroundPoller.stop();
            backgroundPoller = null;
        }
        newMailNotification = false;
    }

    // ==================== MAIN MENU ====================

    private void printMenu() {
        System.out.println();
        System.out.println("---------- Main Menu ----------");
        if (currentAccount != null) {
            System.out.println("Active account: " + currentAccount.getName()
                    + " (" + currentAccount.getUsername() + ")");
        } else {
            System.out.println("⚠ No account selected! Use option 5 to manage accounts.");
        }

        if (newMailNotification) {
            System.out.println("📬 NEW MAIL ARRIVED! Fetch or list inbox to view.");
            newMailNotification = false; // Clear after displaying once
        }

        System.out.println("-------------------------------");
        System.out.println("1. Send email");
        System.out.println("2. Fetch emails from POP3 server");
        System.out.println("3. List local inbox");
        System.out.println("4. View selected email");
        System.out.println("5. Manage accounts");
        System.out.println("6. Search inbox");
        System.out.println("7. Exit");
        System.out.println("-------------------------------");
    }

    // ==================== ACCOUNT GUARD ====================

    /**
     * Returns true if an account is selected, false otherwise.
     * Prints a warning if no account is active.
     */
    private boolean requireAccount() {
        if (currentAccount == null) {
            System.out.println("⚠ No account selected. Please use option 5 to add/select an account.");
            return false;
        }
        return true;
    }

    // ==================== SEND EMAIL ====================

    private void sendEmail() {
        if (!requireAccount()) return;

        System.out.println("\n=== Send Email ===");

        try {
            String defaultTo = currentAccount.getUsername() + "@localhost";
            String to = promptWithDefault("To", defaultTo);
            String subject = promptNonEmpty("Subject", "Test email from CLI");
            String body = readMultilineBody();

            String from = currentAccount.getUsername() + "@localhost";

            SmtpClient smtpClient = new SmtpClient(currentAccount);

            long latency = smtpClient.sendEmail(from, to, subject, body);

            System.out.println("Email sent successfully.");
            System.out.println("Send latency: " + latency + " ms");

            logger.info("CLI: email sent via account '" + currentAccount.getName()
                    + "' to " + to + " in " + latency + " ms");

        } catch (ProtocolException e) {
            logger.error("CLI SMTP protocol error", e);
            System.err.println("SMTP protocol error: " + e.getMessage());
        } catch (IOException e) {
            logger.error("CLI SMTP IO error", e);
            System.err.println("SMTP IO error: " + e.getMessage());
        } catch (Exception e) {
            logger.error("CLI unexpected send error", e);
            System.err.println("Unexpected send error: " + e.getMessage());
        }
    }

    // ==================== FETCH EMAILS ====================

    private void fetchEmails() {
        if (!requireAccount()) return;

        System.out.println("\n=== Fetch Emails ===");

        try {
            Pop3Client pop3Client = new Pop3Client(currentAccount);
            LocalMailbox mailbox = new LocalMailbox(currentAccount);

            long startTime = System.currentTimeMillis();
            List<EmailMessage> fetchedMessages = pop3Client.fetchAll();
            long latency = System.currentTimeMillis() - startTime;

            int savedCount = 0;
            int failedCount = 0;

            for (EmailMessage message : fetchedMessages) {
                try {
                    mailbox.saveMessage(message);
                    savedCount++;
                } catch (IOException e) {
                    failedCount++;
                    logger.error("Failed to save message " + message.getServerMsgId(), e);
                }
            }

            inboxCache = mailbox.loadAllMessages();

            System.out.println("Fetched " + fetchedMessages.size() + " message(s) from POP3 server.");
            System.out.println("Fetch latency: " + latency + " ms");
            System.out.println("Saved/updated " + savedCount + " message(s) locally.");

            if (failedCount > 0) {
                System.out.println("Failed to save " + failedCount + " message(s). Check email_client.log.");
            }

            System.out.println("Total local inbox size: " + inboxCache.size());

            logger.info("CLI: fetched " + fetchedMessages.size() + " message(s) via account '"
                    + currentAccount.getName() + "' in " + latency + " ms");

            // Clear new mail notification since user manually fetched
            newMailNotification = false;

        } catch (ProtocolException e) {
            logger.error("CLI POP3 protocol error", e);
            System.err.println("POP3 protocol error: " + e.getMessage());
        } catch (IOException e) {
            logger.error("CLI POP3 IO error", e);
            System.err.println("POP3 IO error: " + e.getMessage());
        } catch (Exception e) {
            logger.error("CLI unexpected fetch error", e);
            System.err.println("Unexpected fetch error: " + e.getMessage());
        }
    }

    // ==================== LIST INBOX ====================

    private void listInbox() {
        if (!requireAccount()) return;

        System.out.println("\n=== Local Inbox [" + currentAccount.getName() + "] ===");

        LocalMailbox mailbox = new LocalMailbox(currentAccount);
        inboxCache = mailbox.loadAllMessages();

        if (inboxCache.isEmpty()) {
            System.out.println("Local inbox is empty.");
            System.out.println("Use menu option 2 to fetch emails from the POP3 server.");
            return;
        }

        printInboxTable(inboxCache);
    }

    // ==================== SEARCH INBOX ====================

    private void searchInbox() {
        if (!requireAccount()) return;

        System.out.println("\n=== Search Inbox [" + currentAccount.getName() + "] ===");

        String query = prompt("Enter search keyword");
        if (query == null || query.isBlank()) {
            System.out.println("Search cancelled.");
            return;
        }

        SearchCriteria criteria = new SearchCriteria(query);
        LocalMailbox mailbox = new LocalMailbox(currentAccount);
        List<EmailMessage> results = mailbox.searchMessages(criteria);

        if (results.isEmpty()) {
            System.out.println("No messages found matching '" + query + "'.");
            return;
        }

        System.out.println("Found " + results.size() + " message(s) matching '" + query + "':");
        System.out.println();
        printInboxTable(results);

        // Allow viewing a search result directly
        System.out.println();
        String viewChoice = promptWithDefault(
                "Enter message number to view (or Enter to skip)", "");

        if (!viewChoice.isEmpty()) {
            try {
                int msgNum = Integer.parseInt(viewChoice);
                if (msgNum >= 1 && msgNum <= results.size()) {
                    EmailMessage selected = results.get(msgNum - 1);
                    displayEmail(selected, msgNum);
                } else {
                    System.out.println("Invalid message number.");
                }
            } catch (NumberFormatException e) {
                System.out.println("Invalid input.");
            }
        }
    }

    // ==================== VIEW EMAIL ====================

    private void viewSelectedEmail() {
        if (!requireAccount()) return;

        System.out.println("\n=== View Email ===");

        if (inboxCache == null) {
            LocalMailbox mailbox = new LocalMailbox(currentAccount);
            inboxCache = mailbox.loadAllMessages();
        }

        if (inboxCache.isEmpty()) {
            System.out.println("Local inbox is empty.");
            System.out.println("Use menu option 2 to fetch emails from the POP3 server.");
            return;
        }

        printInboxTable(inboxCache);

        int messageNumber = promptInt("Enter message number to view", 1, inboxCache.size());
        EmailMessage message = inboxCache.get(messageNumber - 1);
        displayEmail(message, messageNumber);
    }

    // ==================== ACCOUNT MANAGEMENT ====================

    private void manageAccounts() {
        while (true) {
            printAccountMenu();
            String choice = prompt("Account action");

            if (choice == null) break;

            switch (choice) {
                case "1" -> listAccounts();
                case "2" -> addAccount();
                case "3" -> selectAccount();
                case "4" -> removeAccount();
                case "5" -> { return; }
                default -> System.out.println("Invalid choice. Please enter 1-5.");
            }

            System.out.println();
        }
    }

    private void printAccountMenu() {
        System.out.println();
        System.out.println("------ Account Management ------");
        if (currentAccount != null) {
            System.out.println("Current: " + currentAccount.getName());
        }
        System.out.println("1. List all accounts");
        System.out.println("2. Add new account");
        System.out.println("3. Select active account");
        System.out.println("4. Remove account");
        System.out.println("5. Back to main menu");
        System.out.println("--------------------------------");
    }

    private void listAccounts() {
        List<AccountConfig> all = accountManager.getAllAccounts();
        if (all.isEmpty()) {
            System.out.println("No accounts configured.");
            return;
        }

        System.out.println();
        System.out.printf("%-4s %-20s %-25s %-20s %-5s%n",
                "No", "Name", "Username", "SMTP Server", "TLS");
        System.out.println("--------------------------------------------------------------");

        for (int i = 0; i < all.size(); i++) {
            AccountConfig a = all.get(i);
            String marker = (currentAccount != null && a.getName().equals(currentAccount.getName()))
                    ? " *" : "";
            System.out.printf("%-4d %-20s %-25s %-20s %-5s%s%n",
                    i + 1,
                    truncate(a.getName(), 20),
                    truncate(a.getUsername(), 25),
                    a.getSmtpHost() + ":" + a.getSmtpPort(),
                    a.isUseTls() ? "Yes" : "No",
                    marker
            );
        }
        System.out.println("(* = currently active)");
    }

    private void addAccount() {
        System.out.println("\n=== Add New Account ===");

        String name = promptNonEmpty("Account name", "My Account");
        String smtpHost = promptWithDefault("SMTP host", "127.0.0.1");
        int smtpPort = promptInt("SMTP port", 1, 65535, 25);
        String pop3Host = promptWithDefault("POP3 host", "127.0.0.1");
        int pop3Port = promptInt("POP3 port", 1, 65535, 110);
        String username = promptNonEmpty("Username", "testuser");
        String password = promptNonEmpty("Password", "test123");
        String tlsInput = promptWithDefault("Use TLS? (y/n)", "n");
        boolean useTls = tlsInput.equalsIgnoreCase("y") || tlsInput.equalsIgnoreCase("yes");
        int pollInterval = promptInt("Poll interval in seconds (0=disabled)", 0, 3600, 60);

        AccountConfig newAccount = new AccountConfig(
                name, smtpHost, smtpPort, pop3Host, pop3Port,
                username, password, useTls, pollInterval
        );

        try {
            accountManager.addAccount(newAccount);
            System.out.println("Account '" + name + "' added successfully.");

            // Auto-select if this is the first account
            if (accountManager.getAccountCount() == 1) {
                currentAccount = newAccount;
                System.out.println("Auto-selected as active account.");
                startBackgroundPoller();
            }
        } catch (IOException e) {
            logger.error("Failed to save new account", e);
            System.err.println("Failed to save account: " + e.getMessage());
        }
    }

    private void selectAccount() {
        listAccounts();

        int count = accountManager.getAccountCount();
        if (count == 0) {
            System.out.println("No accounts to select.");
            return;
        }

        int index = promptInt("Select account number", 1, count);
        currentAccount = accountManager.getAccount(index - 1);
        inboxCache = null; // Clear cached inbox when switching accounts

        // Stop old poller, start new one for selected account
        stopBackgroundPoller();
        startBackgroundPoller();

        System.out.println("Switched to account: " + currentAccount.getName());
        logger.info("CLI: switched to account '" + currentAccount.getName() + "'");
    }

    private void removeAccount() {
        listAccounts();

        int count = accountManager.getAccountCount();
        if (count == 0) {
            System.out.println("No accounts to remove.");
            return;
        }

        int index = promptInt("Remove account number", 1, count);
        AccountConfig toRemove = accountManager.getAccount(index - 1);

        String confirm = promptWithDefault(
                "Confirm removal of '" + toRemove.getName() + "'? (y/n)", "n");

        if (!confirm.equalsIgnoreCase("y") && !confirm.equalsIgnoreCase("yes")) {
            System.out.println("Removal cancelled.");
            return;
        }

        try {
            accountManager.removeAccount(index - 1);
            System.out.println("Account '" + toRemove.getName() + "' removed.");

            // Stop the poller (it may have been polling the removed account)
            stopBackgroundPoller();

            // If we removed the active account, deselect
            if (currentAccount != null && toRemove.getName().equals(currentAccount.getName())) {
                currentAccount = accountManager.getAccountCount() > 0
                        ? accountManager.getAccount(0)
                        : null;
                inboxCache = null;
                System.out.println(currentAccount != null
                        ? "Auto-selected: " + currentAccount.getName()
                        : "No active account. Please add or select one.");
            }

            // Start poller for the (possibly new) active account
            startBackgroundPoller();
        } catch (IOException e) {
            logger.error("Failed to remove account", e);
            System.err.println("Failed to remove account: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
        }
    }

    // ==================== DISPLAY HELPERS ====================

    private void printInboxTable(List<EmailMessage> messages) {
        System.out.printf("%-4s %-19s %-30s %s%n", "No", "Received", "From", "Subject");
        System.out.println("---------------------------------------------------------------------------------------");

        for (int i = 0; i < messages.size(); i++) {
            EmailMessage m = messages.get(i);
            System.out.printf("%-4d %-19s %-30s %s%n",
                    i + 1,
                    truncate(nullSafe(m.getReceivedTimestamp()), 19),
                    truncate(nullSafe(m.getSender()), 30),
                    truncate(nullSafe(m.getSubject()), 40)
            );
        }
    }

    private void displayEmail(EmailMessage message, int displayNumber) {
        System.out.println();
        System.out.println("========== Message #" + displayNumber + " ==========");
        System.out.println("Server message ID : " + message.getServerMsgId());
        System.out.println("From              : " + nullSafe(message.getSender()));
        System.out.println("To                : " + nullSafe(message.getRecipient()));
        System.out.println("Subject           : " + nullSafe(message.getSubject()));
        System.out.println("Received          : " + nullSafe(message.getReceivedTimestamp()));
        System.out.println();

        String body = message.getBody();
        System.out.println("---------- Body ----------");

        if (body == null || body.isBlank()) {
            System.out.println("(empty body)");
            System.out.println("\n---------- Raw Email ----------");
            System.out.println(nullSafe(message.getRawEmailContent()));
        } else {
            System.out.println(body.stripTrailing());
        }

        System.out.println("===========================");
    }

    // ==================== INPUT HELPERS ====================

    private String readMultilineBody() {
        System.out.println("Body (finish by pressing Enter on an empty line):");
        StringBuilder body = new StringBuilder();

        while (scanner.hasNextLine()) {
            String line = scanner.nextLine();
            if (line.isEmpty()) break;
            body.append(line).append('\n');
        }

        return body.toString();
    }

    private String prompt(String label) {
        System.out.print(label + ": ");
        if (!scanner.hasNextLine()) return null;
        return scanner.nextLine().trim();
    }

    private String promptWithDefault(String label, String defaultValue) {
        System.out.print(label + " [" + defaultValue + "]: ");
        if (!scanner.hasNextLine()) return defaultValue;
        String input = scanner.nextLine().trim();
        return input.isEmpty() ? defaultValue : input;
    }

    private String promptNonEmpty(String label, String defaultValue) {
        while (true) {
            String input = promptWithDefault(label, defaultValue);
            if (input != null && !input.isBlank()) return input;
            System.out.println("Value cannot be empty.");
        }
    }

    private int promptInt(String label, int min, int max) {
        return promptInt(label, min, max, min);
    }

    private int promptInt(String label, int min, int max, int defaultValue) {
        while (true) {
            System.out.print(label + " [" + min + "-" + max + "]: ");
            if (!scanner.hasNextLine()) return defaultValue;
            String input = scanner.nextLine().trim();
            if (input.isEmpty()) return defaultValue;
            try {
                int value = Integer.parseInt(input);
                if (value >= min && value <= max) return value;
                System.out.println("Please enter a number between " + min + " and " + max + ".");
            } catch (NumberFormatException e) {
                System.out.println("Invalid number.");
            }
        }
    }

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private String truncate(String value, int maxLength) {
        if (value == null) return "";
        if (value.length() <= maxLength) return value;
        if (maxLength <= 3) return value.substring(0, maxLength);
        return value.substring(0, maxLength - 3) + "...";
    }
}