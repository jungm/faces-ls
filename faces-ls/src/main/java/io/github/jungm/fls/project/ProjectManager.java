package io.github.jungm.fls.project;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import io.github.jungm.fls.java.JdtClient;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Maps files to projects. With jdtls the Java project containing the file decides; without it the nearest
 * directory with a build file does.
 */
public final class ProjectManager {
    private final List<Path> workspaceRoots;
    private final JdtClient client;
    private final Map<Path, ProjectModel> projects = new ConcurrentHashMap<>();
    private final Map<Path, Path> fileToRoot = new ConcurrentHashMap<>();

    private final Runnable onProjectChanged;

    public ProjectManager(List<Path> workspaceRoots, JdtClient client) {
        this(workspaceRoots, client, () -> { });
    }

    /** @param onProjectChanged runs when background loading (beans) changed what a project knows */
    public ProjectManager(List<Path> workspaceRoots, JdtClient client, Runnable onProjectChanged) {
        this.workspaceRoots = workspaceRoots;
        this.client = client;
        this.onProjectChanged = onProjectChanged;
    }

    public Collection<ProjectModel> projects() {
        return projects.values();
    }

    /**
     * The project of a file, refreshed on first use. The jdtls round trips run outside the maps, so threads asking
     * for other files never wait on them; threads asking for the same new project wait for its first refresh.
     */
    public ProjectModel forFile(Path file) {
        Path root = fileToRoot.get(file);
        if (root == null) {
            root = rootFor(file);
        }
        ProjectModel model = projects.computeIfAbsent(root, r -> new ProjectModel(r, client, onProjectChanged));
        model.ensureRefreshed();
        return model;
    }

    /**
     * The root of the project containing the file: the location of jdtls' Java project, or else the nearest
     * directory with a build file. It is remembered unless jdtls was connected but gave no answer (timeout, error),
     * so that the next lookup asks again rather than keeping the file in the wrong project.
     */
    private Path rootFor(Path file) {
        boolean answered = true;
        if (client != null && client.connected()) {
            JsonObject params = new JsonObject();
            params.addProperty("path", file.toString());
            Optional<JsonElement> project = client.query("project", params, 15000);
            answered = project.isPresent();
            if (answered && project.get() instanceof JsonObject json && json.has("location")) {
                Path root = Path.of(json.get("location").getAsString());
                fileToRoot.put(file, root);
                return root;
            }
        }
        Path root = buildRoot(file);
        if (answered) {
            fileToRoot.put(file, root);
        }
        return root;
    }

    private Path buildRoot(Path file) {
        Path workspace = workspaceRoots.stream().filter(file::startsWith).findFirst().orElse(null);
        for (Path dir = file.getParent(); dir != null; dir = dir.getParent()) {
            for (String marker : List.of("pom.xml", "build.gradle", "build.gradle.kts")) {
                if (Files.isRegularFile(dir.resolve(marker))) {
                    return dir;
                }
            }
            if (dir.equals(workspace)) {
                break;
            }
        }
        return workspace != null ? workspace : file.getParent();
    }

    /** Called when jdtls connects or disconnects: project boundaries and classpaths may change. */
    public void reset() {
        fileToRoot.clear();
        for (Path root : new ArrayList<>(projects.keySet())) {
            ProjectModel model = projects.remove(root);
            if (model != null) {
                model.discard();
            }
        }
    }

    public void invalidateJava() {
        for (ProjectModel model : projects.values()) {
            model.invalidateJava();
        }
    }
}
