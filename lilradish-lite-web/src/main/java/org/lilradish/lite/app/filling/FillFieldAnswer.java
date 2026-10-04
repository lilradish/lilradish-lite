package org.lilradish.lite.app.filling;

import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.lilradish.lite.domain.declaration.FieldHelp;
import org.lilradish.lite.domain.declaration.FieldLabel;
import org.lilradish.lite.domain.declaration.OfferedTerms;
import org.lilradish.lite.domain.filling.FillField;
import org.lilradish.lite.domain.referencelist.ListNote;

/**
 * One field as whoever fills it or reads its value reads it, the one way every answer writes one: every field it
 * holds in declared order, and the terms it offers with what each means, so no list is read to draw it.
 *
 * @param label absent where it is read by its name
 * @param help absent where it says nothing
 * @param longest how many characters its value may run to, only where it is text
 * @param most the most it holds, only where it holds many
 * @param terms what it offers, only where it is a term
 * @param fields what it holds, only where it holds fields
 * @param types media types only where it is a file
 * @param maximumBytes largest single file only where it is a file
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FillFieldAnswer(
        String name,
        @Nullable String label,
        @Nullable String help,
        String kind,
        @Nullable Integer longest,
        @Nullable Integer most,
        boolean mustBeGiven,
        @Nullable TermsAnswer terms,
        @Nullable List<FillFieldAnswer> fields,
        @Nullable List<String> types,
        @Nullable Long maximumBytes,
        @Nullable String lines) {

    public FillFieldAnswer(
            String name,
            @Nullable String label,
            @Nullable String help,
            String kind,
            @Nullable Integer longest,
            @Nullable Integer most,
            boolean mustBeGiven,
            @Nullable TermsAnswer terms,
            @Nullable List<FillFieldAnswer> fields) {
        this(
                name,
                label,
                help,
                kind,
                longest,
                most,
                mustBeGiven,
                terms,
                fields,
                null,
                null,
                kind.equals("text") ? "several" : null);
    }

    public static List<FillFieldAnswer> of(List<FillField> fields) {
        requireNonNull(fields, "FillFieldAnswer fields must not be null");
        return fields.stream().map(FillFieldAnswer::of).toList();
    }

    private static FillFieldAnswer of(FillField field) {
        FieldLabel label = field.label();
        FieldHelp help = field.help();
        OfferedTerms terms = field.terms();
        return new FillFieldAnswer(
                field.name().value(),
                label == null ? null : label.value(),
                help == null ? null : help.value(),
                field.kind().published(),
                field.longest(),
                field.most(),
                field.mustBeGiven(),
                terms == null ? null : TermsAnswer.of(terms),
                field.fields().isEmpty() ? null : of(field.fields()),
                field.fileTypes() == null
                        ? null
                        : field.fileTypes().stream().map(type -> type.value()).toList(),
                field.maximumBytes(),
                field.lines() == null ? null : field.lines().published());
    }

    /** @param note absent where the list says nothing on choosing */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TermsAnswer(
            List<TermAnswer> terms, @Nullable String note) {

        static TermsAnswer of(OfferedTerms offered) {
            ListNote note = offered.note();
            return new TermsAnswer(
                    offered.terms().stream()
                            .map(term -> new TermAnswer(
                                    term.term().value(), term.meaning().value()))
                            .toList(),
                    note == null ? null : note.value());
        }
    }

    public record TermAnswer(String term, String meaning) {}
}
