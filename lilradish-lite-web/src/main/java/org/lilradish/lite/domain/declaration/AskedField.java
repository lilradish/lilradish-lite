package org.lilradish.lite.domain.declaration;

import static java.util.Objects.requireNonNull;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One field as a model is told it: its name, what it is, how many, how long, and whether it must be given. It
 * carries no label and no help, which are for people, and no floor, which a model is never told.
 *
 * @param longest how long its value may be, only where it is text
 * @param most the most it holds where it holds many; none where it holds one
 * @param terms what it offers, only where it is a term
 * @param fields its own fields in declared order, only where it holds fields, and none otherwise
 * @param mustBeGiven whether its value may not be none, nor, where it holds many, hold none
 * @param confidenceAsked whether a value given back is to come with how sure its maker was; never of a field
 *     another field holds
 */
public record AskedField(
        FieldName name,
        FieldKind kind,
        @Nullable Integer longest,
        @Nullable Integer most,
        @Nullable OfferedTerms terms,
        List<AskedField> fields,
        boolean mustBeGiven,
        boolean confidenceAsked) {

    public AskedField {
        requireNonNull(name, "AskedField name must not be null");
        requireNonNull(kind, "AskedField kind must not be null");
        fields = List.copyOf(requireNonNull(fields, "AskedField fields must not be null"));
        if ((kind == FieldKind.TEXT) != (longest != null) || (longest != null && longest < 1)) {
            throw new IllegalArgumentException("AskedField says how long, of at least one, exactly where it is text");
        }
        if (most != null && most < 1) {
            throw new IllegalArgumentException("AskedField most must be at least one: " + most);
        }
        if ((kind == FieldKind.TERM) != (terms != null)) {
            throw new IllegalArgumentException("AskedField offers terms exactly where it is a term");
        }
        if (kind != FieldKind.FIELDS && !fields.isEmpty()) {
            throw new IllegalArgumentException("AskedField holds fields only where it is fields");
        }
    }
}
