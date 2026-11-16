package io.github.jungm.fls.features;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

import io.github.jungm.fls.project.LibraryFiles;
import io.github.jungm.fls.project.ProjectModel;

/**
 * Hover text that shows the files a resource reference resolves to: images rendered (VS Code shows {@code file:}
 * images in hovers), the start of text files in a code block, and size and origin for everything else.
 */
final class ResourcePreview {
    /** Extensions VS Code renders as images from any directory (its {@code vscode-file} protocol allows them). */
    private static final Set<String> IMAGES = Set.of("png", "jpg", "jpeg", "gif", "bmp", "webp", "svg");
    private static final Map<String, String> LANGUAGES = Map.ofEntries(Map.entry("css", "css"), Map.entry("scss", "scss"),
            Map.entry("less", "less"), Map.entry("js", "javascript"), Map.entry("mjs", "javascript"),
            Map.entry("ts", "typescript"), Map.entry("json", "json"), Map.entry("map", "json"),
            Map.entry("xhtml", "html"), Map.entry("html", "html"), Map.entry("htm", "html"), Map.entry("xml", "xml"),
            Map.entry("properties", "properties"), Map.entry("txt", ""));
    static final int MAX_WIDTH = 320;
    static final int MAX_HEIGHT = 240;
    static final int MAX_LINES = 25;
    private static final int MAX_LINE_LENGTH = 200;
    private static final int MAX_TEXT_BYTES = 1 << 20;
    private static final int MAX_CHILDREN = 20;
    private static final int MAX_OTHERS = 5;
    private static final Pattern SVG_TAG = Pattern.compile("<svg\\b[^>]*>", Pattern.DOTALL);
    private static final Pattern SVG_LENGTH = Pattern.compile("\\b(width|height)\\s*=\\s*[\"']\\s*([0-9.]+)\\s*(px)?\\s*[\"']");
    private static final Pattern SVG_VIEW_BOX = Pattern.compile(
            "\\bviewBox\\s*=\\s*[\"']\\s*[-0-9.]+[\\s,]+[-0-9.]+[\\s,]+([0-9.]+)[\\s,]+([0-9.]+)\\s*[\"']");

    private ResourcePreview() {
    }

    /** The preview of the first file and the locations of the others; null for no files. */
    static String markdown(ProjectModel project, List<Path> files) {
        if (files.isEmpty()) {
            return null;
        }
        StringBuilder text = new StringBuilder(preview(project, files.get(0)));
        if (files.size() > 1) {
            text.append("\n\n---\nAlso: ");
            for (int i = 1; i < files.size() && i <= MAX_OTHERS; i++) {
                text.append(i == 1 ? "" : ", ").append(code(location(project, files.get(i))));
            }
            if (files.size() - 1 > MAX_OTHERS) {
                text.append(", … (").append(files.size() - 1).append(" files)");
            }
        }
        return text.toString();
    }

    private static String preview(ProjectModel project, Path file) {
        String name = file.getFileName() == null ? file.toString() : file.getFileName().toString();
        StringBuilder text = new StringBuilder("**").append(escape(name)).append("**");
        if (Files.isDirectory(file)) {
            text.append(" — directory\n\n").append(code(location(project, file)));
            List<String> children = children(file);
            if (!children.isEmpty()) {
                text.append("\n\n");
                for (int i = 0; i < children.size() && i < MAX_CHILDREN; i++) {
                    text.append(i == 0 ? "" : ", ").append(code(children.get(i)));
                }
                if (children.size() > MAX_CHILDREN) {
                    text.append(", … (").append(children.size()).append(" entries)");
                }
            }
            return text.toString();
        }
        long size;
        try {
            size = Files.size(file);
        } catch (IOException e) {
            return text.append("\n\n").append(code(location(project, file))).toString();
        }
        String extension = extension(name);
        if (IMAGES.contains(extension)) {
            int[] dimensions = extension.equals("svg") ? svgDimensions(file) : imageDimensions(file);
            if (dimensions != null) {
                text.append(" · ").append(dimensions[0]).append(" × ").append(dimensions[1]).append(" px");
            }
            text.append(" · ").append(size(size)).append("\n\n").append(code(location(project, file))).append("\n\n")
                    .append("![").append(escape(name)).append("](").append(imageUri(file, dimensions)).append(')');
            return text.toString();
        }
        String excerpt = size <= MAX_TEXT_BYTES ? text(file) : null;
        if (excerpt == null) {
            return text.append(" · ").append(size(size)).append("\n\n").append(code(location(project, file))).toString();
        }
        List<String> lines = excerpt.lines().toList();
        text.append(" · ").append(lines.size()).append(lines.size() == 1 ? " line" : " lines").append(" · ").append(size(size))
                .append("\n\n").append(code(location(project, file)));
        if (lines.isEmpty()) {
            return text.toString();
        }
        StringBuilder shown = new StringBuilder();
        for (int i = 0; i < lines.size() && i < MAX_LINES; i++) {
            String line = lines.get(i);
            shown.append(line.length() > MAX_LINE_LENGTH ? line.substring(0, MAX_LINE_LENGTH) + "…" : line).append('\n');
        }
        String fence = "`".repeat(Math.max(3, longestRun(shown, '`') + 1));
        text.append("\n\n").append(fence).append(LANGUAGES.getOrDefault(extension, "")).append('\n').append(shown).append(fence);
        if (lines.size() > MAX_LINES) {
            int more = lines.size() - MAX_LINES;
            text.append("\n\n… ").append(more).append(more == 1 ? " more line" : " more lines");
        }
        return text.toString();
    }

    /** Where a file is: inside a jar, relative to the project, or its absolute path. */
    static String location(ProjectModel project, Path file) {
        String origin = LibraryFiles.origin(file);
        if (origin != null) {
            return origin;
        }
        Path absolute = file.toAbsolutePath().normalize();
        Path root = project.root() == null ? null : project.root().toAbsolutePath().normalize();
        Path shown = root != null && absolute.startsWith(root) && !absolute.equals(root) ? root.relativize(absolute) : absolute;
        return shown.toString().replace('\\', '/');
    }

    /** The image's URI with VS Code's {@code |width=,height=} suffix when it is larger than a hover should show. */
    private static String imageUri(Path file, int[] dimensions) {
        String uri = file.toAbsolutePath().toUri().toString().replace("(", "%28").replace(")", "%29");
        if (dimensions == null || dimensions[0] <= 0 || dimensions[1] <= 0
                || dimensions[0] <= MAX_WIDTH && dimensions[1] <= MAX_HEIGHT) {
            return uri;
        }
        double scale = Math.min((double) MAX_WIDTH / dimensions[0], (double) MAX_HEIGHT / dimensions[1]);
        return uri + "|width=" + Math.max(1, Math.round(dimensions[0] * scale)) + ",height="
                + Math.max(1, Math.round(dimensions[1] * scale));
    }

    /** Width and height from the image header, without decoding the pixels; null when unknown. */
    static int[] imageDimensions(Path file) {
        try (ImageInputStream input = ImageIO.createImageInputStream(file.toFile())) {
            if (input == null) {
                return null;
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                return null;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                return new int[] {reader.getWidth(0), reader.getHeight(0)};
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Width and height of an SVG in user units: its {@code width} and {@code height}, else its viewBox. */
    static int[] svgDimensions(Path file) {
        String head;
        try (InputStream input = Files.newInputStream(file)) {
            head = new String(input.readNBytes(8192), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
        Matcher tag = SVG_TAG.matcher(head);
        if (!tag.find()) {
            return null;
        }
        String attributes = tag.group();
        double width = -1;
        double height = -1;
        Matcher length = SVG_LENGTH.matcher(attributes);
        while (length.find()) {
            double value = Double.parseDouble(length.group(2));
            if (length.group(1).equals("width")) {
                width = value;
            } else {
                height = value;
            }
        }
        if (width <= 0 || height <= 0) {
            Matcher viewBox = SVG_VIEW_BOX.matcher(attributes);
            if (!viewBox.find()) {
                return null;
            }
            width = Double.parseDouble(viewBox.group(1));
            height = Double.parseDouble(viewBox.group(2));
        }
        return width > 0 && height > 0 ? new int[] {(int) Math.round(width), (int) Math.round(height)} : null;
    }

    /** The file as text, or null when it looks binary (has NUL bytes). */
    private static String text(Path file) {
        try {
            byte[] bytes = Files.readAllBytes(file);
            for (byte b : bytes) {
                if (b == 0) {
                    return null;
                }
            }
            String text = new String(bytes, StandardCharsets.UTF_8);
            return text.startsWith("\uFEFF") ? text.substring(1) : text;
        } catch (IOException e) {
            return null;
        }
    }

    private static List<String> children(Path dir) {
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(p -> p.getFileName() + (Files.isDirectory(p) ? "/" : "")).sorted().toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    static String size(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        }
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024));
    }

    private static int longestRun(CharSequence text, char c) {
        int longest = 0;
        int run = 0;
        for (int i = 0; i < text.length(); i++) {
            run = text.charAt(i) == c ? run + 1 : 0;
            longest = Math.max(longest, run);
        }
        return longest;
    }

    private static String code(String text) {
        String ticks = "`".repeat(longestRun(text, '`') + 1);
        String pad = text.startsWith("`") || text.endsWith("`") ? " " : "";
        return ticks + pad + text + pad + ticks;
    }

    private static String escape(String text) {
        return text.replaceAll("([\\\\`*_\\[\\]<>#!|])", "\\\\$1");
    }
}
