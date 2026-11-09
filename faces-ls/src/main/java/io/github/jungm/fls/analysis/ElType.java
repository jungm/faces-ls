package io.github.jungm.fls.analysis;

import java.util.List;

import io.github.jungm.fls.java.JType;
import io.github.jungm.fls.java.TypeService;
import io.github.jungm.fls.taglib.TagLibrary;

/**
 * The static type of an EL (sub)expression, as far as faces-ls can tell.
 */
public sealed interface ElType {

    /** A Java type. */
    record Java(JType type) implements ElType {
    }

    /** A resource bundle variable: properties are its keys. */
    record Bundle(String baseName) implements ElType {
    }

    /**
     * A class imported by {@code f:importConstants} and similar tags ({@link Imports}): properties are its static
     * fields, or only its enum constants plus {@code allValuesKeys} for {@code p:importEnum}.
     */
    record Constants(String fqn, boolean enumOnly, List<String> allValuesKeys) implements ElType {
        public Constants(String fqn) {
            this(fqn, false, List.of());
        }
    }

    /** {@code cc} in a composite component: a UIComponent whose {@code attrs} are the interface attributes. */
    record CompositeComponent(TagLibrary.TagDef contract, JType componentType) implements ElType {
    }

    /** {@code cc.attrs}. */
    record CompositeAttributes(TagLibrary.TagDef contract) implements ElType {
    }

    /** A reference to a method without call parentheses, as in method expressions ({@code #{bean.save}}). */
    record MethodReference(TypeService.MethodInfo method, JType owner) implements ElType {
    }

    /** Nothing is known; dependent expressions are not checked. */
    record Unknown() implements ElType {
    }

    ElType UNKNOWN = new Unknown();

    static ElType java(JType type) {
        return new Java(type.boxed());
    }

    static ElType java(String fqn) {
        return new Java(JType.parse(fqn));
    }

    default String display() {
        if (this instanceof Java java) {
            return java.type().display();
        }
        if (this instanceof Bundle bundle) {
            return "ResourceBundle " + bundle.baseName();
        }
        if (this instanceof Constants constants) {
            return "constants of " + constants.fqn();
        }
        if (this instanceof CompositeComponent) {
            return "UIComponent (composite component)";
        }
        if (this instanceof CompositeAttributes) {
            return "composite component attributes";
        }
        if (this instanceof MethodReference reference) {
            return reference.method().returnType().display() + " " + reference.method().signature();
        }
        return "?";
    }
}
