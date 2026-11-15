package com.emailclient.exception;

import java.io.IOException;

/**
 * Used for low-level network/socket IO problems.
 * Examples:
 * - Connection refused
 * - Socket timeout
 * - Server closed connection unexpectedly
 * - Failed to read/write socket stream
 */
public class MailClientIOException extends IOException {

    public MailClientIOException(String message) {
        super(message);
    }

    public MailClientIOException(String message, Throwable cause) {
        super(message, cause);
    }
}