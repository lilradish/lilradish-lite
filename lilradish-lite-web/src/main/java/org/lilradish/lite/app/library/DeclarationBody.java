package org.lilradish.lite.app.library;

import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.libprunus.core.error.ApiErrorException;
import org.lilradish.lite.domain.declaration.Declaration;
import org.lilradish.lite.domain.declaration.DeclarationSide;
import org.lilradish.lite.domain.declaration.Demand;
import org.lilradish.lite.domain.declaration.Demands;
import org.lilradish.lite.domain.declaration.Field;
import org.lilradish.lite.domain.declaration.FieldHelp;
import org.lilradish.lite.domain.declaration.FieldKind;
import org.lilradish.lite.domain.declaration.FieldLabel;
import org.lilradish.lite.domain.declaration.FieldName;
import org.lilradish.lite.domain.declaration.FieldShape;
import org.lilradish.lite.domain.declaration.FieldStanding;
import org.lilradish.lite.domain.declaration.HowMany;
import org.lilradish.lite.domain.failure.RefusalCode;
import org.lilradish.lite.domain.registry.EntryVersionId;
import org.lilradish.lite.web.MintedIdentifiers;
import tools.jackson.databind.JsonNode;

/**
 * Half of what something declares as a page sends it, each field exactly the members its kind and depth take,
 * and the key it was read under where it was; any other member, or one missing, refuses the whole.
 */
final class DeclarationBody {

    /**
     * The half as sent, and the key each field was read under, every field in order before those it holds.
     *
     * @param readAs none for a field the page added
     */
    record Sent(Declaration half, List<@Nullable UUID> readAs) {}

    static final String REFUSED = "This takes a JSON array of fields, each holding exactly the members its kind"
            + " and its depth take, each a value its type admits.";

    private static final String NAME = "name";

    private static final String LABEL = "label";

    private static final String HELP = "help";

    private static final String KIND = "kind";

    private static final String MANY = "many";

    private static final String MOST = "most";

    private static final String LONGEST = "longest";

    private static final String LIST = "list";

    private static final String FIELDS = "fields";

    private static final String MUST_BE_GIVEN = "mustBeGiven";

    private static final String STANDS = "stands";

    private static final String FLOOR = "floor";

    private static final String FIELD_ID = "fieldId";

    private static final Map<String, FieldKind> KINDS = Arrays.stream(FieldKind.values())
            .collect(Collectors.toUnmodifiableMap(FieldKind::published, Function.identity()));

    private static final Map<String, FieldStanding> STANDINGS = Arrays.stream(FieldStanding.values())
            .collect(Collectors.toUnmodifiableMap(FieldStanding::published, Function.identity()));

    /* Worked out once: what a field of each kind takes under each demand. */
    private static final Map<FieldKind, Map<Demands.Kind, Set<String>>> MEMBERS = members();

    private DeclarationBody() {}

    static Sent read(JsonNode body, DeclarationSide side, Demands demands) {
        if (!body.isArray()) {
            throw unusable();
        }
        int[] counted = {0};
        List<@Nullable UUID> readAs = new ArrayList<>();
        Declaration half = new Declaration(side, demands, fields(body, demands, 1, 0, counted, readAs));
        return new Sent(half, Collections.unmodifiableList(readAs));
    }

    private static List<Field> fields(
            JsonNode level, Demands demands, int depth, int above, int[] counted, List<@Nullable UUID> readAs) {
        if (!level.isEmpty() && depth > Declaration.MOST_LEVELS) {
            throw LibraryRefusal.DECLARATION_TOO_DEEP.raised();
        }
        counted[0] += level.size();
        if (counted[0] > Declaration.MOST_FIELDS) {
            throw LibraryRefusal.DECLARATION_TOO_LARGE.raised();
        }
        List<Field> fields = new ArrayList<>(level.size());
        for (JsonNode sent : level) {
            fields.add(field(sent, demands, depth, above, counted, readAs));
        }
        return fields;
    }

    private static Field field(
            JsonNode sent, Demands demands, int depth, int above, int[] counted, List<@Nullable UUID> readAs) {
        if (!sent.isObject() || !sent.path(KIND).isString()) {
            throw unusable();
        }
        FieldKind kind = KINDS.get(sent.get(KIND).asString());
        boolean first = depth == 1;
        if (kind == null) {
            throw unusable();
        }
        Set<String> taken = requireNonNull(requireNonNull(MEMBERS.get(kind)).get(demands.at(first)));
        JsonNode key = sent.path(FIELD_ID);
        int keyed = sent.has(FIELD_ID) ? 1 : 0;
        if (taken.size() != sent.size() - keyed
                || !sent.propertyNames().stream()
                        .allMatch(member -> member.equals(FIELD_ID) || taken.contains(member))) {
            throw unusable();
        }
        readAs.add(keyIn(key));
        FieldName name = nameIn(sent.get(NAME));
        int path = Declaration.pathBelow(above, name);
        if (path > Declaration.LONGEST_PATH) {
            throw LibraryRefusal.DECLARATION_TOO_DEEP.raised();
        }
        FieldShape shape =
                switch (kind) {
                    case TEXT -> limited(() -> new FieldShape.Text(limitIn(sent.get(LONGEST))));
                    case TERM -> new FieldShape.Term(listIn(sent.get(LIST)));
                    case FIELDS -> {
                        JsonNode held = sent.get(FIELDS);
                        if (!held.isArray()) {
                            throw unusable();
                        }
                        yield new FieldShape.Nested(fields(held, demands, depth + 1, path, counted, readAs));
                    }
                    default -> new FieldShape.Plain(kind);
                };
        return new Field(
                name,
                wordsIn(sent.get(LABEL), FieldLabel::new),
                wordsIn(sent.get(HELP), FieldHelp::new),
                shape,
                howManyIn(sent.get(MANY), sent.get(MOST)),
                demandIn(sent, demands, first));
    }

    private static Map<FieldKind, Map<Demands.Kind, Set<String>>> members() {
        Map<FieldKind, Map<Demands.Kind, Set<String>>> members = new EnumMap<>(FieldKind.class);
        for (FieldKind kind : FieldKind.values()) {
            Map<Demands.Kind, Set<String>> byDemand = new EnumMap<>(Demands.Kind.class);
            for (Demands.Kind demand : Demands.Kind.values()) {
                Set<String> taken = new HashSet<>(Set.of(NAME, LABEL, HELP, KIND, MANY, MOST, MUST_BE_GIVEN));
                switch (kind) {
                    case TEXT -> taken.add(LONGEST);
                    case TERM -> taken.add(LIST);
                    case FIELDS -> taken.add(FIELDS);
                    default -> {}
                }
                if (demand == Demands.Kind.STANDS) {
                    taken.addAll(Set.of(STANDS, FLOOR));
                }
                byDemand.put(demand, Set.copyOf(taken));
            }
            members.put(kind, byDemand);
        }
        return members;
    }

    private static FieldName nameIn(JsonNode sent) {
        if (!sent.isString()) {
            throw unusable();
        }
        try {
            return new FieldName(sent.asString());
        } catch (IllegalArgumentException refused) {
            throw LibraryRefusal.FIELD_NAME_UNUSABLE.raised(refused);
        }
    }

    private static <T> @Nullable T wordsIn(JsonNode sent, Function<String, T> held) {
        if (sent.isNull()) {
            return null;
        }
        if (!sent.isString()) {
            throw unusable();
        }
        try {
            return held.apply(sent.asString());
        } catch (IllegalArgumentException refused) {
            throw LibraryRefusal.FIELD_WORDS_UNUSABLE.raised(refused);
        }
    }

    /** Whether it is a whole number is this body's to say, and whether it is one a limit may be, the domain's. */
    private static @Nullable Integer limitIn(JsonNode sent) {
        if (sent.isNull()) {
            return null;
        }
        if (!sent.isInt()) {
            throw unusable();
        }
        return sent.intValue();
    }

    private static <T> T limited(Supplier<T> made) {
        try {
            return made.get();
        } catch (IllegalArgumentException refused) {
            throw LibraryRefusal.FIELD_LIMIT_UNUSABLE.raised(refused);
        }
    }

    /* A key that is no identifier this system mints was never read here, so it keeps nothing. */
    private static @Nullable UUID keyIn(JsonNode sent) {
        if (sent.isMissingNode() || sent.isNull()) {
            return null;
        }
        if (!sent.isString()) {
            throw unusable();
        }
        return MintedIdentifiers.read(sent.asString()).orElse(null);
    }

    private static @Nullable EntryVersionId listIn(JsonNode sent) {
        if (sent.isNull()) {
            return null;
        }
        if (!sent.isString()) {
            throw unusable();
        }
        return MintedIdentifiers.read(sent.asString())
                .map(EntryVersionId::new)
                .orElseThrow(LibraryRefusal.VERSION_NOT_PINNABLE::raised);
    }

    private static HowMany howManyIn(JsonNode many, JsonNode most) {
        if (!many.isBoolean()) {
            throw unusable();
        }
        if (many.booleanValue()) {
            return limited(() -> new HowMany.Many(limitIn(most)));
        }
        if (!most.isNull()) {
            throw unusable();
        }
        return new HowMany.One();
    }

    /** Only the members the depth's demand takes are there to read, which {@link #MEMBERS} made sure of. */
    private static Demand demandIn(JsonNode sent, Demands demands, boolean first) {
        JsonNode given = sent.get(MUST_BE_GIVEN);
        if (!given.isBoolean()) {
            throw unusable();
        }
        FieldStanding standing = null;
        Integer floor = null;
        if (demands.at(first) == Demands.Kind.STANDS) {
            JsonNode stands = sent.get(STANDS);
            if (!stands.isNull()) {
                standing = stands.isString() ? STANDINGS.get(stands.asString()) : null;
                if (standing == null) {
                    throw unusable();
                }
            }
            JsonNode floorSent = sent.get(FLOOR);
            if (!floorSent.isNull()) {
                if (!floorSent.isInt() || standing != FieldStanding.ABOVE_CONFIDENCE) {
                    throw unusable();
                }
                floor = floorSent.intValue();
            }
        }
        FieldStanding stood = standing;
        Integer above = floor;
        return limited(() -> demands.demandAt(first, given.booleanValue(), stood, above));
    }

    private static ApiErrorException unusable() {
        return new ApiErrorException(RefusalCode.BODY_UNUSABLE, REFUSED);
    }
}
