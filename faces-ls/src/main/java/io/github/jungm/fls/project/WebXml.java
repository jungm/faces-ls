package io.github.jungm.fls.project;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import io.github.jungm.fls.xml.XmlDocument;
import io.github.jungm.fls.xml.XmlElement;

/** The context parameters of a {@code WEB-INF/web.xml} that faces-ls uses. */
public record WebXml(Map<String, String> contextParams) {
    private static final Logger LOG = Logger.getLogger(WebXml.class.getName());

    public static final WebXml EMPTY = new WebXml(Map.of());

    public WebXml {
        contextParams = Collections.unmodifiableMap(new LinkedHashMap<>(contextParams));
    }

    /** The {@code WEB-INF/web.xml} of a web root, or {@link #EMPTY} when there is none. */
    public static WebXml read(Path webRoot) {
        Path file = webRoot.resolve("WEB-INF").resolve("web.xml");
        if (!Files.isRegularFile(file)) {
            return EMPTY;
        }
        try {
            return parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            LOG.log(Level.FINE, "cannot read " + file, e);
            return EMPTY;
        }
    }

    public static WebXml parse(String text) {
        Map<String, String> params = new LinkedHashMap<>();
        for (XmlElement element : XmlDocument.parse(text).allElements()) {
            if (!element.localName().equals("context-param")) {
                continue;
            }
            String name = null;
            String value = null;
            for (XmlElement child : element.children()) {
                if (child.localName().equals("param-name")) {
                    name = child.contentText(text).trim();
                } else if (child.localName().equals("param-value")) {
                    value = child.contentText(text).trim();
                }
            }
            if (name != null && value != null) {
                params.putIfAbsent(name, value);
            }
        }
        return new WebXml(params);
    }

    /** The value of a Faces parameter under any package prefix, e.g. {@code jakarta.faces.<name>}, or null. */
    public String facesParam(String name) {
        for (Map.Entry<String, String> param : contextParams.entrySet()) {
            if (param.getKey().endsWith("." + name)) {
                return param.getValue();
            }
        }
        return null;
    }

    /** The {@code FACELETS_LIBRARIES} taglib files, resolved against the web root. */
    public List<Path> faceletsLibraries(Path webRoot) {
        List<Path> result = new ArrayList<>();
        String value = facesParam("FACELETS_LIBRARIES");
        if (value == null) {
            return result;
        }
        for (String library : value.split(";")) {
            String path = library.trim();
            if (!path.isEmpty()) {
                result.add(webRoot.resolve(path.startsWith("/") ? path.substring(1) : path));
            }
        }
        return result;
    }

    /** The client id separator ({@code SEPARATOR_CHAR}), ':' by default. */
    public char separator() {
        String value = facesParam("SEPARATOR_CHAR");
        return value != null && value.length() == 1 ? value.charAt(0) : ':';
    }
}
