package org.lilradish.lite.app.library;

import static java.util.Objects.requireNonNull;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.lilradish.lite.domain.codestep.CodeStepDeclaration;
import org.lilradish.lite.domain.declaration.Declaration;
import org.lilradish.lite.domain.declaration.DeclarationSide;
import org.lilradish.lite.domain.declaration.Demands;
import org.lilradish.lite.domain.declaration.Field;
import org.lilradish.lite.domain.declaration.FieldHelp;
import org.lilradish.lite.domain.declaration.FieldKind;
import org.lilradish.lite.domain.declaration.FieldLabel;
import org.lilradish.lite.domain.declaration.FieldName;
import org.lilradish.lite.domain.declaration.FieldShape;
import org.lilradish.lite.domain.declaration.FieldStanding;
import org.lilradish.lite.domain.declaration.HowMany;
import org.lilradish.lite.domain.identity.GroupId;
import org.lilradish.lite.domain.registry.EntryKind;
import org.lilradish.lite.domain.registry.EntryVersionId;
import org.lilradish.lite.domain.wire.StoreLabels;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * What versions and route steps declare, as the store holds it, each field beside its key: every owner asked is
 * read in one statement, and holds both halves, empty where it stores no field there. A stored value its type
 * refuses, or a field only a cycle leaves unreached, fails the read rather than being shown or left out. What a
 * code step declares is the release's, keyed here alike.
 */
final class StoredDeclarations {

    // DB-SPECIFIC: enum and array casts and any(…) are PostgreSQL's.
    private static final String COLUMNS = """
            field.declaration_field_id,
                   field.parent_field_id,
                   cast(field.side as text) as side,
                   field.name,
                   field.label,
                   field.help,
                   cast(field.kind as text) as kind,
                   field.holds_many,
                   field.text_limit,
                   field.many_limit,
                   field.term_list_version_id,
                   field.must_be_given,
                   cast(field.standing as text) as standing,
                   field.standing_threshold""";

    /* Fields a parent holds come back in its order; the key breaks a tie a draft may hold, the same way each time. */
    private static final String OF_VERSIONS = """
            select field.entry_version_id as owner,
                   %s
              from declaration_fields field
             where field.entry_version_id = any(cast(:owners as uuid[]))
             order by field.position, field.declaration_field_id
            """.formatted(COLUMNS);

    private static final String OF_ROUTES = """
            select field.workflow_step_id as owner,
                   %s
              from declaration_fields field
              join workflow_steps step on step.workflow_step_id = field.workflow_step_id
             where step.entry_version_id = any(cast(:versions as uuid[]))
             order by field.position, field.declaration_field_id
            """.formatted(COLUMNS);

    /* Only this group's: another group's version is never read, only named as pinned where it may not be. */
    private static final String KINDS_IN_GROUP = """
            select version.entry_version_id, cast(version.entry_kind as text) as kind
              from entry_versions version
              join entries entry on entry.entry_id = version.entry_id
             where version.entry_version_id = any(cast(:versions as uuid[])) and entry.group_id = :group
            """;

    private StoredDeclarations() {}

    /**
     * Inside the caller's transaction: each version's two halves, asked as its kind asks them; only a question
     * or a workflow declares anything.
     */
    static Map<EntryVersionId, Halves> ofVersions(JdbcClient database, Map<EntryVersionId, EntryKind> versions) {
        requireNonNull(versions, "StoredDeclarations versions must not be null");
        Map<UUID, Function<DeclarationSide, Demands>> owners = HashMap.newHashMap(versions.size());
        versions.forEach((version, kind) -> owners.put(version.value(), demandsOf(kind)));
        List<Row> rows = database.sql(OF_VERSIONS)
                .param("owners", owners.keySet().stream().map(UUID::toString).toArray(String[]::new))
                .query((result, number) -> row(result))
                .list();
        Map<EntryVersionId, Halves> declared = HashMap.newHashMap(versions.size());
        byOwner(rows, owners, "Version").forEach((owner, halves) -> declared.put(new EntryVersionId(owner), halves));
        return declared;
    }

    /**
     * Inside the caller's transaction: what each of {@code versions} the group holds declares, each by its own
     * kind; one the group does not hold is left out, for whatever reads the result to name.
     */
    static Map<EntryVersionId, Halves> ofGroupVersions(
            JdbcClient database, GroupId group, Collection<EntryVersionId> versions) {
        if (versions.isEmpty()) {
            return Map.of();
        }
        Map<EntryVersionId, EntryKind> kinds = HashMap.newHashMap(versions.size());
        database.sql(KINDS_IN_GROUP)
                .param("versions", PinnedVersions.spelled(versions))
                .param("group", group.value())
                .query(result -> {
                    kinds.put(
                            new EntryVersionId(result.getObject("entry_version_id", UUID.class)),
                            StoreLabels.parse(EntryKind.class, result.getString("kind")));
                });
        return ofVersions(database, kinds);
    }

    /** Inside the caller's transaction: what each route step of the versions gives back, by the step's key. */
    static Map<UUID, Half> ofRoutes(JdbcClient database, Collection<EntryVersionId> versions, Collection<UUID> routes) {
        requireNonNull(routes, "StoredDeclarations routes must not be null");
        Map<UUID, Function<DeclarationSide, Demands>> owners = HashMap.newHashMap(routes.size());
        routes.forEach(route -> owners.put(route, Demands::ofWorkflow));
        List<Row> rows = database.sql(OF_ROUTES)
                .param("versions", PinnedVersions.spelled(versions))
                .query((result, number) -> row(result))
                .list();
        for (Row row : rows) {
            if (row.side() != DeclarationSide.GIVES || !owners.containsKey(row.owner())) {
                throw new IllegalStateException("Step " + row.owner() + " holds a field no route gives back");
            }
        }
        Map<UUID, Half> gives = HashMap.newHashMap(routes.size());
        byOwner(rows, owners, "Route step").forEach((route, halves) -> gives.put(route, halves.gives()));
        return gives;
    }

    /**
     * What a code step this release holds declares, each field under a key drawn from the code step's name, the
     * half and the path of names reaching it, so a place named within it is the same place at every reading.
     */
    static Halves ofCodeStep(String name, CodeStepDeclaration declared) {
        requireNonNull(declared, "StoredDeclarations declared must not be null");
        return new Halves(released(name, declared.takes()), released(name, declared.gives()));
    }

    private static Half released(String name, Declaration declaration) {
        return new Half(
                declaration,
                releasedKeys("code_step/" + name + "/" + declaration.side().published(), declaration.fields()));
    }

    /* A name-based key never meets a drawn one: its version digit is its own. A name holds no '/', so no two paths
    spell alike. */
    private static List<Keyed> releasedKeys(String above, List<Field> level) {
        List<Keyed> keys = new ArrayList<>(level.size());
        for (Field field : level) {
            String path = above + "/" + field.name().value();
            keys.add(new Keyed(
                    UUID.nameUUIDFromBytes(path.getBytes(StandardCharsets.UTF_8)),
                    field.shape() instanceof FieldShape.Nested nested
                            ? releasedKeys(path, nested.fields())
                            : List.of()));
        }
        return keys;
    }

    /** Every reference list version the fields at {@code level} and below pin, added to {@code pinned}. */
    static void pinnedIn(List<Field> level, Set<EntryVersionId> pinned) {
        for (Field field : level) {
            if (field.shape() instanceof FieldShape.Term term && term.list() != null) {
                pinned.add(term.list());
            }
            if (field.shape() instanceof FieldShape.Nested nested) {
                pinnedIn(nested.fields(), pinned);
            }
        }
    }

    private static Function<DeclarationSide, Demands> demandsOf(EntryKind kind) {
        return switch (kind) {
            case QUESTION -> Demands::ofQuestion;
            case WORKFLOW -> Demands::ofWorkflow;
            case REFERENCE_LIST -> throw new IllegalArgumentException("A reference list declares nothing");
        };
    }

    private static Map<UUID, Halves> byOwner(
            List<Row> rows, Map<UUID, Function<DeclarationSide, Demands>> owners, String ownedBy) {
        Map<UUID, List<Row>> grouped = HashMap.newHashMap(owners.size());
        for (Row row : rows) {
            grouped.computeIfAbsent(row.owner(), ignored -> new ArrayList<>()).add(row);
        }
        Map<UUID, Halves> halves = HashMap.newHashMap(owners.size());
        owners.forEach((owner, demands) ->
                halves.put(owner, halves(grouped.getOrDefault(owner, List.of()), demands, ownedBy + " " + owner)));
        return halves;
    }

    private static Halves halves(List<Row> rows, Function<DeclarationSide, Demands> demandsOf, String owner) {
        Map<@Nullable UUID, List<Row>> byParent = new HashMap<>();
        for (Row row : rows) {
            byParent.computeIfAbsent(row.parent(), ignored -> new ArrayList<>()).add(row);
        }
        Map<DeclarationSide, Half> halves = new EnumMap<>(DeclarationSide.class);
        int[] reached = {0};
        for (DeclarationSide side : DeclarationSide.values()) {
            List<Row> first = byParent.getOrDefault(null, List.of()).stream()
                    .filter(row -> row.side() == side)
                    .toList();
            List<Keyed> keys = new ArrayList<>();
            List<Field> fields = new ArrayList<>();
            Demands demands = demandsOf.apply(side);
            try {
                for (Row row : first) {
                    fields.add(field(row, demands, true, byParent, keys, reached));
                }
                halves.put(side, new Half(new Declaration(side, demands, fields), keys));
            } catch (IllegalArgumentException refused) {
                throw new IllegalStateException(owner + " holds a field this system will not read", refused);
            }
        }
        if (reached[0] != rows.size()) {
            throw new IllegalStateException(owner + " holds fields no first-level field reaches");
        }
        return new Halves(
                requireNonNull(halves.get(DeclarationSide.TAKES)), requireNonNull(halves.get(DeclarationSide.GIVES)));
    }

    private static Field field(
            Row row,
            Demands demands,
            boolean first,
            Map<@Nullable UUID, List<Row>> byParent,
            List<Keyed> keys,
            int[] reached) {
        reached[0]++;
        FieldKind kind = StoreLabels.parse(FieldKind.class, row.kind());
        List<Keyed> held = new ArrayList<>();
        FieldShape shape =
                switch (kind) {
                    case TEXT -> new FieldShape.Text(row.textLimit());
                    case TERM -> new FieldShape.Term(row.list() == null ? null : new EntryVersionId(row.list()));
                    case FIELDS -> {
                        List<Field> fields = new ArrayList<>();
                        for (Row child : byParent.getOrDefault(row.id(), List.of())) {
                            fields.add(field(child, demands, false, byParent, held, reached));
                        }
                        yield new FieldShape.Nested(fields);
                    }
                    case NUMBER, DATE, MOMENT, YES_NO -> new FieldShape.Plain(kind);
                };
        keys.add(new Keyed(row.id(), held));
        return new Field(
                new FieldName(row.name()),
                row.label() == null ? null : new FieldLabel(row.label()),
                row.help() == null ? null : new FieldHelp(row.help()),
                shape,
                row.holdsMany() ? new HowMany.Many(row.manyLimit()) : new HowMany.One(),
                demands.demandAt(
                        first,
                        row.mustBeGiven(),
                        row.standing() == null ? null : StoreLabels.parse(FieldStanding.class, row.standing()),
                        row.floor()));
    }

    private static Row row(ResultSet result) throws SQLException {
        return new Row(
                result.getObject("owner", UUID.class),
                result.getObject("declaration_field_id", UUID.class),
                result.getObject("parent_field_id", UUID.class),
                StoreLabels.parse(DeclarationSide.class, result.getString("side")),
                result.getString("name"),
                result.getString("label"),
                result.getString("help"),
                result.getString("kind"),
                result.getBoolean("holds_many"),
                result.getObject("text_limit", Integer.class),
                result.getObject("many_limit", Integer.class),
                result.getObject("term_list_version_id", UUID.class),
                result.getObject("must_be_given", Boolean.class),
                result.getString("standing"),
                result.getObject("standing_threshold", Integer.class));
    }

    /**
     * One half of a declaration, and each of its fields' keys in the same shape; a question's, a workflow's or
     * a route's.
     *
     * @param keys a key for each first-level field, in declared order, each holding its own fields' keys
     */
    record Half(Declaration declaration, List<Keyed> keys) {

        Half {
            requireNonNull(declaration, "StoredDeclarations.Half declaration must not be null");
            keys = List.copyOf(requireNonNull(keys, "StoredDeclarations.Half keys must not be null"));
        }

        /** The key of the field at {@code at}, counted as {@code FieldProblem} counts. */
        UUID keyAt(List<Integer> at) {
            List<Keyed> keysHere = keys;
            Keyed key = null;
            for (int index : at) {
                key = keysHere.get(index);
                keysHere = key.fields();
            }
            return requireNonNull(key, "a place names a field").id();
        }
    }

    record Keyed(UUID id, List<Keyed> fields) {

        Keyed {
            requireNonNull(id, "StoredDeclarations.Keyed id must not be null");
            fields = List.copyOf(requireNonNull(fields, "StoredDeclarations.Keyed fields must not be null"));
        }
    }

    /** What a question or a workflow version takes and gives back. */
    record Halves(Half takes, Half gives) {

        Halves {
            requireNonNull(takes, "StoredDeclarations.Halves takes must not be null");
            requireNonNull(gives, "StoredDeclarations.Halves gives must not be null");
        }

        Half half(DeclarationSide side) {
            return side == DeclarationSide.TAKES ? takes : gives;
        }

        /** Every reference list version either half pins, added to {@code pinned}. */
        void listsPinnedInto(Set<EntryVersionId> pinned) {
            pinnedIn(takes.declaration().fields(), pinned);
            pinnedIn(gives.declaration().fields(), pinned);
        }
    }

    private record Row(
            UUID owner,
            UUID id,
            @Nullable UUID parent,
            DeclarationSide side,
            String name,
            @Nullable String label,
            @Nullable String help,
            String kind,
            boolean holdsMany,
            @Nullable Integer textLimit,
            @Nullable Integer manyLimit,
            @Nullable UUID list,
            @Nullable Boolean mustBeGiven,
            @Nullable String standing,
            @Nullable Integer floor) {}
}
