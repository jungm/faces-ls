package io.github.jungm.fls.java;

import java.util.concurrent.CompletableFuture;

import org.eclipse.lsp4j.jsonrpc.services.JsonRequest;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * The JSON-RPC requests faces-ls sends to the faces-jdt bundle inside jdtls (the bundle declares the same interface).
 * {@code authenticate} has to come first; every query takes the path of a file in the project ({@code path}) besides
 * its own parameters.
 */
public interface FacesJdtService {
    /** {@code {token}} from the rendezvous file; answers {@code {pid, bundleVersion}}. */
    @JsonRequest
    CompletableFuture<JsonObject> authenticate(JsonObject params);

    /** {@code {pid, projects: [{name, location}]}}: the Java projects jdtls has imported. */
    @JsonRequest
    CompletableFuture<JsonElement> ping(JsonObject params);

    /** {@code {name, location, sourceFolders, classpath}} of the project containing {@code path}. */
    @JsonRequest
    CompletableFuture<JsonElement> project(JsonObject params);

    /** {@code [{name, type, kind, scope?, location, library?}]}: the named beans visible in the project. */
    @JsonRequest
    CompletableFuture<JsonElement> beans(JsonObject params);

    /** {@code {fqn}}: the description of the type followed by the descriptions of all its supertypes. */
    @JsonRequest
    CompletableFuture<JsonElement> type(JsonObject params);

    /** {@code {fqn, kind, name?, paramCount}}: {@code {uri, range}} of a member's name. */
    @JsonRequest
    CompletableFuture<JsonElement> location(JsonObject params);

    /** {@code {fqn, kind, name?, paramCount}}: {@code {markdown}} of a member's Javadoc. */
    @JsonRequest
    CompletableFuture<JsonElement> javadoc(JsonObject params);

    /** {@code {prefix, limit?}}: {@code [{fqn, simpleName}]} of public types. */
    @JsonRequest
    CompletableFuture<JsonElement> findTypes(JsonObject params);

    /** {@code {uri, line, character}}: jdtls' call hierarchy items at a position. */
    @JsonRequest("callHierarchy/prepare")
    CompletableFuture<JsonElement> callHierarchyPrepare(JsonObject params);

    /** {@code {item}}: the incoming calls of an item. */
    @JsonRequest("callHierarchy/incoming")
    CompletableFuture<JsonElement> callHierarchyIncoming(JsonObject params);

    /** {@code {item}}: the outgoing calls of an item. */
    @JsonRequest("callHierarchy/outgoing")
    CompletableFuture<JsonElement> callHierarchyOutgoing(JsonObject params);

    /**
     * {@code {uri, line, character}}: {@code {declaringType, name, kind, parameterCount}} of the Java member named at a
     * position of a Java file, where it is declared or used; null when there is none.
     */
    @JsonRequest
    CompletableFuture<JsonElement> member(JsonObject params);
}
