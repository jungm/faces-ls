package io.github.jungm.fls;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import io.github.jungm.fls.java.JType;
import io.github.jungm.fls.java.TypeService;
import io.github.jungm.fls.java.TypeService.Property;

/** {@link TypeService} and {@link io.github.jungm.fls.java.JdtClient} against {@link FakeJdt}. */
class TypeServiceTest {
    @TempDir
    Path workspace;
    FakeJdt jdt;
    TypeService types;
    final List<String> events = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        jdt = new FakeJdt(workspace, workspace.resolve("rendezvous"), events::add);
        types = new TypeService(jdt.client(), workspace.toString());
    }

    @AfterEach
    void tearDown() throws Exception {
        jdt.close();
    }

    @Test
    void failedLookupIsNotCachedAsMissingType() {
        jdt.type("a.Order", List.of(), List.of(), List.of("java.lang.String getCustomer()")).failing("a.Order");
        assertNull(types.info("a.Order"));
        assertTrue(types.members(JType.of("a.Order")).properties().isEmpty());

        jdt.recover("a.Order");
        assertNotNull(types.info("a.Order"));
        assertTrue(types.members(JType.of("a.Order")).properties().containsKey("customer"));
    }

    @Test
    void failedSupertypeLookupKeepsMembersUncached() {
        // a.Base is not part of the first answer, so it is asked for on its own and that request fails
        jdt.type("a.Sub", List.of("a.Base"), List.of(), List.of("int getAge()"));
        assertNotNull(types.info("a.Sub"));
        jdt.type("a.Base", List.of(), List.of(), List.of("java.lang.String getName()")).failing("a.Base");
        assertEquals(List.of("age"), List.copyOf(types.members(JType.of("a.Sub")).properties().keySet()));
        jdt.recover("a.Base");
        assertEquals(List.of("age", "name"), List.copyOf(types.members(JType.of("a.Sub")).properties().keySet()));
    }

    @Test
    void missingTypeIsCached() {
        assertNull(types.info("a.Missing"));
        assertNull(types.info("a.Missing"));
        assertEquals(1, jdt.requests("type"));
    }

    @Test
    void oneRequestDescribesTheWholeHierarchy() {
        jdt.type("a.Root", List.of(), List.of(), List.of("java.lang.String getId()"))
                .type("a.Base", List.of("a.Root"), List.of(), List.of("java.lang.String getName()"))
                .type("a.Sub", List.of("a.Base"), List.of(), List.of("int getAge()"));
        TypeService.Members members = types.members(JType.of("a.Sub"));
        assertEquals(List.of("age", "name", "id"), List.copyOf(members.properties().keySet()));
        assertEquals(3, types.hierarchy(JType.of("a.Sub")).size());
        assertTrue(types.isSubtype(JType.of("a.Sub"), "a.Root"));
        assertEquals(1, jdt.requests("type"));
    }

    @Test
    void invalidateDropsCachedAnswers() {
        jdt.type("a.Order", List.of(), List.of(), List.of());
        types.info("a.Order");
        types.invalidate();
        types.info("a.Order");
        assertEquals(2, jdt.requests("type"));
    }

    /** Expectations as {@code java.beans.Introspector} reports them for the same methods. */
    @Test
    void propertiesFollowIntrospector() {
        jdt.type("a.Animal", List.of(), List.of(), List.of())
                .type("a.Dog", List.of("a.Animal"), List.of(), List.of())
                .type("a.Cat", List.of(), List.of(), List.of())
                .type("a.Bean", List.of(), List.of(), List.of(
                        "java.lang.Boolean isBoxed()",
                        "void setBoxed(java.lang.Boolean)",
                        "boolean isPrim()",
                        "boolean isBoth()",
                        "boolean getBoth()",
                        "boolean isMixed()",
                        "java.lang.String getMixed()",
                        "void setMixed(boolean)",
                        "java.lang.String getMis()",
                        "void setMis(int)",
                        "java.lang.String getOver()",
                        "void setOver(int)",
                        "void setOver(java.lang.String)",
                        "java.lang.Object getCov()",
                        "void setCov(java.lang.String)",
                        "java.lang.String getFluent()",
                        "a.Bean setFluent(java.lang.String)",
                        "int getPrimInt()",
                        "void setPrimInt(java.lang.Integer)",
                        "a.Animal getPet()",
                        "void setPet(java.lang.Object)",
                        "void setPet(a.Dog)",
                        "a.Animal getOther()",
                        "void setOther(a.Cat)",
                        "java.lang.Object[] getItems()",
                        "void setItems(java.lang.String[])",
                        "void setWo(int)",
                        "void setWo(java.lang.String)"));
        Map<String, Property> properties = types.members(JType.of("a.Bean")).properties();

        // isX() only with primitive boolean; the setter alone makes a write-only property
        Property boxed = properties.get("boxed");
        assertNull(boxed.getter());
        assertEquals("setBoxed", boxed.setter().name());
        assertEquals("java.lang.Boolean", boxed.type().name());

        assertEquals("isPrim", properties.get("prim").getter().name());
        assertNull(properties.get("prim").setter());
        assertEquals("isBoth", properties.get("both").getter().name());
        assertEquals("isMixed", properties.get("mixed").getter().name());
        assertEquals("setMixed", properties.get("mixed").setter().name());

        // a setter whose parameter is not assignable to the getter's type leaves the property read-only
        assertTrue(properties.get("mis").readable());
        assertNull(properties.get("mis").setter());
        assertNull(properties.get("primInt").setter());
        assertNull(properties.get("other").setter());

        assertEquals(List.of(JType.STRING), properties.get("over").setter().parameters());
        assertEquals("setCov", properties.get("cov").setter().name());
        assertEquals("setItems", properties.get("items").setter().name());
        // the most specific assignable setter
        assertEquals("a.Dog", properties.get("pet").setter().parameters().get(0).name());

        // setters return void
        assertNull(properties.get("fluent").setter());

        assertFalse(properties.get("wo").readable());
        assertEquals("int", properties.get("wo").type().name());
    }

    @Test
    void queryTellsAnsweredNullFromNoAnswer() {
        jdt.failing("a.Busy");
        JsonObject params = new JsonObject();
        params.addProperty("path", workspace.toString());
        params.addProperty("fqn", "a.Missing");
        assertEquals(Optional.of(JsonNull.INSTANCE), jdt.client().query("type", params, 5000));
        params.addProperty("fqn", "a.Busy");
        assertEquals(Optional.empty(), jdt.client().query("type", params, 5000));
        assertEquals(JsonNull.INSTANCE, jdt.client().request("type", params));
        assertEquals(Optional.empty(), jdt.client().query("noSuchMethod", params, 5000));
    }

    @Test
    void javaChangedReachesTheListener() throws InterruptedException {
        jdt.javaChanged();
        for (int i = 0; i < 100 && events.isEmpty(); i++) {
            Thread.sleep(20);
        }
        assertEquals(List.of("javaChanged"), events);
    }

    @Test
    void closedClientGivesNoAnswer() {
        jdt.client().close();
        JsonObject params = new JsonObject();
        params.addProperty("path", workspace.toString());
        params.addProperty("fqn", "a.Missing");
        Optional<JsonElement> answer = jdt.client().query("type", params, 1000);
        assertTrue(answer.isEmpty());
        assertFalse(types.available());
        assertNull(types.info("a.Missing"));
    }
}
