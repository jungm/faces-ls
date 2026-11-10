package io.github.jungm.fls.jdt;

/** A query that cannot be answered for a known reason; logged by its message alone and answered with an error. */
final class QueryException extends Exception {
    QueryException(String message) {
        super(message);
    }
}
