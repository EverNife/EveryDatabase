package br.com.finalcraft.everydatabase.manager.writeback;

import br.com.finalcraft.everydatabase.manager.log.ManagerLog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;

/**
 * Records what a flush reported. Several write-back paths are deliberately quiet about failure -
 * they swallow the error, fix the state and move on - so the log line is the ONLY externally visible
 * evidence that the path ran at all.
 */
class CapturingManagerLog implements ManagerLog {

    private final List<String> lines = Collections.synchronizedList(new ArrayList<>());

    @Override
    public void log(Level level, String message) {
        lines.add(level.getName() + ": " + message);
    }

    /** How many captured messages were logged at {@code level} and contain {@code fragment}. */
    int count(Level level, String fragment) {
        int hits = 0;
        for (String line : lines()) {
            if (line.startsWith(level.getName() + ": ") && line.contains(fragment)) {
                hits++;
            }
        }
        return hits;
    }

    boolean has(Level level, String fragment) {
        return count(level, fragment) > 0;
    }

    List<String> lines() {
        return new ArrayList<>(lines);
    }
}
