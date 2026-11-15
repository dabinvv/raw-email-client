package com.emailclient.exception;

/**
 * Used when the mail server returns an unexpected or invalid protocol response.
 * Examples:
 * - SMTP returns 535 authentication failed
 * - POP3 returns -ERR authentication failed
 * - Unexpected server greeting
 */
public class ProtocolException extends Exception {

    public ProtocolException(String message) {
        super(message);
    }

    public ProtocolException(String message, Throwable cause) {
        super(message, cause);
    }
}