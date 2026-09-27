package org.lilradish.lite.app.library;

import static java.util.Objects.requireNonNull;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.lilradish.lite.app.change.Author;
import org.lilradish.lite.domain.declaration.Declaration;
import org.lilradish.lite.domain.declaration.DeclarationSide;
import org.lilradish.lite.domain.declaration.Demand;
import org.lilradish.lite.domain.declaration.Field;
import org.lilradish.lite.domain.declaration.FieldHelp;
import org.lilradish.lite.domain.declaration.FieldLabel;
import org.lilradish.lite.domain.declaration.FieldShape;
import org.lilradish.lite.domain.declaration.HowMany;
import org.lilradish.lite.domain.identity.UserId;
import org.lilradish.lite.domain.registry.EntryKind;
import org.lilradish.lite.domain.registry.EntryVersionId;
import org.lilradish.lite.domain.wire.StoreLabels;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * A half's fields written as rows in their declared order, owned by a draft or by one route step of it. A field
 * keeps the list its stored row already pins, whatever that now stands at; every other pin must be in service in
 * the draft's group.
 */
final class FieldWriting {

    // DB-SPECIFIC: uuidv7(), generate_series, unnest and enum and array casts are PostgreSQL's.
    /* Drawn before the fields are written, so each is written knowing its parent's key. */
    private static final String KEYS = "select uuidv7() from generate_series(1, :count)";

    /* One statement for every field: a parent's key is checked when it ends, after the parent is written. */
    private static final String FIELDS = """
            insert into declaration_fields (declaration_field_id, entry_version_id, entry_kind, workflow_step_id,
                                            side, parent_field_id, position, name, label, help, kind, holds_many,
                                            text_limit, many_limit, term_list_version_id, must_be_given, standing,
                                            standing_threshold, created_by)
            select field.id, cast(:version as uuid), cast(:entryKind as entry_kind), cast(:step as uuid),
                   cast(:side as declaration_side), field.parent, field.position, field.name, field.label, field.help,
                   cast(field.kind as field_kind), field.holds_many, field.text_limit, field.many_limit, field.list,
                   field.must_be_given, cast(field.standing as field_standing), field.floor, %s
              from unnest(cast(:ids as uuid[]), cast(:parents as uuid[]), cast(:positions as integer[]),
                          cast(:names as text[]), cast(:labels as text[]), cast(:helps as text[]),
                          cast(:kinds as text[]), cast(:holdsMany as boolean[]), cast(:textLimits as integer[]),
                          cast(:manyLimits as integer[]), cast(:lists as uuid[]), cast(:mustBeGiven as boolean[]),
                          cast(:standings as text[]), cast(:floors as integer[]))
                   as field(id, parent, position, name, label, help, kind, holds_many, text_limit, many_limit, list,
                            must_be_given, standing, floor)
            """.formatted(Author.OF_CALLER);

    private static final String HALF_CLEARED = """
            delete from declaration_fields
             where entry_version_id = :version and side = cast(:side as declaration_side)
            """;

    private FieldWriting() {}

    /**
     * The draft's own half written whole in place of what it held; {@code readAs} is the key each field was read
     * under, in the order the half reads, none for one added.
     */
    static void halfRewritten(
            JdbcClient database, OpenDraft draft, Declaration half, List<@Nullable UUID> readAs, UserId caller) {
        // Pins are found first: a field kept is judged by the pin its stored row holds, which clearing deletes.
        Deque<OpenDraft.PinnableVersion> pinnable = pinnable(draft, half.side(), half.fields(), readAs);
        database.sql(HALF_CLEARED)
                .param("version", draft.version().value())
                .param("side", StoreLabels.label(half.side()))
                .update();
        written(
                database,
                draft,
                new Owner.Version(draft.version(), draft.kind()),
                half.side(),
                half.fields(),
                pinnable,
                caller);
    }

    /**
     * Each list the fields pin, in the order {@link #written} takes them; asked before the half is cleared, as a field
     * read under a key is judged by the pin its stored row holds. {@code readAs} holds none for a field added.
     */
    static Deque<OpenDraft.PinnableVersion> pinnable(
            OpenDraft draft, DeclarationSide side, List<Field> fields, List<@Nullable UUID> readAs) {
        Deque<OpenDraft.PinnableVersion> pinnable = new ArrayDeque<>();
        Iterator<@Nullable UUID> keys = readAs.iterator();
        pinnableIn(draft, side, fields, keys, pinnable);
        if (keys.hasNext()) {
            throw new IllegalArgumentException("FieldWriting was handed more keys read than fields");
        }
        return pinnable;
    }

    /** The fields written as the owner's, taking from {@code pinnable} what {@link #pinnable} found for them. */
    static void written(
            JdbcClient database,
            OpenDraft draft,
            Owner owner,
            DeclarationSide side,
            List<Field> fields,
            Deque<OpenDraft.PinnableVersion> pinnable,
            UserId caller) {
        Rows rows = new Rows();
        flattened(draft, fields, null, pinnable, rows);
        if (rows.count() == 0) {
            return;
        }
        List<UUID> keys = database.sql(KEYS)
                .param("count", rows.count())
                .query((result, number) -> result.getObject(1, UUID.class))
                .list();
        rows.keyed(keys);
        EntryVersionId version = owner instanceof Owner.Version held ? held.version() : null;
        UUID step = owner instanceof Owner.RouteStep route ? route.step() : null;
        rows.bound(database.sql(FIELDS))
                .param("version", version == null ? null : version.value().toString())
                .param("entryKind", owner instanceof Owner.Version held ? StoreLabels.label(held.kind()) : null)
                .param("step", step == null ? null : step.toString())
                .param("side", StoreLabels.label(side))
                .param("caller", caller.value())
                .update();
    }

    private static void pinnableIn(
            OpenDraft draft,
            DeclarationSide side,
            List<Field> level,
            Iterator<@Nullable UUID> keys,
            Deque<OpenDraft.PinnableVersion> pinnable) {
        for (Field field : level) {
            if (!keys.hasNext()) {
                throw new IllegalArgumentException("FieldWriting was handed fewer keys read than fields");
            }
            UUID key = keys.next();
            if (field.shape() instanceof FieldShape.Term term && term.list() != null) {
                EntryVersionId list = term.list();
                OpenDraft.PinnableVersion found = (key == null
                                ? Optional.<OpenDraft.PinnableVersion>empty()
                                : draft.pinnedAlready(side, key, list))
                        .orElseGet(() -> draft.pinInService(list));
                if (found.kind() != EntryKind.REFERENCE_LIST) {
                    throw LibraryRefusal.VERSION_NOT_PINNABLE.raised();
                }
                pinnable.add(found);
            }
            if (field.shape() instanceof FieldShape.Nested nested) {
                pinnableIn(draft, side, nested.fields(), keys, pinnable);
            }
        }
    }

    /** Parents before what they hold, so a row's parent is always an earlier row. */
    private static void flattened(
            OpenDraft draft,
            List<Field> level,
            @Nullable Integer parent,
            Deque<OpenDraft.PinnableVersion> pinnable,
            Rows rows) {
        for (int index = 0; index < level.size(); index++) {
            Field field = level.get(index);
            String list = null;
            if (field.shape() instanceof FieldShape.Term term && term.list() != null) {
                OpenDraft.PinnableVersion pinned = requireNonNull(pinnable.poll());
                draft.requireIssued(pinned);
                list = pinned.version().value().toString();
            }
            int row = rows.add(field, parent, index + 1, list);
            if (field.shape() instanceof FieldShape.Nested nested) {
                flattened(draft, nested.fields(), row, pinnable, rows);
            }
        }
    }

    /** Whose fields they are: the version itself, or one route step of it. */
    sealed interface Owner permits Owner.Version, Owner.RouteStep {

        record Version(EntryVersionId version, EntryKind kind) implements Owner {}

        record RouteStep(UUID step) implements Owner {}
    }

    /** Every field of a half as one column per stored column, bound as arrays to a single statement. */
    private static final class Rows {

        private final List<@Nullable Integer> parents = new ArrayList<>();

        private final List<Integer> positions = new ArrayList<>();

        private final List<String> names = new ArrayList<>();

        private final List<@Nullable String> labels = new ArrayList<>();

        private final List<@Nullable String> helps = new ArrayList<>();

        private final List<String> kinds = new ArrayList<>();

        private final List<Boolean> holdsMany = new ArrayList<>();

        private final List<@Nullable Integer> textLimits = new ArrayList<>();

        private final List<@Nullable Integer> manyLimits = new ArrayList<>();

        private final List<@Nullable String> lists = new ArrayList<>();

        private final List<Boolean> mustBeGiven = new ArrayList<>();

        private final List<@Nullable String> standings = new ArrayList<>();

        private final List<@Nullable Integer> floors = new ArrayList<>();

        private String[] ids = new String[0];

        int count() {
            return names.size();
        }

        int add(Field field, @Nullable Integer parent, int position, @Nullable String list) {
            FieldLabel label = field.label();
            FieldHelp help = field.help();
            parents.add(parent);
            positions.add(position);
            names.add(field.name().value());
            labels.add(label == null ? null : label.value());
            helps.add(help == null ? null : help.value());
            kinds.add(StoreLabels.label(field.shape().kind()));
            holdsMany.add(field.howMany() instanceof HowMany.Many);
            textLimits.add(field.shape() instanceof FieldShape.Text text ? text.longest() : null);
            manyLimits.add(field.howMany() instanceof HowMany.Many many ? many.most() : null);
            lists.add(list);
            mustBeGiven.add(field.demand().mustBe());
            standings.add(
                    field.demand() instanceof Demand.Stands stands && stands.standing() != null
                            ? StoreLabels.label(stands.standing())
                            : null);
            floors.add(field.demand() instanceof Demand.Stands stands ? stands.floor() : null);
            return names.size() - 1;
        }

        void keyed(List<UUID> keys) {
            if (keys.size() != count()) {
                throw new IllegalStateException("Drew " + keys.size() + " keys for " + count() + " fields");
            }
            ids = keys.stream().map(UUID::toString).toArray(String[]::new);
        }

        JdbcClient.StatementSpec bound(JdbcClient.StatementSpec statement) {
            List<@Nullable String> parentKeys = new ArrayList<>(count());
            for (Integer parent : parents) {
                parentKeys.add(parent == null ? null : ids[parent]);
            }
            return statement
                    .param("ids", ids)
                    .param("parents", parentKeys.toArray(String[]::new))
                    .param("positions", positions.toArray(Integer[]::new))
                    .param("names", names.toArray(String[]::new))
                    .param("labels", labels.toArray(String[]::new))
                    .param("helps", helps.toArray(String[]::new))
                    .param("kinds", kinds.toArray(String[]::new))
                    .param("holdsMany", holdsMany.toArray(Boolean[]::new))
                    .param("textLimits", textLimits.toArray(Integer[]::new))
                    .param("manyLimits", manyLimits.toArray(Integer[]::new))
                    .param("lists", lists.toArray(String[]::new))
                    .param("mustBeGiven", mustBeGiven.toArray(Boolean[]::new))
                    .param("standings", standings.toArray(String[]::new))
                    .param("floors", floors.toArray(Integer[]::new));
        }
    }
}
