package io.github.jungm.fls.jdt;

import org.eclipse.lsp4j.jsonrpc.services.JsonNotification;

/** The JSON-RPC notifications this bundle sends to faces-ls. */
public interface FacesJdtEvents {
    /** Java types or beans may have changed; cached answers are stale. */
    @JsonNotification
    void javaChanged();
}
