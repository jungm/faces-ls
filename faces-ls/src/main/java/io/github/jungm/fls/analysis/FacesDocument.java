package io.github.jungm.fls.analysis;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.github.jungm.fls.el.ElParser;
import io.github.jungm.fls.el.ElRegion;
import io.github.jungm.fls.taglib.CompositeComponents;
import io.github.jungm.fls.taglib.TagLibrary;
import io.github.jungm.fls.xml.XmlAttribute;
import io.github.jungm.fls.xml.XmlDocument;
import io.github.jungm.fls.xml.XmlElement;
import io.github.jungm.fls.xml.XmlText;

/**
 * An open Facelets document with its XML tree and every EL expression in it.
 */
public final class FacesDocument {

    /**
     * An EL expression in the document. {@code attribute} is null for EL in text content; {@code element} is the
     * owning element (for text, the enclosing element).
     */
    public record ElSite(ElRegion region, XmlAttribute attribute, XmlElement element, ElParser.Result parse) {
        /** True when the attribute value consists of exactly this one expression. */
        public boolean wholeValue() {
            return attribute != null && region.start() == attribute.valueStart() && region.end() == attribute.valueEnd();
        }
    }

    private final String uri;
    private final Path path;
    private final String text;
    private final int version;
    private final XmlDocument xml;
    private final LineIndex lines;
    private final List<ElSite> elSites = new ArrayList<>();
    private final Map<XmlAttribute, ElSite> attributeSites = new IdentityHashMap<>();
    /** Read on first use; empty when the document is no composite component. Reading it twice is harmless. */
    private volatile Optional<TagLibrary.TagDef> compositeInterface;

    public FacesDocument(String uri, String text, int version) {
        this.uri = uri;
        this.path = toPath(uri);
        this.text = text;
        this.version = version;
        this.xml = XmlDocument.parse(text);
        this.lines = new LineIndex(text);
        collectElSites();
    }

    private static Path toPath(String uri) {
        try {
            return Path.of(URI.create(uri));
        } catch (RuntimeException e) {
            return Path.of(uri);
        }
    }

    private void collectElSites() {
        for (XmlElement element : xml.allElements()) {
            for (XmlAttribute attribute : element.attributes()) {
                if (!attribute.hasValue()) {
                    continue;
                }
                for (ElRegion region : ElRegion.scan(text, attribute.valueStart(), attribute.valueEnd())) {
                    elSites.add(new ElSite(region, attribute, element, ElParser.parse(text, region)));
                }
            }
        }
        for (XmlText segment : xml.texts()) {
            for (ElRegion region : ElRegion.scan(text, segment.start(), segment.end())) {
                if (!xml.inComment(region.start())) {
                    elSites.add(new ElSite(region, null, segment.parent(), ElParser.parse(text, region)));
                }
            }
        }
        elSites.sort((a, b) -> Integer.compare(a.region().start(), b.region().start()));
        for (ElSite site : elSites) {
            if (site.attribute() != null) {
                attributeSites.putIfAbsent(site.attribute(), site);
            }
        }
    }

    public String uri() {
        return uri;
    }

    public Path path() {
        return path;
    }

    public String text() {
        return text;
    }

    public int version() {
        return version;
    }

    public XmlDocument xml() {
        return xml;
    }

    public LineIndex lines() {
        return lines;
    }

    public List<ElSite> elSites() {
        return elSites;
    }

    /** The first EL expression in an attribute's value, or null. */
    public ElSite siteOf(XmlAttribute attribute) {
        return attributeSites.get(attribute);
    }

    /** The EL expression whose braces contain the offset. */
    public ElSite elAt(int offset) {
        for (ElSite site : elSites) {
            if (site.region().contains(offset)) {
                return site;
            }
        }
        return null;
    }

    /** The {@code cc:interface} of this document when it is a composite component. */
    public TagLibrary.TagDef compositeInterface() {
        Optional<TagLibrary.TagDef> result = compositeInterface;
        if (result == null) {
            result = Optional.empty();
            if (CompositeComponents.findInterface(xml) != null || CompositeComponents.findImplementation(xml) != null) {
                String name = path.getFileName().toString().replaceFirst("\\.xhtml$", "");
                result = Optional.ofNullable(CompositeComponents.describe(name, uri, text));
            }
            compositeInterface = result;
        }
        return result.orElse(null);
    }
}
