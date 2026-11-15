package com.emailclient.model;

/**
 * Configuration for one email account.
 *
 * Stored as JSON in accounts.json.
 * Each account has isolated mailbox storage.
 */
public class AccountConfig {

    private String name;
    private String smtpHost;
    private int smtpPort;
    private String pop3Host;
    private int pop3Port;
    private String username;
    private String password;
    private boolean useTls;
    private int pollIntervalSeconds;

    /** Required by Gson */
    public AccountConfig() {}

    public AccountConfig(String name, String smtpHost, int smtpPort,
                         String pop3Host, int pop3Port,
                         String username, String password, boolean useTls) {
        this(name, smtpHost, smtpPort, pop3Host, pop3Port,
                username, password, useTls, 60);
    }

    public AccountConfig(String name, String smtpHost, int smtpPort,
                         String pop3Host, int pop3Port,
                         String username, String password, boolean useTls,
                         int pollIntervalSeconds) {
        this.name = name;
        this.smtpHost = smtpHost;
        this.smtpPort = smtpPort;
        this.pop3Host = pop3Host;
        this.pop3Port = pop3Port;
        this.username = username;
        this.password = password;
        this.useTls = useTls;
        this.pollIntervalSeconds = pollIntervalSeconds;
    }

    // --- Getters and Setters ---

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getSmtpHost() { return smtpHost; }
    public void setSmtpHost(String smtpHost) { this.smtpHost = smtpHost; }

    public int getSmtpPort() { return smtpPort; }
    public void setSmtpPort(int smtpPort) { this.smtpPort = smtpPort; }

    public String getPop3Host() { return pop3Host; }
    public void setPop3Host(String pop3Host) { this.pop3Host = pop3Host; }

    public int getPop3Port() { return pop3Port; }
    public void setPop3Port(int pop3Port) { this.pop3Port = pop3Port; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public boolean isUseTls() { return useTls; }
    public void setUseTls(boolean useTls) { this.useTls = useTls; }

    public int getPollIntervalSeconds() { return pollIntervalSeconds; }
    public void setPollIntervalSeconds(int pollIntervalSeconds) { this.pollIntervalSeconds = pollIntervalSeconds; }

    /**
     * Returns a safe display string that never exposes the password.
     */
    @Override
    public String toString() {
        return "Account{name='" + name + "', user='" + username +
               "', smtp=" + smtpHost + ":" + smtpPort +
               ", pop3=" + pop3Host + ":" + pop3Port +
               ", tls=" + useTls +
               ", poll=" + pollIntervalSeconds + "s}";
    }
}