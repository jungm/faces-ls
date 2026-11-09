package io.github.jungm.fls.taglib;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import io.github.jungm.fls.project.LibraryFiles;
import io.github.jungm.fls.xml.XmlDocument;
import io.github.jungm.fls.xml.XmlElement;

/**
 * Where a {@code *.taglib.xml} came from: a file, an entry in a jar, or a resource bundled with faces-ls. Jar entries
 * and bundled resources are extracted on demand, so that go to definition can open them.
 */
public record TaglibDescriptor(Path file, Path jar, String entry, String resource) {

    public static TaglibDescriptor ofFile(Path file) {
        return new TaglibDescriptor(file, null, null, null);
    }

    public static TaglibDescriptor ofJarEntry(Path jar, String entry) {
        return new TaglibDescriptor(null, jar, entry, null);
    }

    public static TaglibDescriptor ofResource(String resource) {
        return new TaglibDescriptor(null, null, null, resource);
    }

    /** A local file with the descriptor's content, or null. */
    public Path localFile() {
        if (file != null) {
            return file;
        }
        if (jar != null) {
            return LibraryFiles.extract(jar, entry);
        }
        return LibraryFiles.extractResource(resource);
    }

    /** A position in the descriptor: {@code offset} into {@code file}'s text. */
    public record Position(Path file, String text, int offset, int length) {
    }

    /**
     * The {@code <tag-name>} of a tag, or with {@code attributeName} the {@code <name>} of one of its attributes.
     * Falls back to the tag when the attribute is not declared.
     */
    public Position find(String tagName, String attributeName) {
        Path local = localFile();
        if (local == null) {
            return null;
        }
        String text;
        try {
            text = Files.readString(local);
        } catch (IOException e) {
            return null;
        }
        for (XmlElement tag : XmlDocument.parse(text).allElements()) {
            if (!tag.localName().equals("tag")) {
                continue;
            }
            XmlElement nameElement = child(tag, "tag-name");
            if (nameElement == null || !nameElement.contentText(text).trim().equals(tagName)) {
                continue;
            }
            if (attributeName != null) {
                for (XmlElement attribute : tag.children()) {
                    XmlElement name = attribute.localName().equals("attribute") ? child(attribute, "name") : null;
                    if (name != null && name.contentText(text).trim().equals(attributeName)) {
                        return contentPosition(local, text, name);
                    }
                }
            }
            return contentPosition(local, text, nameElement);
        }
        return null;
    }

    private static Position contentPosition(Path file, String text, XmlElement element) {
        String content = element.contentText(text);
        int leading = content.length() - content.stripLeading().length();
        return new Position(file, text, element.startTagEnd() + leading, content.trim().length());
    }

    private static XmlElement child(XmlElement parent, String localName) {
        for (XmlElement child : parent.children()) {
            if (child.localName().equals(localName)) {
                return child;
            }
        }
        return null;
    }
}
