package io.github.jungm.fls.jdt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaElementDelta;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IPackageFragment;
import org.junit.jupiter.api.Test;

/** The delta filter, on deltas shaped like those jdtls delivers as POST_CHANGE. */
class JavaChangeListenerTest {
    private static final IJavaElementDelta[] NONE = {};

    @Test
    void openingAFileIsSkipped() {
        IJavaElementDelta delta = model(project(pkg(delta(unit("Order.java", true), IJavaElementDelta.CHANGED,
                IJavaElementDelta.F_PRIMARY_WORKING_COPY, NONE, null))));
        assertFalse(JavaChangeListener.affectsTypes(delta));
    }

    @Test
    void closingAFileCounts() {
        IJavaElementDelta delta = model(project(pkg(delta(unit("Order.java", false), IJavaElementDelta.CHANGED,
                IJavaElementDelta.F_PRIMARY_WORKING_COPY, NONE, null))));
        assertTrue(JavaChangeListener.affectsTypes(delta));
    }

    @Test
    void savingAFileCounts() {
        IJavaElementDelta delta = model(project(pkg(delta(unit("Order.java", true), IJavaElementDelta.CHANGED,
                IJavaElementDelta.F_CONTENT | IJavaElementDelta.F_PRIMARY_RESOURCE, NONE, null))));
        assertTrue(JavaChangeListener.affectsTypes(delta));
    }

    @Test
    void nonJavaResourcesAreSkipped() {
        IResourceDelta[] resources = {resource()};
        IJavaElementDelta delta = model(delta(element(IJavaProject.class, IJavaElement.JAVA_PROJECT, "web"),
                IJavaElementDelta.CHANGED, IJavaElementDelta.F_CONTENT, NONE, resources));
        assertFalse(JavaChangeListener.affectsTypes(delta));
    }

    @Test
    void classpathChangesAndNewTypesCount() {
        assertTrue(JavaChangeListener.affectsTypes(model(delta(element(IJavaProject.class, IJavaElement.JAVA_PROJECT, "web"),
                IJavaElementDelta.CHANGED, IJavaElementDelta.F_RESOLVED_CLASSPATH_CHANGED, NONE, null))));
        assertTrue(JavaChangeListener.affectsTypes(model(project(pkg(delta(unit("New.java", false),
                IJavaElementDelta.ADDED, 0, NONE, null))))));
    }

    @Test
    void summaryListsTheChangedLeaves() {
        IJavaElementDelta delta = model(project(pkg(delta(unit("Order.java", true), IJavaElementDelta.CHANGED,
                IJavaElementDelta.F_CONTENT | IJavaElementDelta.F_PRIMARY_RESOURCE, NONE, null))));
        assertEquals("CHANGED Order.java [CONTENT, PRIMARY_RESOURCE]", JavaChangeListener.summary(delta));
    }

    private static IJavaElementDelta model(IJavaElementDelta child) {
        return delta(element(IJavaElement.class, IJavaElement.JAVA_MODEL, ""), IJavaElementDelta.CHANGED,
                IJavaElementDelta.F_CHILDREN, new IJavaElementDelta[] {child}, null);
    }

    private static IJavaElementDelta project(IJavaElementDelta child) {
        return delta(element(IJavaProject.class, IJavaElement.JAVA_PROJECT, "web"), IJavaElementDelta.CHANGED,
                IJavaElementDelta.F_CHILDREN, new IJavaElementDelta[] {child}, null);
    }

    private static IJavaElementDelta pkg(IJavaElementDelta child) {
        return delta(element(IPackageFragment.class, IJavaElement.PACKAGE_FRAGMENT, "org.example"),
                IJavaElementDelta.CHANGED, IJavaElementDelta.F_CHILDREN, new IJavaElementDelta[] {child}, null);
    }

    private static ICompilationUnit unit(String name, boolean workingCopy) {
        return proxy(ICompilationUnit.class, Map.of("getElementType", IJavaElement.COMPILATION_UNIT,
                "getElementName", name, "isWorkingCopy", workingCopy));
    }

    private static <T extends IJavaElement> T element(Class<T> type, int elementType, String name) {
        return proxy(type, Map.of("getElementType", elementType, "getElementName", name));
    }

    private static IResourceDelta resource() {
        return proxy(IResourceDelta.class, Map.of());
    }

    private static IJavaElementDelta delta(IJavaElement element, int kind, int flags, IJavaElementDelta[] children,
                                           IResourceDelta[] resources) {
        Map<String, Object> answers = new HashMap<>(Map.of("getElement", element, "getKind", kind,
                "getFlags", flags, "getAffectedChildren", children));
        if (resources != null) {
            answers.put("getResourceDeltas", resources);
        }
        return proxy(IJavaElementDelta.class, answers);
    }

    /** An implementation answering the named no-argument methods; null for the others. */
    private static <T> T proxy(Class<T> type, Map<String, Object> answers) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
                (proxy, method, args) -> answers.get(method.getName())));
    }
}
