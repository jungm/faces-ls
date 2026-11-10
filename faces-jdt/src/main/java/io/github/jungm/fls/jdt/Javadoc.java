package io.github.jungm.fls.jdt;

import java.lang.reflect.Method;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMember;
import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.jdt.core.ITypeRoot;
import org.eclipse.jdt.core.JavaModelException;

/**
 * Javadoc of members as Markdown. jdtls' own converter ({@code JavadocContentAccess2.getMarkdownContent}, internal
 * to jdtls, hence reflection) renders it as jdtls' hover does, including inherited documentation and Javadoc
 * attached to libraries; without it, the source comment is converted with a few patterns.
 */
final class Javadoc {
    private static final String CONTENT_ACCESS = "org.eclipse.jdt.ls.core.internal.javadoc.JavadocContentAccess2";
    private static final Pattern INLINE_TAG = Pattern.compile("\\{@(?:code|literal)\\s+([^}]*)}|\\{@(?:link|linkplain|value)\\s+([^}\\s]*)[^}]*}");

    private static volatile Method markdownContent;
    private static volatile boolean resolved;

    private Javadoc() {
    }

    /** The Markdown of a member's Javadoc, or null when it has none. */
    static String markdown(IMember member) throws JavaModelException {
        String converted = fromJdtls(member);
        if (converted != null && !converted.isBlank()) {
            return converted.trim();
        }
        ISourceRange range = member.getJavadocRange();
        ITypeRoot root = member.getTypeRoot();
        String source = root == null ? null : root.getSource();
        if (range == null || source == null) {
            return null;
        }
        return fromSource(source.substring(range.getOffset(), range.getOffset() + range.getLength()));
    }

    private static String fromJdtls(IMember member) {
        Method method = markdownContentMethod();
        if (method == null) {
            return null;
        }
        try {
            return (String) method.invoke(null, member);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return null;
        }
    }

    private static Method markdownContentMethod() {
        if (!resolved) {
            try {
                markdownContent = Javadoc.class.getClassLoader().loadClass(CONTENT_ACCESS)
                        .getMethod("getMarkdownContent", IJavaElement.class);
            } catch (ReflectiveOperationException | LinkageError e) {
                markdownContent = null;
            }
            resolved = true;
        }
        return markdownContent;
    }

    /** Converts a Javadoc comment ({@code /** ... *}{@code /}) to Markdown. */
    static String fromSource(String raw) {
        String body = raw.replaceFirst("^/\\*\\*", "").replaceFirst("\\*/$", "");
        StringBuilder text = new StringBuilder();
        for (String line : body.split("\\R")) {
            text.append(line.replaceFirst("^\\s*\\*\\s?", "")).append('\n');
        }
        String result = text.toString().trim();
        Matcher matcher = INLINE_TAG.matcher(result);
        StringBuilder replaced = new StringBuilder();
        while (matcher.find()) {
            String value = matcher.group(1) != null ? matcher.group(1) : matcher.group(2).replace('#', '.');
            matcher.appendReplacement(replaced, Matcher.quoteReplacement("`" + value.trim() + "`"));
        }
        matcher.appendTail(replaced);
        result = replaced.toString()
                .replaceAll("(?i)<p>\\s*", "\n\n")
                .replaceAll("(?i)</?(b|strong)>", "**")
                .replaceAll("(?i)</?(i|em)>", "_")
                .replaceAll("(?i)</?code>", "`")
                .replaceAll("(?i)<li>", "\n- ")
                .replaceAll("<[^>]+>", "")
                .replaceAll("(?m)^@(param|return|throws|exception|see|since|deprecated)\\b", "\n**@$1**");
        return result.trim();
    }
}
