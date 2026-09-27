package org.lilradish.lite.domain.run;

import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.libprunus.core.log.annotation.DoNotLog;
import org.lilradish.lite.domain.declaration.Declaration;
import org.lilradish.lite.domain.declaration.Field;
import org.lilradish.lite.domain.declaration.FieldName;
import org.lilradish.lite.domain.declaration.FieldShape;
import org.lilradish.lite.domain.declaration.HowMany;
import org.lilradish.lite.domain.wire.JsonValue;
import org.lilradish.lite.domain.wire.JsonValue.JsonArray;
import org.lilradish.lite.domain.wire.JsonValue.JsonMember;
import org.lilradish.lite.domain.wire.JsonValue.JsonObject;
import org.lilradish.lite.domain.workflow.Binding;
import org.lilradish.lite.domain.workflow.BindingSource;
import org.lilradish.lite.domain.workflow.Pointer;

/** What a step takes in one run, read from its bindings: each binding's value beside what it was read from. */
public final class StepInputs {

    private static final JsonValue NONE = new JsonValue.JsonNull();

    private StepInputs() {}

    /**
     * One per binding of {@code step}, in the order written, where every step it reads from has a value standing
     * there; none where one has not yet. What the store holds that no approved version could bind is thrown.
     */
    public static Optional<List<InputRecord>> traced(RunSnapshot run, StepSnapshot step, Declaration takes) {
        requireNonNull(takes, "StepInputs takes must not be null");
        return bound(run, step).map(read -> traced(read, takes));
    }

    private static List<InputRecord> traced(List<BindingRecord> bound, Declaration takes) {
        List<InputRecord> traced = new ArrayList<>(bound.size());
        for (Reached each : reached(bound, takes)) {
            traced.add(new InputRecord(each.id(), each.input().source()));
        }
        return List.copyOf(traced);
    }

    /**
     * One value per field {@code takes} declares at its first level, in declared order: none where nothing was
     * bound there, and every object built or taken whole holding each field declared of it, in declared order and
     * none where unfilled, at every depth. What the store holds that no approved version could bind is thrown.
     */
    public static JsonObject filled(Declaration takes, List<BindingRecord> bound) {
        requireNonNull(takes, "StepInputs takes must not be null");
        requireNonNull(bound, "StepInputs bound must not be null");
        return object(takes.fields(), 0, reached(bound, takes));
    }

    /**
     * What {@code aTry} of {@code step} took, one per binding it took through in the order written, read back from
     * what it was traced to: a value a step of this run made, and otherwise what the binding reads now.
     */
    public static List<BindingRecord> took(RunSnapshot run, StepSnapshot step, TryRecord aTry) {
        requireNonNull(run, "StepInputs run must not be null");
        requireNonNull(step, "StepInputs step must not be null");
        requireNonNull(aTry, "StepInputs try must not be null");
        List<BindingRecord> taken = new ArrayList<>(aTry.inputs().size());
        for (Binding binding : step.planned().bindings()) {
            for (InputRecord input : aTry.inputs()) {
                if (input.binding().equals(binding.id())) {
                    taken.add(new BindingRecord(binding, took(run, binding, input.source()), input.source()));
                    break;
                }
            }
        }
        return List.copyOf(taken);
    }

    private static JsonValue took(RunSnapshot run, Binding binding, @Nullable ProductionValueId source) {
        if (source != null && binding.source() instanceof BindingSource.StepOutput output) {
            List<FieldName> names = output.pointer().names();
            return within(made(run, binding, output, source).value(), names.subList(1, names.size()));
        }
        return bound(run, binding)
                .map(BindingRecord::value)
                .orElseThrow(() -> new IllegalStateException("Binding " + binding.id() + " read nothing"));
    }

    private static ValueRecord made(
            RunSnapshot run, Binding binding, BindingSource.StepOutput output, ProductionValueId source) {
        StepSnapshot from = run.step(new WorkflowStepId(output.step()))
                .orElseThrow(() -> new IllegalStateException(
                        "Binding " + binding.id() + " reads a step its version does not hold"));
        for (TryRecord aTry : from.tries()) {
            for (ValueRecord value : aTry.values()) {
                if (value.id().equals(source)) {
                    return value;
                }
            }
        }
        throw new IllegalStateException("Binding " + binding.id() + " took a value its step does not hold");
    }

    /** Each binding's value, in the order written; none where a step it reads from has no value standing yet. */
    public static Optional<List<BindingRecord>> bound(RunSnapshot run, StepSnapshot step) {
        requireNonNull(run, "StepInputs run must not be null");
        requireNonNull(step, "StepInputs step must not be null");
        List<BindingRecord> bound = new ArrayList<>(step.planned().bindings().size());
        for (Binding binding : step.planned().bindings()) {
            Optional<BindingRecord> read = bound(run, binding);
            if (read.isEmpty()) {
                return Optional.empty();
            }
            bound.add(read.get());
        }
        return Optional.of(List.copyOf(bound));
    }

    /** One binding's value as it would be read now; none where the step it reads from has none standing yet. */
    public static Optional<BindingRecord> bound(RunSnapshot run, Binding binding) {
        requireNonNull(run, "StepInputs run must not be null");
        requireNonNull(binding, "StepInputs binding must not be null");
        return switch (binding.source()) {
            case BindingSource.Written written -> Optional.of(new BindingRecord(binding, written.constant(), null));
            case BindingSource.WorkflowInput input -> {
                JsonObject started = run.startedWith();
                if (started == null) {
                    throw new IllegalStateException(
                            "Run " + run.run().value() + " beneath another reads what no run above bound into it");
                }
                yield Optional.of(new BindingRecord(
                        binding, within(started, input.pointer().names()), null));
            }
            case BindingSource.StepOutput output -> fromStep(run, binding, output);
        };
    }

    private static Optional<BindingRecord> fromStep(RunSnapshot run, Binding binding, BindingSource.StepOutput output) {
        StepSnapshot source = run.step(new WorkflowStepId(output.step()))
                .orElseThrow(() -> new IllegalStateException(
                        "Binding " + binding.id() + " reads a step its version does not hold"));
        TryRecord newest = source.newest().filter(TryRecord::yielded).orElse(null);
        if (newest == null) {
            return Optional.empty();
        }
        List<FieldName> names = output.pointer().names();
        String first = names.getFirst().value();
        ValueRecord value = newest.values().stream()
                .filter(held -> held.field().equals(first))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Binding " + binding.id() + " reads a field its step gave nothing back for"));
        if (ValueStanding.of(newest, value) != ValueStanding.STANDS) {
            return Optional.empty();
        }
        return Optional.of(
                new BindingRecord(binding, within(value.value(), names.subList(1, names.size())), value.id()));
    }

    /** What stands at those names within {@code value}, a missing member or anything not an object being none. */
    public static JsonValue within(JsonValue value, List<FieldName> names) {
        requireNonNull(value, "StepInputs value must not be null");
        requireNonNull(names, "StepInputs names must not be null");
        JsonValue here = value;
        for (FieldName name : names) {
            if (!(here instanceof JsonObject object)) {
                return NONE;
            }
            here = member(object, name.value());
        }
        return here;
    }

    private static JsonValue member(JsonObject object, String name) {
        for (JsonMember member : object.members()) {
            if (member.name().equals(name)) {
                return member.value();
            }
        }
        return NONE;
    }

    /**
     * Each binding beside the one field it fills of {@code takes}, in the order written: one within many names no
     * place, and no two fill one place or one within the other, as submitting refuses.
     */
    private static List<Reached> reached(List<BindingRecord> bound, Declaration takes) {
        List<Reached> reached = new ArrayList<>(bound.size());
        for (BindingRecord input : bound) {
            Pointer target = input.binding().target();
            if (target == null) {
                throw new IllegalStateException("Binding " + input.binding().id() + " fills no input");
            }
            if (!(target.reach(takes.fields()) instanceof Pointer.Reach.At)) {
                throw new IllegalStateException("Binding " + input.binding().id() + " fills nothing its step takes");
            }
            for (Reached earlier : reached) {
                if (target.within(earlier.target()) || earlier.target().within(target)) {
                    throw new IllegalStateException(
                            "Binding " + input.binding().id() + " fills what binding " + earlier.id() + " fills");
                }
            }
            reached.add(new Reached(input, target));
        }
        return reached;
    }

    private static JsonObject object(List<Field> fields, int depth, List<Reached> within) {
        List<JsonMember> members = new ArrayList<>(fields.size());
        for (Field field : fields) {
            List<Reached> here = null;
            for (Reached each : within) {
                if (each.target().names().get(depth).equals(field.name())) {
                    if (here == null) {
                        here = new ArrayList<>();
                    }
                    here.add(each);
                }
            }
            members.add(new JsonMember(field.name().value(), here == null ? NONE : filled(field, depth, here)));
        }
        return new JsonObject(members);
    }

    private static JsonValue filled(Field field, int depth, List<Reached> here) {
        Reached first = here.getFirst();
        if (first.target().names().size() == depth + 1) {
            // Alone here: reached refuses any other binding at this field or within it.
            return completed(field, first.input().value(), first.id());
        }
        // Reached through it, so it holds one value of fields: Pointer.reach stops at anything else.
        return object(((FieldShape.Nested) field.shape()).fields(), depth + 1, here);
    }

    /** Every field a value of fields declares is there, none where nothing was given for it; no other may be. */
    private static JsonValue completed(Field field, JsonValue value, UUID binding) {
        if (!(field.shape() instanceof FieldShape.Nested nested)) {
            return value;
        }
        if (field.howMany() instanceof HowMany.One) {
            return value instanceof JsonObject object ? members(nested.fields(), object, binding) : value;
        }
        if (!(value instanceof JsonArray many)) {
            return value;
        }
        List<JsonValue> items = new ArrayList<>(many.items().size());
        for (JsonValue item : many.items()) {
            items.add(item instanceof JsonObject object ? members(nested.fields(), object, binding) : item);
        }
        return new JsonArray(items);
    }

    private static JsonObject members(List<Field> fields, JsonObject object, UUID binding) {
        List<JsonMember> members = new ArrayList<>(fields.size());
        int found = 0;
        for (Field inner : fields) {
            JsonValue value = NONE;
            for (JsonMember member : object.members()) {
                if (member.name().equals(inner.name().value())) {
                    value = member.value();
                    found++;
                    break;
                }
            }
            members.add(new JsonMember(inner.name().value(), completed(inner, value, binding)));
        }
        if (found != object.members().size()) {
            throw new IllegalStateException("Binding " + binding + " fills a value holding a field not declared");
        }
        return new JsonObject(members);
    }

    /** A binding beside where it fills. */
    private record Reached(BindingRecord input, Pointer target) {

        UUID id() {
            return input.binding().id();
        }
    }

    /**
     * One binding's value as it would be taken now.
     *
     * @param source the value a step made that it was read from; none for a constant or what the run was started
     *     with
     */
    public record BindingRecord(
            @DoNotLog Binding binding,
            @DoNotLog JsonValue value,
            @Nullable ProductionValueId source) {

        public BindingRecord {
            requireNonNull(binding, "StepInputs.BindingRecord binding must not be null");
            requireNonNull(value, "StepInputs.BindingRecord value must not be null");
        }
    }
}
