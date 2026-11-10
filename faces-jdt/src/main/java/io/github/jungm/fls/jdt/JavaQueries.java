package io.github.jungm.fls.jdt;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * The Java model queries {@link JdtServer} answers. Each takes the request parameters and returns the result,
 * {@link com.google.gson.JsonNull} when the thing asked for does not exist; exceptions become error responses.
 */
interface JavaQueries {
    JsonElement ping() throws Exception;

    JsonElement project(JsonObject params) throws Exception;

    JsonElement beans(JsonObject params) throws Exception;

    JsonElement type(JsonObject params) throws Exception;

    JsonElement location(JsonObject params) throws Exception;

    JsonElement javadoc(JsonObject params) throws Exception;

    JsonElement findTypes(JsonObject params) throws Exception;

    JsonElement callHierarchyPrepare(JsonObject params) throws Exception;

    JsonElement callHierarchyIncoming(JsonObject params) throws Exception;

    JsonElement callHierarchyOutgoing(JsonObject params) throws Exception;

    JsonElement member(JsonObject params) throws Exception;
}
