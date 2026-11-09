package io.github.jungm.fls.analysis;

import io.github.jungm.fls.java.TypeService;

/**
 * A name an EL expression can start with.
 *
 * @param declarationUri    document declaring the variable (tag variables, bundles), or null
 * @param declarationOffset offset in that document, or -1
 * @param javaLocation      Java source declaring it (beans), or null
 */
public record ElVariable(String name, ElType type, Kind kind, String detail, String declarationUri,
                         int declarationOffset, TypeService.SourceLocation javaLocation) {

    public enum Kind {
        IMPLICIT, BEAN, BUNDLE, TAG_VARIABLE, PARAMETER, LAMBDA, CONSTANTS, COMPOSITE
    }

    public static ElVariable implicit(String name, ElType type, String detail) {
        return new ElVariable(name, type, Kind.IMPLICIT, detail, null, -1, null);
    }
}
