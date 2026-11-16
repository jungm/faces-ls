package io.github.jungm.fls.extension;

import java.util.List;

import org.eclipse.lsp4j.Range;

/**
 * An argument of a method call in a Java document that has a constant {@code String} value.
 *
 * @param value         the value of the argument
 * @param range         the argument in the document
 * @param literal       whether the argument is one string literal, rather than a constant or a concatenation
 * @param method        name of the called method
 * @param index         position of the argument among the arguments of the call
 * @param argumentCount number of arguments of the call
 * @param receiverTypes the type the method is called on, followed by its supertypes (fully qualified, erased)
 */
public record StringArgument(String value, Range range, boolean literal, String method, int index, int argumentCount,
                             List<String> receiverTypes) {
}
