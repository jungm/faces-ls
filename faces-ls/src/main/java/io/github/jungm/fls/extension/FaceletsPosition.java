package io.github.jungm.fls.extension;

import io.github.jungm.fls.analysis.ElAnalyzer;
import io.github.jungm.fls.analysis.FacesDocument;

/**
 * An offset in an open Facelets document. The analyzer knows the document, its project and the Facelets index.
 */
public record FaceletsPosition(ElAnalyzer analyzer, int offset) {

    public FacesDocument document() {
        return analyzer.document();
    }
}
