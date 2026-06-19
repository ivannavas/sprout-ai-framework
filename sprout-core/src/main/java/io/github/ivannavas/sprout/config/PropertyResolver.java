package io.github.ivannavas.sprout.config;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Function;

// Resolves ${...} placeholders embedded in property values, Spring-style: ${key}, ${key:default},
// placeholders inside a larger string, and nested/chained references.
public final class PropertyResolver {

    private static final String PREFIX = "${";
    private static final String SUFFIX = "}";
    private static final char DEFAULT_SEPARATOR = ':';

    private PropertyResolver() {
    }

    public static String resolve(String text, Function<String, String> source) {
        if (text == null || !text.contains(PREFIX)) {
            return text;
        }
        return parse(text, source, new HashSet<>());
    }

    private static String parse(String text, Function<String, String> source, Set<String> visiting) {
        StringBuilder result = new StringBuilder(text);
        int start = result.indexOf(PREFIX);
        while (start != -1) {
            int end = findEnd(result, start);
            if (end == -1) {
                break;
            }
            String original = result.substring(start + PREFIX.length(), end);
            if (!visiting.add(original)) {
                throw new IllegalStateException("Circular placeholder reference '" + original + "'");
            }

            // Resolve placeholders nested inside the key itself, e.g. ${${prefix}.key}.
            String placeholder = parse(original, source, visiting);

            String key = placeholder;
            String defaultValue = null;
            int sep = placeholder.indexOf(DEFAULT_SEPARATOR);
            if (sep != -1) {
                key = placeholder.substring(0, sep);
                defaultValue = placeholder.substring(sep + 1);
            }

            String value = source.apply(key);
            if (value == null) {
                value = defaultValue;
            }
            if (value == null) {
                throw new IllegalStateException(
                        "No value found for property '" + key + "' and no default was provided");
            }

            // Resolve placeholders contained in the resolved value (chained references).
            value = parse(value, source, visiting);
            result.replace(start, end + SUFFIX.length(), value);
            visiting.remove(original);
            start = result.indexOf(PREFIX, start + value.length());
        }
        return result.toString();
    }

    // Finds the index of the } that closes the placeholder opened at start (handles nesting).
    private static int findEnd(CharSequence buf, int start) {
        int index = start + PREFIX.length();
        int depth = 0;
        while (index < buf.length()) {
            if (matches(buf, index, SUFFIX)) {
                if (depth == 0) {
                    return index;
                }
                depth--;
                index += SUFFIX.length();
            } else if (matches(buf, index, PREFIX)) {
                depth++;
                index += PREFIX.length();
            } else {
                index++;
            }
        }
        return -1;
    }

    private static boolean matches(CharSequence buf, int index, String token) {
        if (index + token.length() > buf.length()) {
            return false;
        }
        for (int i = 0; i < token.length(); i++) {
            if (buf.charAt(index + i) != token.charAt(i)) {
                return false;
            }
        }
        return true;
    }
}