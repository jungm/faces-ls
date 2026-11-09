package io.github.jungm.fls.project;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Keys of a {@code .properties} file with the line each key is defined on.
 */
public record PropertiesFile(Path path, Map<String, Entry> entries) {

    public record Entry(String key, String value, int line) {
    }

    public static PropertiesFile read(Path path) {
        Map<String, Entry> entries = new LinkedHashMap<>();
        String text;
        try {
            text = Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            try {
                text = Files.readString(path, StandardCharsets.ISO_8859_1);
            } catch (IOException ex) {
                return new PropertiesFile(path, entries);
            }
        }
        String[] lines = text.split("\\R", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].stripLeading();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) {
                continue;
            }
            int startLine = i;
            StringBuilder logical = new StringBuilder(line);
            while (endsWithContinuation(logical) && i + 1 < lines.length) {
                logical.setLength(logical.length() - 1);
                logical.append(lines[++i].stripLeading());
            }
            String entry = logical.toString();
            int separator = -1;
            for (int j = 0; j < entry.length(); j++) {
                char c = entry.charAt(j);
                if (c == '\\') {
                    j++;
                    continue;
                }
                if (c == '=' || c == ':' || Character.isWhitespace(c)) {
                    separator = j;
                    break;
                }
            }
            String key = unescape(separator < 0 ? entry : entry.substring(0, separator));
            String value = separator < 0 ? "" : entry.substring(separator + 1).replaceFirst("^[\\s=:]+", "");
            entries.putIfAbsent(key, new Entry(key, unescape(value), startLine));
        }
        return new PropertiesFile(path, entries);
    }

    private static boolean endsWithContinuation(CharSequence line) {
        int backslashes = 0;
        for (int i = line.length() - 1; i >= 0 && line.charAt(i) == '\\'; i--) {
            backslashes++;
        }
        return backslashes % 2 == 1;
    }

    private static String unescape(String text) {
        StringBuilder result = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < text.length()) {
                char next = text.charAt(++i);
                switch (next) {
                    case 'n' -> result.append('\n');
                    case 't' -> result.append('\t');
                    case 'u' -> {
                        if (i + 4 < text.length()) {
                            try {
                                result.append((char) Integer.parseInt(text.substring(i + 1, i + 5), 16));
                                i += 4;
                            } catch (NumberFormatException e) {
                                result.append('u');
                            }
                        }
                    }
                    default -> result.append(next);
                }
            } else {
                result.append(c);
            }
        }
        return result.toString();
    }
}
