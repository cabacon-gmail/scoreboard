package com.carolinarollergirls.scoreboard.reliability.runner;

/** The manifest could not be read or is invalid. Reported as an infrastructure error (exit code 2). */
public final class ManifestException extends Exception {
    public ManifestException(String message) { super(message); }
    public ManifestException(String message, Throwable cause) { super(message, cause); }

    private static final long serialVersionUID = 1L;
}
