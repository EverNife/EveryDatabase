package br.com.finalcraft.everydatabase.manager.log;

import java.util.logging.Level;

/**
 * Minimal logging seam for the manager add-on's maintenance utilities (schema sweeps, write-back
 * flush). The caller routes messages to SLF4J, JUL, a plugin logger, or nowhere; the default
 * everywhere is {@link #SILENT}, matching the library's silent-by-default posture.
 *
 * <p>Deliberately NOT the core's per-storage event log: that one models structured backend events
 * with topics and levels, while these utilities only ever need a human-readable progress line.
 * Implementations must be thread-safe - a sweep logs from its own worker thread.
 */
@FunctionalInterface
public interface ManagerLog {

    void log(Level level, String message);

    /** Drop-everything sink; the default when no logger is supplied. */
    ManagerLog SILENT = (level, message) -> { };
}
