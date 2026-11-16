package io.github.jungm.fls.extension;

import io.github.jungm.fls.analysis.ElAnalyzer;
import io.github.jungm.fls.analysis.FacesDocument;

/**
 * The offsets {@code start} (inclusive) to {@code end} (exclusive) of an open Facelets document. The analyzer knows
 * the document, its project and the Facelets index.
 */
public record FaceletsRange(ElAnalyzer analyzer, int start, int end) {

    public FacesDocument document() {
        return analyzer.document();
    }
}
