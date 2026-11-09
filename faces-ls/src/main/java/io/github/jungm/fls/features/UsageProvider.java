package io.github.jungm.fls.features;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.lsp4j.CallHierarchyIncomingCall;
import org.eclipse.lsp4j.CallHierarchyItem;
import org.eclipse.lsp4j.CallHierarchyOutgoingCall;
import org.eclipse.lsp4j.CodeLens;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SymbolKind;
import io.github.jungm.fls.index.FaceletsIndex;
import io.github.jungm.fls.index.FaceletsIndex.Edge;
import io.github.jungm.fls.index.FaceletsIndex.Entry;
import io.github.jungm.fls.index.FaceletsIndex.Snapshot;
import io.github.jungm.fls.xml.XmlElement;

import com.google.gson.JsonPrimitive;

/**
 * Where Facelets files are used: call hierarchy (who uses a composite, include or template, up to the pages),
 * references and a code lens with usage counts.
 */
public final class UsageProvider {
    public static final String NO_OP_COMMAND = "faces.usages";

    private final Snapshot index;

    public UsageProvider(Snapshot index) {
        this.index = index;
    }

    /** The file the cursor refers to: the target of a composite tag, include or template, else the file itself. */
    public String targetAt(String uri, int offset) {
        for (Edge edge : index.edgesNear(uri, offset)) {
            return edge.to();
        }
        return uri;
    }

    /** Named by web path (e.g. {@code /WEB-INF/angaben.xhtml}); the detail says what kind of file it is. */
    public CallHierarchyItem item(String uri) {
        Entry entry = index.entry(uri);
        String name = entry != null && entry.webPath() != null ? entry.webPath() : fileName(uri);
        String detail = entry == null ? null : kind(entry);
        Range start = new Range(new Position(0, 0), new Position(0, 0));
        boolean page = entry != null && index.isPage(entry);
        CallHierarchyItem item = new CallHierarchyItem(name, page ? SymbolKind.File : SymbolKind.Module, uri, start, start);
        item.setDetail(detail);
        item.setData(new JsonPrimitive(uri));
        return item;
    }

    private String kind(Entry entry) {
        if (index.isPage(entry)) {
            return "page";
        }
        List<Edge> usages = index.usages(entry.uri());
        if (!usages.isEmpty()) {
            return switch (usages.get(0).kind()) {
                case COMPOSITE -> "composite component";
                case TEMPLATE -> "template";
                case INCLUDE -> "include";
            };
        }
        return entry.compositeKey() != null ? "composite component" : null;
    }

    private static String fileName(String uri) {
        try {
            Path path = Path.of(URI.create(uri));
            return path.getFileName().toString();
        } catch (RuntimeException e) {
            return uri.substring(uri.lastIndexOf('/') + 1);
        }
    }

    public List<CallHierarchyIncomingCall> incoming(String uri) {
        List<CallHierarchyIncomingCall> calls = new ArrayList<>();
        for (Map.Entry<String, List<Edge>> user : index.usersOf(uri).entrySet()) {
            Entry from = index.entry(user.getKey());
            List<Range> ranges = new ArrayList<>();
            for (Edge edge : user.getValue()) {
                ranges.add(nameRange(from, edge.element()));
            }
            CallHierarchyItem item = item(user.getKey());
            if (!ranges.isEmpty()) {
                item.setSelectionRange(ranges.get(0));
                item.setRange(ranges.get(0));
            }
            calls.add(new CallHierarchyIncomingCall(item, ranges));
        }
        return calls;
    }

    public List<CallHierarchyOutgoingCall> outgoing(String uri) {
        Entry from = index.entry(uri);
        Map<String, List<Range>> byTarget = new LinkedHashMap<>();
        for (Edge edge : index.uses(uri)) {
            byTarget.computeIfAbsent(edge.to(), k -> new ArrayList<>()).add(nameRange(from, edge.element()));
        }
        List<CallHierarchyOutgoingCall> calls = new ArrayList<>();
        for (Map.Entry<String, List<Range>> target : byTarget.entrySet()) {
            calls.add(new CallHierarchyOutgoingCall(item(target.getKey()), target.getValue()));
        }
        return calls;
    }

    /** Every place the file is used (composite tags, includes, template clients). */
    public List<Location> references(String uri, boolean includeDeclaration) {
        List<Location> locations = new ArrayList<>();
        if (includeDeclaration) {
            locations.add(new Location(uri, new Range(new Position(0, 0), new Position(0, 0))));
        }
        for (Edge usage : index.usages(uri)) {
            Entry from = index.entry(usage.from());
            locations.add(new Location(usage.from(), nameRange(from, usage.element())));
        }
        return locations;
    }

    /** "used in N files · M pages" above the first line of composites, includes and templates. */
    public List<CodeLens> codeLenses(String uri) {
        Entry entry = index.entry(uri);
        if (entry == null || index.isPage(entry)) {
            return List.of();
        }
        int files = index.usersOf(uri).size();
        int pages = index.pagesContaining(uri).size();
        String title = files == 0 ? "not used by any page"
                : "used in " + files + (files == 1 ? " file" : " files") + " · " + pages + (pages == 1 ? " page" : " pages");
        Range range = new Range(new Position(0, 0), new Position(0, 0));
        return List.of(new CodeLens(range, new Command(title, NO_OP_COMMAND), null));
    }

    private static Range nameRange(Entry entry, XmlElement element) {
        if (entry == null) {
            return new Range(new Position(0, 0), new Position(0, 0));
        }
        return entry.lines().range(element.nameStart(), element.nameEnd());
    }
}
