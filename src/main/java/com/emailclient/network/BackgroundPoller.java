package com.emailclient.network;

import com.emailclient.exception.MailClientIOException;
import com.emailclient.exception.ProtocolException;
import com.emailclient.logger.SimpleFileLogger;
import com.emailclient.model.AccountConfig;
import com.emailclient.model.EmailMessage;
import com.emailclient.storage.LocalMailbox;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Background thread that periodically polls POP3 server for new mail.
 *
 * CRITICAL DESIGN CONSTRAINT (TRD §10):
 * Sockets are NOT thread-safe. Each poll cycle opens its OWN independent
 * socket connection. The poller NEVER shares sockets with the CLI thread.
 *
 * Thread safety:
 * - Uses ScheduledExecutorService with single thread
 * - LocalMailbox.saveMessageIfNew() is synchronized
 * - Shutdown is graceful: pending polls complete before termination
 */
public class BackgroundPoller {

    private final AccountConfig account;
    private final Consumer<Integer> onNewMailCallback;
    private final SimpleFileLogger logger;

    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> pollTask;
    private volatile boolean running;

    /**
     * @param account          Account configuration including poll interval
     * @param onNewMailCallback Called with count of new messages when mail arrives.
     *                          Runs on the poller thread — keep it lightweight.
     */
    public BackgroundPoller(AccountConfig account, Consumer<Integer> onNewMailCallback) {
        this.account = account;
        this.onNewMailCallback = onNewMailCallback;
        this.logger = SimpleFileLogger.getInstance();
        this.running = false;
    }

    /**
     * Starts periodic polling.
     * Does nothing if poll interval is 0 (disabled) or already running.
     */
    public synchronized void start() {
        if (running) {
            logger.info("Background poller already running for account '" + account.getName() + "'");
            return;
        }

        int interval = account.getPollIntervalSeconds();
        if (interval <= 0) {
            logger.info("Background polling disabled for account '" + account.getName()
                    + "' (interval=" + interval + ")");
            return;
        }

        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "mail-poller-" + account.getName());
            t.setDaemon(true); // Daemon thread: won't prevent JVM shutdown
            return t;
        });

        running = true;

        // Schedule with fixed delay (not fixed rate) to avoid overlapping polls
        // if a poll takes longer than the interval
        pollTask = scheduler.scheduleWithFixedDelay(
                this::pollOnce,
                interval,   // Initial delay: wait one full interval before first poll
                interval,
                TimeUnit.SECONDS
        );

        logger.info("Background poller started for account '" + account.getName()
                + "' (interval=" + interval + "s)");
    }

    /**
     * Stops polling gracefully.
     * Waits up to 10 seconds for current poll to complete.
     */
    public synchronized void stop() {
        if (!running) return;

        running = false;

        if (pollTask != null) {
            pollTask.cancel(false); // Don't interrupt mid-poll
        }

        if (scheduler != null) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                    logger.error("Background poller forced shutdown after timeout");
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        logger.info("Background poller stopped for account '" + account.getName() + "'");
    }

    /**
     * Returns true if the poller is currently active.
     */
    public synchronized boolean isRunning() {
        return running;
    }

    /**
     * Single poll cycle. Opens its OWN socket connection.
     * Never reuses any socket from the CLI thread.
     */
    private void pollOnce() {
        if (!running) return;

        try {
            logger.info("Background poll: checking account '" + account.getName() + "'");

            // INDEPENDENT SOCKET: Each poll creates its own Pop3Client
            // This satisfies TRD §10: sockets are not thread-safe
            Pop3Client pop3Client = new Pop3Client(account);
            List<EmailMessage> fetchedMessages = pop3Client.fetchAll();

            LocalMailbox mailbox = new LocalMailbox(account);
            int newCount = 0;

            for (EmailMessage message : fetchedMessages) {
                try {
                    if (mailbox.saveMessageIfNew(message)) {
                        newCount++;
                    }
                } catch (IOException e) {
                    logger.error("Background poll: failed to save message "
                            + message.getServerMsgId(), e);
                }
            }

            if (newCount > 0) {
                logger.info("Background poll: " + newCount + " new message(s) for account '"
                        + account.getName() + "'");
                onNewMailCallback.accept(newCount);
            } else {
                logger.info("Background poll: no new messages for account '"
                        + account.getName() + "'");
            }

        } catch (ProtocolException e) {
            logger.error("Background poll: protocol error for account '"
                    + account.getName() + "'", e);
        } catch (MailClientIOException e) {
            logger.error("Background poll: IO error for account '"
                    + account.getName() + "'", e);
        } catch (Exception e) {
            logger.error("Background poll: unexpected error for account '"
                    + account.getName() + "'", e);
        }
        // Exceptions are caught here so the scheduler continues polling.
        // An uncaught exception would silently kill the scheduled task.
    }
}