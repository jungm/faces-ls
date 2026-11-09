package io.github.jungm.fls;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.jungm.fls.java.JdtClient;

/**
 * A stand-in for faces-jdt: answers the socket protocol of {@link JdtClient} with types declared by the test, so
 * EL can be typed without jdtls.
 */
final class FakeJdt implements AutoCloseable {
    private final Path workspace;
    private final ServerSocket server;
    private final Map<String, JsonObject> types = new HashMap<>();
    private final JdtClient client;

    FakeJdt(Path workspace, Path rendezvous) throws IOException {
        this.workspace = workspace;
        this.server = new ServerSocket(0, 5, InetAddress.getLoopbackAddress());
        Files.createDirectories(rendezvous);
        long pid = ProcessHandle.current().pid();
        JsonObject info = new JsonObject();
        info.addProperty("pid", pid);
        info.addProperty("port", server.getLocalPort());
        info.addProperty("token", "test");
        Files.writeString(rendezvous.resolve("jdtls-" + pid + ".json"), info.toString());
        Thread acceptor = new Thread(this::accept, "fake-jdt");
        acceptor.setDaemon(true);
        acceptor.start();
        client = new JdtClient(rendezvous, List.of(workspace), event -> { }, () -> { });
        client.start();
        for (int i = 0; i < 100 && !client.connected(); i++) {
            sleep();
        }
        if (!client.connected()) {
            throw new IllegalStateException("fake faces-jdt did not connect");
        }
    }

    JdtClient client() {
        return client;
    }

    /** Declares a type: {@code fields} as "name:type", "static name:type" or "enum NAME"; methods likewise. */
    FakeJdt type(String fqn, List<String> supertypes, List<String> fields, List<String> methods) {
        JsonObject type = new JsonObject();
        type.addProperty("fqn", fqn);
        type.addProperty("kind", fields.stream().anyMatch(f -> f.startsWith("enum ")) ? "enum" : "class");
        type.addProperty("source", true);
        type.add("typeParameters", new JsonArray());
        JsonArray supers = new JsonArray();
        supertypes.forEach(supers::add);
        type.add("supertypes", supers);
        JsonArray fieldArray = new JsonArray();
        for (String field : fields) {
            JsonObject f = new JsonObject();
            boolean enumConstant = field.startsWith("enum ");
            boolean isStatic = enumConstant || field.startsWith("static ");
            String rest = field.replaceFirst("^(enum|static) ", "");
            f.addProperty("name", rest.contains(":") ? rest.substring(0, rest.indexOf(':')) : rest);
            f.addProperty("type", enumConstant ? fqn : rest.substring(rest.indexOf(':') + 1));
            f.addProperty("static", isStatic);
            f.addProperty("enumConstant", enumConstant);
            fieldArray.add(f);
        }
        type.add("fields", fieldArray);
        JsonArray methodArray = new JsonArray();
        for (String method : methods) {
            // "[static ]returnType name(paramType, ...)"
            boolean isStatic = method.startsWith("static ");
            String rest = method.replaceFirst("^static ", "");
            String returnType = rest.substring(0, rest.indexOf(' '));
            String name = rest.substring(rest.indexOf(' ') + 1, rest.indexOf('('));
            String params = rest.substring(rest.indexOf('(') + 1, rest.indexOf(')')).trim();
            JsonObject m = new JsonObject();
            m.addProperty("name", name);
            JsonArray paramArray = new JsonArray();
            if (!params.isEmpty()) {
                for (String param : params.split(",")) {
                    paramArray.add(param.trim());
                }
            }
            m.add("params", paramArray);
            m.addProperty("returnType", returnType);
            m.addProperty("static", isStatic);
            m.addProperty("varargs", false);
            m.addProperty("deprecated", false);
            methodArray.add(m);
        }
        type.add("methods", methodArray);
        types.put(fqn, type);
        return this;
    }

    private void accept() {
        while (!server.isClosed()) {
            try {
                Socket socket = server.accept();
                Thread handler = new Thread(() -> serve(socket), "fake-jdt-connection");
                handler.setDaemon(true);
                handler.start();
            } catch (IOException e) {
                return;
            }
        }
    }

    private void serve(Socket socket) {
        try (socket;
             BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             Writer writer = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8)) {
            reader.readLine(); // hello with the token
            String line;
            while ((line = reader.readLine()) != null) {
                JsonObject request = JsonParser.parseString(line).getAsJsonObject();
                JsonObject response = new JsonObject();
                response.add("id", request.get("id"));
                response.add("result", answer(request.get("method").getAsString(), request.getAsJsonObject("params")));
                writer.write(response + "\n");
                writer.flush();
            }
        } catch (IOException | RuntimeException e) {
            // connection closed
        }
    }

    private JsonElement answer(String method, JsonObject params) {
        switch (method) {
            case "ping" -> {
                JsonObject project = new JsonObject();
                project.addProperty("location", workspace.toString());
                JsonArray projects = new JsonArray();
                projects.add(project);
                JsonObject result = new JsonObject();
                result.add("projects", projects);
                return result;
            }
            case "project" -> {
                JsonObject result = new JsonObject();
                result.add("sourceFolders", new JsonArray());
                result.add("classpath", new JsonArray());
                return result;
            }
            case "beans" -> {
                return new JsonArray();
            }
            case "type" -> {
                JsonObject type = types.get(params.get("fqn").getAsString());
                return type == null ? JsonNull.INSTANCE : type;
            }
            default -> {
                return JsonNull.INSTANCE;
            }
        }
    }

    private static void sleep() {
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Waits until the project's background bean search has finished, so unknown names are reported. */
    static void awaitBeans(io.github.jungm.fls.project.ProjectModel project) {
        project.beans();
        for (int i = 0; i < 100 && !project.beansLoaded(); i++) {
            sleep();
        }
    }

    @Override
    public void close() throws IOException {
        client.close();
        server.close();
    }
}
