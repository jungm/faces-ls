package org.example.shared;

/**
 * EL functions of the shared tag library.
 */
public final class Strings {
    private Strings() {
    }

    public static String upper(String value) {
        return value == null ? null : value.toUpperCase();
    }
}
