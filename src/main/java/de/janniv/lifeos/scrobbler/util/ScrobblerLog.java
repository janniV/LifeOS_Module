package de.janniv.lifeos.scrobbler.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.FileHandler;
import java.util.logging.Formatter;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Single shared logger writing to {@code scrobbler.log} in the module data
 * directory. Keeps the LifeOS console clean and makes remote diagnostics
 * possible — the log rotates at 1 MB / 3 files.
 */
public final class ScrobblerLog {

    private static final Logger LOG = Logger.getLogger("lifeos.scrobbler");
    private static volatile boolean wired = false;

    private ScrobblerLog() {}

    public static synchronized void initialize(Path storageDir) {
        if (wired) return;
        try {
            Files.createDirectories(storageDir);
            FileHandler fh = new FileHandler(storageDir.resolve("scrobbler.log").toString(),
                1_000_000, 3, true);
            fh.setEncoding("UTF-8");
            fh.setFormatter(new SimpleFormatter());
            LOG.addHandler(fh);
            LOG.setUseParentHandlers(false);
            LOG.setLevel(Level.INFO);
            wired = true;
        } catch (IOException e) {
            System.err.println("[Scrobbler] Could not attach log file: " + e.getMessage());
        }
    }

    public static Logger get() { return LOG; }

    public static void info(String msg) { LOG.info(msg); }
    public static void warn(String msg) { LOG.warning(msg); }
    public static void warn(String msg, Throwable t) { LOG.log(Level.WARNING, msg, t); }
    public static void error(String msg, Throwable t) { LOG.log(Level.SEVERE, msg, t); }

    private static final class SimpleFormatter extends Formatter {
        @Override
        public String format(LogRecord r) {
            StringBuilder sb = new StringBuilder()
                .append(java.time.Instant.ofEpochMilli(r.getMillis()))
                .append(' ').append(r.getLevel().getName())
                .append(' ').append(formatMessage(r))
                .append('\n');
            if (r.getThrown() != null) {
                java.io.StringWriter sw = new java.io.StringWriter();
                r.getThrown().printStackTrace(new java.io.PrintWriter(sw));
                sb.append(sw);
            }
            return sb.toString();
        }
    }
}
