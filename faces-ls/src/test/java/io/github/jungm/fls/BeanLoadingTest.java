package io.github.jungm.fls;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import io.github.jungm.fls.project.ProjectModel;

/** The background bean search of a project, and what happens when the project changes while it runs. */
class BeanLoadingTest {
    @TempDir
    Path workspace;

    private static void await(BooleanSupplier condition, String what) throws InterruptedException {
        for (int i = 0; i < 200 && !condition.getAsBoolean(); i++) {
            Thread.sleep(25);
        }
        assertTrue(condition.getAsBoolean(), what);
    }

    private static List<String> names(List<ProjectModel.Bean> beans) {
        return beans.stream().map(ProjectModel.Bean::name).toList();
    }

    @Test
    void searchAnsweredAfterAChangeRunsAgainAndNotifies() throws Exception {
        Files.writeString(workspace.resolve("pom.xml"), "<project/>");
        AtomicInteger changes = new AtomicInteger();
        try (FakeJdt jdt = new FakeJdt(workspace, workspace.resolve("rendezvous"))) {
            jdt.bean("orderBean", "org.example.OrderBean").hold("beans", params -> true);
            ProjectModel project = new ProjectModel(workspace, jdt.client(), changes::incrementAndGet);
            project.refresh();

            project.beans();
            await(() -> jdt.requests("beans") == 1, "bean search started");
            // the answer in flight describes the project before the change
            project.invalidateJava();
            jdt.release("beans");

            await(project::beansLoaded, "beans loaded");
            assertEquals(2, jdt.requests("beans"));
            assertEquals(List.of("orderBean"), names(project.beans()));
            await(() -> changes.get() == 1, "change notified");
        }
    }

    @Test
    void discardedProjectStopsSearching() throws Exception {
        Files.writeString(workspace.resolve("pom.xml"), "<project/>");
        AtomicInteger changes = new AtomicInteger();
        try (FakeJdt jdt = new FakeJdt(workspace, workspace.resolve("rendezvous"))) {
            jdt.hold("beans", params -> true);
            ProjectModel project = new ProjectModel(workspace, jdt.client(), changes::incrementAndGet);
            project.refresh();

            project.beans();
            await(() -> jdt.requests("beans") == 1, "bean search started");
            project.discard();
            jdt.release("beans");

            Thread.sleep(300);
            assertEquals(1, jdt.requests("beans"));
            assertEquals(0, changes.get());
        }
    }
}
