package io.github.jungm.fls.extension;

import org.eclipse.lsp4j.Range;

import io.github.jungm.fls.project.ProjectModel;

/**
 * A range of a Java document. faces-ls does not have the text of Java documents; what an extension needs of it comes
 * from jdtls, through {@link ExtensionHost}.
 *
 * @param project the project of the file
 */
public record JavaRange(String uri, Range range, ProjectModel project) {
}
