package io.github.jungm.fls.extension;

import org.eclipse.lsp4j.Position;

import io.github.jungm.fls.project.ProjectModel;

/**
 * A position in a Java document. faces-ls does not have the text of Java documents; what an extension needs of it
 * comes from jdtls, through {@link ExtensionHost}.
 *
 * @param project the project of the file
 */
public record JavaPosition(String uri, Position position, ProjectModel project) {
}
