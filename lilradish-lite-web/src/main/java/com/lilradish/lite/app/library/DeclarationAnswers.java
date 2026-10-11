package com.lilradish.lite.app.library;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.lilradish.lite.domain.declaration.Demand;
import com.lilradish.lite.domain.declaration.Field;
import com.lilradish.lite.domain.declaration.FieldHelp;
import com.lilradish.lite.domain.declaration.FieldLabel;
import com.lilradish.lite.domain.declaration.FieldShape;
import com.lilradish.lite.domain.declaration.FieldStanding;
import com.lilradish.lite.domain.declaration.HowMany;
import com.lilradish.lite.domain.registry.EntryVersionId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

final class DeclarationAnswers {

    private DeclarationAnswers() {}

    /** Every field of the half in declared order; a list it pins is one {@code pins} names, or a store gone wrong. */
    static List<FieldAnswer> of(StoredDeclarations.Half half, Map<EntryVersionId, PinnedVersions.PinnedVersion> pins) {
        return fields(half.declaration().fields(), half.keys(), pins);
    }

    static PinnedAnswer pinned(PinnedVersions.PinnedVersion pinned) {
        RetiredPinsRefusal.NumberedVersion newer = pinned.newer();
        return new PinnedAnswer(
                pinned.name().value(),
                pinned.version().value(),
                pinned.number(),
                pinned.standing().published(),
                newer == null
                        ? null
                        : new NumberedAnswer(
                                newer.version().value(),
                                newer.number(),
                                newer.versionName() == null
                                        ? null
                                        : newer.versionName().value()),
                pinned.versionName() == null ? null : pinned.versionName().value());
    }

    private static List<FieldAnswer> fields(
            List<Field> fields,
            List<StoredDeclarations.Keyed> keys,
            Map<EntryVersionId, PinnedVersions.PinnedVersion> pins) {
        List<FieldAnswer> answers = new ArrayList<>(fields.size());
        for (int index = 0; index < fields.size(); index++) {
            answers.add(field(fields.get(index), keys.get(index), pins));
        }
        return answers;
    }

    private static FieldAnswer field(
            Field field, StoredDeclarations.Keyed key, Map<EntryVersionId, PinnedVersions.PinnedVersion> pins) {
        FieldLabel label = field.label();
        FieldHelp help = field.help();
        Integer longest = field.shape() instanceof FieldShape.Text text ? text.longest() : null;
        PinnedAnswer list = null;
        if (field.shape() instanceof FieldShape.Term term && term.list() != null) {
            PinnedVersions.PinnedVersion pinned = pins.get(term.list());
            if (pinned == null) {
                throw new IllegalStateException("A field pins a list no reader was named");
            }
            list = pinned(pinned);
        }
        List<FieldAnswer> held =
                field.shape() instanceof FieldShape.Nested nested ? fields(nested.fields(), key.fields(), pins) : null;
        List<String> types = field.shape() instanceof FieldShape.File file
                ? file.types().stream().map(type -> type.value()).toList()
                : null;
        Long maximumBytes = field.shape() instanceof FieldShape.File file ? file.maximumBytes() : null;
        String stands = null;
        Integer floor = null;
        if (field.demand() instanceof Demand.Stands standsSo) {
            FieldStanding standing = standsSo.standing();
            stands = standing == null ? null : standing.published();
            floor = standsSo.floor();
        }
        return new FieldAnswer(
                key.id(),
                field.name().value(),
                label == null ? null : label.value(),
                help == null ? null : help.value(),
                field.shape().kind().published(),
                longest,
                field.shape() instanceof FieldShape.Text text ? text.lines().published() : null,
                list,
                field.howMany() instanceof HowMany.Many,
                field.howMany() instanceof HowMany.Many many ? many.most() : null,
                field.demand().mustBe(),
                stands,
                floor,
                held,
                types,
                maximumBytes);
    }

    /**
     * Each member a draft has not chosen yet is absent, as is each its kind, half or depth does not take.
     *
     * @param fieldId the key it is stored under, which changes whenever its half is written again
     * @param stands only on what a question gives back where no other field holds it
     * @param floor only where it stands above a confidence
     * @param fields only on a field holding fields, in declared order
     * @param types only on a file field, in declared order
     * @param maximumBytes only on a file field with a declared limit
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record FieldAnswer(
            UUID fieldId,
            String name,
            @Nullable String label,
            @Nullable String help,
            String kind,
            @Nullable Integer longest,
            @Nullable String lines,
            @Nullable PinnedAnswer list,
            boolean many,
            @Nullable Integer most,
            boolean mustBeGiven,
            @Nullable String stands,
            @Nullable Integer floor,
            @Nullable List<FieldAnswer> fields,
            @Nullable List<String> types,
            @Nullable Long maximumBytes) {}

    /**
     * @param standing the pinned version's own
     * @param newer the newest of its entry in service, absent where that is this one or none is
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record PinnedAnswer(
            String name,
            UUID versionId,
            int number,
            String standing,
            @Nullable NumberedAnswer newer,

            @JsonInclude(JsonInclude.Include.ALWAYS) @Nullable
            String versionName) {}

    record NumberedAnswer(
            UUID versionId, int number, @Nullable String versionName) {}
}
