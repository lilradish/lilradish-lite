package com.lilradish.lite.domain.search;

import static java.util.Objects.requireNonNull;

import com.lilradish.lite.domain.declaration.AskedField;
import com.lilradish.lite.domain.declaration.Field;
import com.lilradish.lite.domain.declaration.FieldKind;
import com.lilradish.lite.domain.declaration.FieldShape;
import com.lilradish.lite.domain.inference.ProductionAnswer;
import com.lilradish.lite.domain.inference.Unwrapping;
import com.lilradish.lite.domain.wire.JsonValue;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Only {@link #asked} makes the fields, so what a search is told and what it is read against cannot drift apart. */
public final class SearchAnswer {

    private SearchAnswer() {}

    /** Each of the way's outputs in its order: text of at most its limit, given as it demands, asking no confidence. */
    public static List<AskedField> asked(SearchWay way) {
        requireNonNull(way, "SearchAnswer way must not be null");
        List<AskedField> asked = new ArrayList<>(way.outputs().size());
        for (Field output : way.outputs()) {
            /* A way holds each output once as text with a limit, so neither the cast nor the limit fails. */
            Integer limit = requireNonNull(((FieldShape.Text) output.shape()).longest());
            asked.add(new AskedField(
                    output.name(),
                    FieldKind.TEXT,
                    limit,
                    null,
                    null,
                    List.of(),
                    output.demand().mustBe(),
                    false));
        }
        return List.copyOf(asked);
    }

    /**
     * What came back read once, as a model's production is read: a member for every field and no other, each
     * within its limit and holding nothing the store cannot keep. Text that shows nothing is nothing given: none
     * where the field need not be given, and otherwise not fitting. Nothing is cleaned or cut.
     *
     * @param answer what came back as read; none where it was not one JSON document
     */
    public static ProductionAnswer read(List<AskedField> asked, @Nullable JsonValue answer) {
        return Unwrapping.searched(asked, answer);
    }
}
