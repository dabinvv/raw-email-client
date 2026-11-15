package com.emailclient.logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Simple custom file logger required by the TRD.
 *
 * Important:
 * - Logs INFO and ERROR only.
 * - Never logs plaintext passwords.
 * - Attempts to redact obvious password-related values.
 * - Does not crash the application if logging fails.
 */
public class SimpleFileLogger {

    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final Object LOCK = new Object();

    private static volatile SimpleFileLogger instance;

    private final Path logFile;

    /**
     * These patterns redact obvious credential-related log entries.
     *
     * However, the best practice is still:
     * NEVER pass real passwords to the logger in the first place.
     */
    private static final List<Pattern> REDACT_PATTERNS = List.of(
            Pattern.compile("(?i)(\\bPASS\\s+).+"),
            Pattern.compile("(?i)(\\bPASSWORD\\s*[=:]?\\s*).+"),
            Pattern.compile("(?i)(\\bPWD\\s*[=:]?\\s*).+"),
            Pattern.compile("(?i)(\\bSECRET\\s*[=:]?\\s*).+"),
            Pattern.compile("(?i)(\\bTOKEN\\s*[=:]?\\s*).+"),
            Pattern.compile("(?i)(\\bAUTH LOGIN\\s+).+")
    );

    private SimpleFileLogger(Path logFile) {
        this.logFile = logFile;
    }

    public static SimpleFileLogger getInstance() {
        return getInstance(Path.of("email_client.log"));
    }

    public static SimpleFileLogger getInstance(Path logFile) {
        if (instance == null) {
            synchronized (LOCK) {
                if (instance == null) {
                    instance = new SimpleFileLogger(logFile);
                }
            }
        }
        return instance;
    }

    public void info(String message) {
        write("INFO", message, null);
    }

    public void error(String message) {
        write("ERROR", message, null);
    }

    public void error(String message, Throwable throwable) {
        write("ERROR", message, throwable);
    }

    private void write(String level, String message, Throwable throwable) {
        try {
            String safeMessage = redact(message);
            safeMessage = flattenLineBreaks(safeMessage);

            String throwableText = "";
            if (throwable != null) {
                String throwableMessage = redact(String.valueOf(throwable.getMessage()));
                throwableMessage = flattenLineBreaks(throwableMessage);
                throwableText = " | " + throwable.getClass().getSimpleName() + ": " + throwableMessage;
            }

            String logLine = String.format(
                    "[%s] [%s] %s%s",
                    LocalDateTime.now().format(TIMESTAMP_FORMAT),
                    level,
                    safeMessage,
                    throwableText
            );

            synchronized (LOCK) {
                Path parent = logFile.toAbsolutePath().getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }

                Files.write(
                        logFile,
                        (logLine + System.lineSeparator()).getBytes(StandardCharsets.UTF_8),
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND
                );
            }
        } catch (IOException ex) {
            // Logger should never crash the main application.
            System.err.println("[LOGGER] Unable to write log: " + ex.getMessage());
        }
    }

    /**
     * Redacts known password-like content.
     * This is a safety net, not an excuse to log passwords.
     */
    public static String redact(String message) {
        if (message == null) {
            return "";
        }

        String result = message;

        for (Pattern pattern : REDACT_PATTERNS) {
            result = pattern.matcher(result).replaceAll("$1[REDACTED]");
        }

        // Redact standalone Base64-looking lines.
        // This helps avoid accidentally logging Base64 passwords.
        result = result.replaceAll(
                "(?m)^[A-Za-z0-9+/]{8,}={0,2}$",
                "[BASE64-REDACTED]"
        );

        return result;
    }

    private static String flattenLineBreaks(String value) {
        if (value == null) {
            return "";
        }

        return value
                .replace("\r", "")
                .replace("\n", " | ");
    }
}