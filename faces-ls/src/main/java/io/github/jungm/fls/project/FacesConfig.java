package io.github.jungm.fls.project;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import io.github.jungm.fls.xml.XmlDocument;
import io.github.jungm.fls.xml.XmlElement;

/**
 * The parts of faces-config.xml files that EL resolution needs: resource bundle variables and managed beans.
 */
public final class FacesConfig {

    public record ResourceBundleVar(String var, String baseName, Path file, int offset) {
    }

    public record ManagedBean(String name, String className, String scope, Path file, int offset) {
    }

    private final List<ResourceBundleVar> resourceBundles = new ArrayList<>();
    private final List<ManagedBean> managedBeans = new ArrayList<>();
    private final List<Path> files = new ArrayList<>();

    public List<ResourceBundleVar> resourceBundles() {
        return resourceBundles;
    }

    public List<ManagedBean> managedBeans() {
        return managedBeans;
    }

    public List<Path> files() {
        return files;
    }

    void read(Path file) {
        String text;
        try {
            text = Files.readString(file);
        } catch (IOException e) {
            return;
        }
        files.add(file);
        XmlDocument document = XmlDocument.parse(text);
        XmlElement root = document.root();
        if (root == null || !root.localName().equals("faces-config")) {
            return;
        }
        for (XmlElement section : root.children()) {
            if (section.localName().equals("application")) {
                for (XmlElement bundle : section.children()) {
                    if (bundle.localName().equals("resource-bundle")) {
                        XmlElement baseName = child(bundle, "base-name");
                        XmlElement var = child(bundle, "var");
                        if (baseName != null && var != null) {
                            resourceBundles.add(new ResourceBundleVar(var.contentText(text).trim(),
                                    baseName.contentText(text).trim(), file, var.startTagEnd()));
                        }
                    }
                }
            } else if (section.localName().equals("managed-bean")) {
                XmlElement name = child(section, "managed-bean-name");
                XmlElement className = child(section, "managed-bean-class");
                XmlElement scope = child(section, "managed-bean-scope");
                if (name != null && className != null) {
                    managedBeans.add(new ManagedBean(name.contentText(text).trim(), className.contentText(text).trim(),
                            scope == null ? null : scope.contentText(text).trim(), file, name.startTagEnd()));
                }
            }
        }
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
