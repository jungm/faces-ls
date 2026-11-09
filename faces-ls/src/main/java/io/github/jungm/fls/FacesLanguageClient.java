package io.github.jungm.fls;

import org.eclipse.lsp4j.jsonrpc.services.JsonNotification;
import org.eclipse.lsp4j.services.LanguageClient;

import com.google.gson.JsonObject;

/** The LSP client plus faces-ls' own notifications, which clients without support for them ignore. */
public interface FacesLanguageClient extends LanguageClient {
    /**
     * {@code {"connected": boolean}}: whether faces-ls reaches faces-jdt in jdtls, i.e. whether it answers requests
     * on Java files such as the call hierarchy with Facelets callers.
     */
    @JsonNotification("faces/javaStatus")
    void javaStatus(JsonObject status);
}
