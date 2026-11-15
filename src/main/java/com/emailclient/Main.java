package com.emailclient;

import com.emailclient.ui.CliUI;

/**
 * Application entry point.
 *
 * Starts the CLI email client.
 */
public class Main {

    public static void main(String[] args) {
        CliUI cli = new CliUI();
        cli.run();
    }
}