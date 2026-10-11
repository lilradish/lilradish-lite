package com.lilradish.lite.domain.search;

import static java.util.Objects.requireNonNull;

import com.lilradish.lite.domain.declaration.Declaration;
import com.lilradish.lite.domain.declaration.DeclarationSide;
import com.lilradish.lite.domain.declaration.Demand;
import com.lilradish.lite.domain.declaration.Demands;
import com.lilradish.lite.domain.declaration.Field;
import com.lilradish.lite.domain.declaration.FieldName;
import com.lilradish.lite.domain.declaration.FieldShape;
import com.lilradish.lite.domain.declaration.HowMany;
import com.lilradish.lite.domain.model.ModelToolName;
import com.lilradish.lite.domain.text.Identifiers;
import java.util.List;
import java.util.Map;

/** The only place a search way's outputs are declared. */
public final class SearchWay {

    public static final FieldName TITLE = new FieldName("title");

    public static final FieldName BODY = new FieldName("body");

    public static final FieldName REFERENCES = new FieldName("references");

    public static final FieldName OTHER = new FieldName("other");

    /** In the order {@link #outputs()} holds them. */
    public static final List<FieldName> FIELDS = List.of(TITLE, BODY, REFERENCES, OTHER);

    private static final Map<FieldName, Demand.Given> OUTPUT_DEMANDS = Map.of(
            TITLE, new Demand.Given(true),
            BODY, new Demand.Given(true),
            REFERENCES, new Demand.Given(false),
            OTHER, new Demand.Given(false));

    private final String name;

    private final ModelToolName requiredTool;

    private final List<Field> outputs;

    public SearchWay(
            String name,
            ModelToolName requiredTool,
            int titleLimit,
            int bodyLimit,
            int referencesLimit,
            int otherLimit) {
        this.name = Identifiers.requireText(name, "SearchWay name");
        this.requiredTool = requireNonNull(requiredTool, "SearchWay " + name + " requiredTool must not be null");
        List<Field> fields = List.of(
                output(TITLE, titleLimit),
                output(BODY, bodyLimit),
                output(REFERENCES, referencesLimit),
                output(OTHER, otherLimit));
        Declaration declaration =
                new Declaration(DeclarationSide.GIVES, Demands.ofWorkflow(DeclarationSide.GIVES), fields);
        for (var problem : declaration.problems(Map.of())) {
            FieldName field = fields.get(problem.at().getFirst()).name();
            throw new IllegalArgumentException(
                    "SearchWay " + name + " output " + field.value() + " is not holdable: " + problem.code());
        }
        if (declaration.longestWritten(Map.of()) > Declaration.MOST_SENT) {
            throw new IllegalArgumentException("SearchWay " + name
                    + " outputs at their limits could not be written out together as one value of at most "
                    + Declaration.MOST_SENT + " characters");
        }
        this.outputs = fields;
    }

    /** What every way asks of this output, including where the release holds no way of its name. */
    public static Demand.Given demand(FieldName field) {
        requireNonNull(field, "SearchWay demand field must not be null");
        Demand.Given demand = OUTPUT_DEMANDS.get(field);
        if (demand == null) {
            throw new IllegalArgumentException("SearchWay declares no output " + field.value());
        }
        return demand;
    }

    public String name() {
        return name;
    }

    public ModelToolName requiredTool() {
        return requiredTool;
    }

    /** In declared order. */
    public List<Field> outputs() {
        return outputs;
    }

    private Field output(FieldName field, int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException(
                    "SearchWay " + name + " output " + field.value() + " limit must be positive: " + limit);
        }
        return new Field(field, null, null, new FieldShape.Text(limit), new HowMany.One(), demand(field));
    }
}
