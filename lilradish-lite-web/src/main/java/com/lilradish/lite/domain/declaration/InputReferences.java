package com.lilradish.lite.domain.declaration;

import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Names an instruction or drawing refers to, without putting any value into the author's text. */
public final class InputReferences {

    private static final Pattern REFERENCE = Pattern.compile("\\{\\{([a-z][a-z0-9_]{0,62})\\}\\}");

    private InputReferences() {}

    /** Each name once, in the order it is first referred to; escaped opening braces name nothing. */
    public static List<String> names(String text) {
        List<String> names = new ArrayList<>();
        HashSet<String> seen = new HashSet<>();
        for (Reference reference : referencesIn(text, true)) {
            if (seen.add(reference.name())) {
                names.add(reference.name());
            }
        }
        return List.copyOf(names);
    }

    public static List<String> unknown(String text, List<FieldName> takes) {
        requireNonNull(takes, "InputReferences takes must not be null");
        HashSet<String> taken =
                new HashSet<>(takes.stream().map(FieldName::value).toList());
        return names(text).stream().filter(name -> !taken.contains(name)).toList();
    }

    /** A drawing's existing field-name syntax, without treating other brace contents as names. */
    public static List<FieldName> drawingNames(String text) {
        return referencesIn(text, false).stream()
                .map(reference -> new FieldName(reference.name()))
                .toList();
    }

    /** Names are changed together, so exchanging two names never changes either reference twice. */
    public static String renamed(String text, Map<FieldName, FieldName> names) {
        requireNonNull(names, "InputReferences renamed names must not be null");
        StringBuilder renamed = new StringBuilder(text.length());
        int from = 0;
        for (Reference reference : referencesIn(text, false)) {
            FieldName name = names.get(new FieldName(reference.name()));
            if (name != null && !name.value().equals(reference.name())) {
                renamed.append(text, from, reference.from())
                        .append("{{")
                        .append(name.value())
                        .append("}}");
                from = reference.to();
            }
        }
        return renamed.append(text, from, text.length()).toString();
    }

    private static List<Reference> referencesIn(String text, boolean completeBraces) {
        requireNonNull(text, "InputReferences text must not be null");
        List<Reference> found = new ArrayList<>();
        Matcher matcher = REFERENCE.matcher(text);
        int at = 0;
        while (at < text.length()) {
            if (text.startsWith("\\{{", at)) {
                at += 3;
            } else if (completeBraces && text.startsWith("{{", at)) {
                int end = text.indexOf("}}", at + 2);
                if (end == -1) {
                    break;
                }
                found.add(new Reference(at, end + 2, text.substring(at + 2, end)));
                at = end + 2;
            } else {
                matcher.region(at, text.length());
                if (matcher.lookingAt()) {
                    found.add(new Reference(at, matcher.end(), matcher.group(1)));
                    at = matcher.end();
                } else {
                    at++;
                }
            }
        }
        return found;
    }

    private record Reference(int from, int to, String name) {}
}
