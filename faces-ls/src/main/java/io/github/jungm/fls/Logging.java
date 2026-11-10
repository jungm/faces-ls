package io.github.jungm.fls;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.logging.ConsoleHandler;
import java.util.logging.FileHandler;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Logs to stderr (editors show it in their language server log) and optionally to a file.
 */
final class Logging {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private Logging() {
    }

    static void configure(String file) {
        Logger root = Logger.getLogger("");
        for (Handler handler : root.getHandlers()) {
            root.removeHandler(handler);
        }
        Formatter formatter = new Formatter() {
            @Override
            public String format(LogRecord record) {
                StringBuilder text = new StringBuilder()
                        .append(LocalTime.now().format(TIME)).append(' ')
                        .append(record.getLevel()).append(' ')
                        .append(record.getLoggerName().replace("io.github.jungm.fls.", "")).append(": ")
                        .append(formatMessage(record)).append(System.lineSeparator());
                if (record.getThrown() != null) {
                    StringWriter trace = new StringWriter();
                    record.getThrown().printStackTrace(new PrintWriter(trace));
                    text.append(trace);
                }
                return text.toString();
            }
        };
        ConsoleHandler console = new ConsoleHandler();
        console.setFormatter(formatter);
        console.setLevel(Level.INFO);
        root.addHandler(console);
        if (file != null && !file.isBlank()) {
            try {
                FileHandler handler = new FileHandler(file, true);
                handler.setFormatter(formatter);
                handler.setLevel(Level.ALL);
                root.addHandler(handler);
            } catch (IOException e) {
                root.log(Level.WARNING, "cannot open log file " + file, e);
            }
        }
        root.setLevel(Level.INFO);
        Logger.getLogger("io.github.jungm.fls").setLevel(Level.FINE);
    }
}
