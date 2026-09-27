package org.lilradish.lite.app.library;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.lilradish.lite.domain.declaration.Demand;
import org.lilradish.lite.domain.declaration.Field;
import org.lilradish.lite.domain.declaration.FieldHelp;
import org.lilradish.lite.domain.declaration.FieldLabel;
import org.lilradish.lite.domain.declaration.FieldShape;
import org.lilradish.lite.domain.declaration.FieldStanding;
import org.lilradish.lite.domain.declaration.HowMany;
import org.lilradish.lite.domain.registry.EntryVersionId;

/** A declaration's fields, and the versions they pin, as every reader of a version's content is answered them. */
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
                newer == null ? null : new NumberedAnswer(newer.version().value(), newer.number()));
    }

    static OfferedAnswer offered(PinnedVersions.OfferedVersion offered) {
        return new OfferedAnswer(offered.name().value(), offered.version().value(), offered.number());
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
                list,
                field.howMany() instanceof HowMany.Many,
                field.howMany() instanceof HowMany.Many many ? many.most() : null,
                field.demand().mustBe(),
                stands,
                floor,
                held);
    }

    /**
     * Each member a draft has not chosen yet is absent, as is each its kind, half or depth does not take.
     *
     * @param fieldId the key it is stored under, which changes whenever its half is written again
     * @param stands only on what a question gives back where no other field holds it
     * @param floor only where it stands above a confidence
     * @param fields only on a field holding fields, in declared order
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record FieldAnswer(
            UUID fieldId,
            String name,
            @Nullable String label,
            @Nullable String help,
            String kind,
            @Nullable Integer longest,
            @Nullable PinnedAnswer list,
            boolean many,
            @Nullable Integer most,
            boolean mustBeGiven,
            @Nullable String stands,
            @Nullable Integer floor,
            @Nullable List<FieldAnswer> fields) {}

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
            @Nullable NumberedAnswer newer) {}

    record NumberedAnswer(UUID versionId, int number) {}

    record OfferedAnswer(String name, UUID versionId, int number) {}
}
