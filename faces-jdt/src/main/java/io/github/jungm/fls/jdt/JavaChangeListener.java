package io.github.jungm.fls.jdt;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.jdt.core.ElementChangedEvent;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IElementChangedListener;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaElementDelta;

/**
 * Reacts to {@link ElementChangedEvent#POST_CHANGE} deltas of the Java model: for each delta that can change types
 * or beans, {@code onChange} runs at once, and {@code onSettled} once the changes have settled for a moment.
 * <p>
 * Skipped are deltas that only flag a compilation unit becoming a primary working copy (jdtls opening a file; the
 * editor's text reaches the model through reconciling, which this listener does not see) and deltas that only
 * report non-Java resources of a project, source folder or package (Facelets pages, properties, XML). Everything
 * else counts, including a working copy being discarded, since its unsaved state may have been described already.
 */
final class JavaChangeListener implements IElementChangedListener {
    private static final int SUMMARY_LIMIT = 6;

    private final Runnable onChange;
    private final Runnable onSettled;
    private final Consumer<String> log;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(Threads.daemon("faces-jdt-events"));
    private ScheduledFuture<?> pendingChange;

    JavaChangeListener(Runnable onChange, Runnable onSettled, Consumer<String> log) {
        this.onChange = onChange;
        this.onSettled = onSettled;
        this.log = log;
    }

    void close() {
        scheduler.shutdownNow();
    }

    @Override
    public void elementChanged(ElementChangedEvent event) {
        IJavaElementDelta delta = event.getDelta();
        boolean relevant = affectsTypes(delta);
        log.accept("java delta (" + (relevant ? "invalidates" : "skipped") + "): " + summary(delta));
        if (!relevant) {
            return;
        }
        onChange.run();
        synchronized (this) {
            if (pendingChange != null) {
                pendingChange.cancel(false);
            }
            pendingChange = scheduler.schedule(onSettled, 400, TimeUnit.MILLISECONDS);
        }
    }

    /** Whether a delta may change the description of a type or the beans of a project. */
    static boolean affectsTypes(IJavaElementDelta delta) {
        if (delta.getKind() != IJavaElementDelta.CHANGED) {
            return true;
        }
        IJavaElement element = delta.getElement();
        int flags = delta.getFlags() & ~IJavaElementDelta.F_CHILDREN;
        if (element instanceof ICompilationUnit unit && unit.isWorkingCopy()) {
            flags &= ~IJavaElementDelta.F_PRIMARY_WORKING_COPY;
        }
        if (isContainer(element) && hasResourceDeltas(delta)) {
            // F_CONTENT on a container reports changed non-Java resources, listed as resource deltas
            flags &= ~IJavaElementDelta.F_CONTENT;
        }
        if (flags != 0) {
            return true;
        }
        for (IJavaElementDelta child : delta.getAffectedChildren()) {
            if (affectsTypes(child)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isContainer(IJavaElement element) {
        return switch (element.getElementType()) {
            case IJavaElement.JAVA_MODEL, IJavaElement.JAVA_PROJECT, IJavaElement.PACKAGE_FRAGMENT_ROOT,
                 IJavaElement.PACKAGE_FRAGMENT -> true;
            default -> false;
        };
    }

    private static boolean hasResourceDeltas(IJavaElementDelta delta) {
        IResourceDelta[] resources = delta.getResourceDeltas();
        return resources != null && resources.length > 0;
    }

    /** The changed leaves of a delta, e.g. {@code CHANGED Foo.java [CONTENT, PRIMARY_RESOURCE]}. */
    static String summary(IJavaElementDelta delta) {
        List<String> leaves = new ArrayList<>();
        collect(delta, leaves);
        if (leaves.size() > SUMMARY_LIMIT) {
            int more = leaves.size() - SUMMARY_LIMIT;
            leaves = new ArrayList<>(leaves.subList(0, SUMMARY_LIMIT));
            leaves.add("+" + more + " more");
        }
        return String.join("; ", leaves);
    }

    private static void collect(IJavaElementDelta delta, List<String> leaves) {
        int own = delta.getFlags() & ~IJavaElementDelta.F_CHILDREN;
        if (own != 0 || delta.getKind() != IJavaElementDelta.CHANGED || delta.getAffectedChildren().length == 0) {
            String kind = switch (delta.getKind()) {
                case IJavaElementDelta.ADDED -> "ADDED";
                case IJavaElementDelta.REMOVED -> "REMOVED";
                default -> "CHANGED";
            };
            String name = delta.getElement().getElementName();
            StringBuilder text = new StringBuilder(kind).append(' ').append(name.isEmpty() ? "<model>" : name)
                    .append(' ').append(flagNames(own));
            if (hasResourceDeltas(delta)) {
                text.append(" resources=").append(delta.getResourceDeltas().length);
            }
            leaves.add(text.toString());
        }
        for (IJavaElementDelta child : delta.getAffectedChildren()) {
            collect(child, leaves);
        }
    }

    private static List<String> flagNames(int flags) {
        List<String> names = new ArrayList<>();
        int[] values = {IJavaElementDelta.F_CONTENT, IJavaElementDelta.F_MODIFIERS, IJavaElementDelta.F_MOVED_FROM,
                IJavaElementDelta.F_MOVED_TO, IJavaElementDelta.F_ADDED_TO_CLASSPATH,
                IJavaElementDelta.F_REMOVED_FROM_CLASSPATH, IJavaElementDelta.F_REORDER, IJavaElementDelta.F_OPENED,
                IJavaElementDelta.F_CLOSED, IJavaElementDelta.F_SUPER_TYPES, IJavaElementDelta.F_SOURCEATTACHED,
                IJavaElementDelta.F_SOURCEDETACHED, IJavaElementDelta.F_FINE_GRAINED,
                IJavaElementDelta.F_ARCHIVE_CONTENT_CHANGED, IJavaElementDelta.F_PRIMARY_WORKING_COPY,
                IJavaElementDelta.F_CLASSPATH_CHANGED, IJavaElementDelta.F_PRIMARY_RESOURCE,
                IJavaElementDelta.F_AST_AFFECTED, IJavaElementDelta.F_CATEGORIES,
                IJavaElementDelta.F_RESOLVED_CLASSPATH_CHANGED, IJavaElementDelta.F_ANNOTATIONS};
        String[] labels = {"CONTENT", "MODIFIERS", "MOVED_FROM", "MOVED_TO", "ADDED_TO_CLASSPATH",
                "REMOVED_FROM_CLASSPATH", "REORDER", "OPENED", "CLOSED", "SUPER_TYPES", "SOURCEATTACHED",
                "SOURCEDETACHED", "FINE_GRAINED", "ARCHIVE_CONTENT_CHANGED", "PRIMARY_WORKING_COPY",
                "CLASSPATH_CHANGED", "PRIMARY_RESOURCE", "AST_AFFECTED", "CATEGORIES", "RESOLVED_CLASSPATH_CHANGED",
                "ANNOTATIONS"};
        int rest = flags;
        for (int i = 0; i < values.length; i++) {
            if ((flags & values[i]) != 0) {
                names.add(labels[i]);
                rest &= ~values[i];
            }
        }
        if (rest != 0) {
            names.add("0x" + Integer.toHexString(rest));
        }
        return names;
    }
}
