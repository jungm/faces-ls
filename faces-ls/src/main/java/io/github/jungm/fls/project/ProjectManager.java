package io.github.jungm.fls.project;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
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

    public ProjectModel forFile(Path file) {
        Path root = fileToRoot.computeIfAbsent(file, this::rootFor);
        return projects.computeIfAbsent(root, r -> {
            ProjectModel model = new ProjectModel(r, client, onProjectChanged);
            model.refresh();
            return model;
        });
    }

    private Path rootFor(Path file) {
        if (client != null && client.connected()) {
            JsonObject params = new JsonObject();
            params.addProperty("path", file.toString());
            JsonElement project = client.request("project", params, 15000);
            if (project.isJsonObject() && project.getAsJsonObject().has("location")) {
                return Path.of(project.getAsJsonObject().get("location").getAsString());
            }
        }
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
        List<ProjectModel> models = new ArrayList<>(projects.values());
        projects.clear();
        for (ProjectModel model : models) {
            model.invalidateJava();
        }
    }

    public void invalidateJava() {
        for (ProjectModel model : projects.values()) {
            model.invalidateJava();
        }
    }
}
