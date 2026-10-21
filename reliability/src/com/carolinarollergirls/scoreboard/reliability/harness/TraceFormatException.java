package com.carolinarollergirls.scoreboard.reliability.harness;

/** A trace file is invalid. The message names the line. */
public final class TraceFormatException extends Exception {
    public TraceFormatException(String message) { super(message); }

    private static final long serialVersionUID = 1L;
}
