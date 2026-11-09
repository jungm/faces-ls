package io.github.jungm.fls;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.jungm.fls.taglib.Namespaces;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * User settings from {@code lsp.faces-ls.settings}.
 *
 * @param namespaces predefined namespace prefixes: prefix to candidate URIs, first available one wins. Completing a
 *                   tag or function of such a prefix declares it on the root element when the page lacks it.
 */
public record FacesSettings(Map<String, List<String>> namespaces) {

    /** The standard libraries under their conventional prefixes, plus PrimeFaces. */
    public static Map<String, List<String>> defaultNamespaces() {
        Map<String, List<String>> defaults = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : Namespaces.DEFAULT_PREFIXES.entrySet()) {
            defaults.put(entry.getValue(), List.of(entry.getKey()));
        }
        defaults.put("p", List.of("primefaces", "http://primefaces.org/ui"));
        return defaults;
    }

    public static FacesSettings defaults() {
        return new FacesSettings(defaultNamespaces());
    }

    /**
     * Reads {@code {"namespaces": {"p": "primefaces", "o": ["omnifaces", "http://omnifaces.org/ui"]}}}. User entries
     * replace defaults with the same prefix; an empty string or {@code null} removes a default.
     */
    public static FacesSettings from(JsonElement settings) {
        Map<String, List<String>> namespaces = defaultNamespaces();
        if (settings != null && settings.isJsonObject() && settings.getAsJsonObject().get("namespaces") instanceof JsonObject map) {
            for (Map.Entry<String, JsonElement> entry : map.entrySet()) {
                List<String> uris = new ArrayList<>();
                JsonElement value = entry.getValue();
                if (value.isJsonPrimitive()) {
                    uris.add(value.getAsString());
                } else if (value.isJsonArray()) {
                    value.getAsJsonArray().forEach(uri -> uris.add(uri.getAsString()));
                }
                uris.removeIf(String::isBlank);
                if (uris.isEmpty()) {
                    namespaces.remove(entry.getKey());
                } else {
                    namespaces.put(entry.getKey(), List.copyOf(uris));
                }
            }
        }
        return new FacesSettings(Map.copyOf(namespaces));
    }
}
