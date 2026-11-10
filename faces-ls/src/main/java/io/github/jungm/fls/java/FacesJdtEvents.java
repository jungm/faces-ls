package io.github.jungm.fls.java;

import org.eclipse.lsp4j.jsonrpc.services.JsonNotification;

/** The JSON-RPC notifications the faces-jdt bundle sends to faces-ls. */
public interface FacesJdtEvents {
    /** Java types or beans may have changed; cached answers are stale. */
    @JsonNotification
    void javaChanged();
}
