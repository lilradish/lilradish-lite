package org.lilradish.lite.app.library;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.libprunus.core.error.ApiErrorException;
import org.lilradish.lite.domain.declaration.DeclarationSide;
import org.lilradish.lite.domain.declaration.Demands;
import org.lilradish.lite.domain.declaration.FieldName;
import org.lilradish.lite.domain.declaration.FieldStanding;
import org.lilradish.lite.domain.failure.RefusalCode;
import org.lilradish.lite.domain.model.ModelMode;
import org.lilradish.lite.domain.model.ModelName;
import org.lilradish.lite.domain.referencelist.Term;
import org.lilradish.lite.domain.registry.EntryKind;
import org.lilradish.lite.domain.registry.EntryVersionId;
import org.lilradish.lite.domain.search.SearchWay;
import org.lilradish.lite.domain.text.ConcealingCharacter;
import org.lilradish.lite.domain.text.Identifiers;
import org.lilradish.lite.domain.text.Legibility;
import org.lilradish.lite.domain.text.ProseRefusal;
import org.lilradish.lite.domain.wire.JsonValue;
import org.lilradish.lite.domain.workflow.ConstantFit;
import org.lilradish.lite.domain.workflow.ModelChoice;
import org.lilradish.lite.domain.workflow.Pointer;
import org.lilradish.lite.domain.workflow.Producer;
import org.lilradish.lite.domain.workflow.StepId;
import org.lilradish.lite.domain.workflow.StepProducer;
import org.lilradish.lite.web.MintedIdentifiers;
import tools.jackson.databind.JsonNode;

/**
 * A workflow draft's steps and what fills what it gives back, as a page sends them: each object exactly the
 * members its kind takes, a step read earlier by the key it was read under, and a step bound from by its place
 * in what was sent. Any other member, or one missing, refuses the whole.
 */
final class FlowBody {

    static final String REFUSED = "This takes a JSON object holding the revision read, every step in the order it"
            + " runs and what fills each value given back, each exactly the members its kind takes.";

    /** The most steps a workflow version holds. */
    static final int MOST_STEPS = 256;

    static final int MOST_QUESTION_PARTS = 256;

    private static final String REVISION = "revision";

    private static final String STEPS = "steps";

    private static final String OUTPUTS = "outputs";

    private static final String STEP_ID = "stepId";

    private static final String NAME = "name";

    private static final String RUNS = "runs";

    private static final String KIND = "kind";

    private static final String VERSION = "version";

    private static final String CODE_STEP = "codeStep";

    private static final String DISCRIMINATOR = "discriminator";

    private static final String GIVES = "gives";

    private static final String CASES = "cases";

    private static final String CASE_ID = "caseId";

    private static final String TERM = "term";

    private static final String WORKFLOW = "workflow";

    private static final String PRODUCER = "producer";

    private static final String MODEL = "model";

    private static final String MODE = "mode";

    private static final String TOLD = "toldWhatHappened";

    private static final String TRIES = "tries";

    private static final String REVIEWER = "reviewer";

    private static final String BINDINGS = "bindings";

    private static final String TARGET = "target";

    private static final String SOURCE = "source";

    private static final String INPUT = "input";

    private static final String STEP = "step";

    private static final String PATH = "path";

    private static final String CONSTANT = "constant";

    private static final String SEARCH_WAY = "searchWay";

    private static final String QUESTION = "question";

    private static final String STANDINGS = "standings";

    private static final String TEXT = "text";

    private static final String FROM = "from";

    private static final Set<String> STANDING_MEMBERS =
            SearchWay.FIELDS.stream().map(FieldName::value).collect(Collectors.toUnmodifiableSet());

    private FlowBody() {}

    /**
     * @param revision the revision of the draft the page read
     * @param steps in the order they run
     * @param outputs what fills each value the workflow gives back
     */
    record Sent(int revision, List<SentStep> steps, List<SentBinding> outputs) {}

    /**
     * @param readAs the key it was read under, which keeps what it pins; none for a step added
     * @param producer only where it runs a question, a code step or a search, and none there until chosen
     * @param tries only where it runs a question, a code step or a search, and none there until chosen
     * @param reviewer the model reviewing its productions, only where it runs a question, a code step or a search;
     *     none where a person does
     */
    record SentStep(
            @Nullable UUID readAs,
            StepId name,
            SentRuns runs,
            @Nullable Producer producer,
            @Nullable Integer tries,
            @Nullable ModelChoice reviewer,
            List<SentBinding> bindings) {}

    sealed interface SentRuns
            permits SentRuns.Unchosen, SentRuns.Pinned, SentRuns.Code, SentRuns.Route, SentRuns.Search {

        record Unchosen() implements SentRuns {}

        record Pinned(EntryKind kind, EntryVersionId version) implements SentRuns {}

        record Code(@Nullable String codeStep) implements SentRuns {}

        record Route(@Nullable SentSource discriminator, DeclarationBody.Sent gives, List<SentCase> cases)
                implements SentRuns {}

        /**
         * @param way none until chosen
         * @param standings how each of the way's fields stands, by its name; one none is chosen for yet is left out
         */
        record Search(@Nullable String way, List<SentPart> question, Map<FieldName, FieldStanding> standings)
                implements SentRuns {}
    }

    /** One part of a search's question: text written into it, or a value read from what a binding may read. */
    sealed interface SentPart permits SentPart.Text, SentPart.Read {

        record Text(String text) implements SentPart {}

        record Read(SentRead source) implements SentPart {}
    }

    /**
     * @param readAs the key it was read under, which keeps what it pins; none for a case added
     * @param term none for the fallback
     */
    record SentCase(
            @Nullable UUID readAs,
            @Nullable String term,
            @Nullable EntryVersionId workflow,
            List<SentBinding> bindings) {}

    record SentBinding(Pointer target, SentSource source) {}

    sealed interface SentSource permits SentRead, SentSource.Written {

        record Input(Pointer pointer) implements SentRead {}

        /** @param index the step's place among those sent, counted from zero */
        record Step(int index, Pointer pointer) implements SentRead {}

        /** @param stored the constant as the store writes it */
        record Written(String stored) implements SentSource {}
    }

    sealed interface SentRead extends SentSource permits SentSource.Input, SentSource.Step {

        Pointer pointer();
    }

    static Sent read(JsonNode body) {
        requireMembers(body, Set.of(REVISION, STEPS, OUTPUTS), Set.of());
        int revision = SeenRevision.in(body, REFUSED);
        JsonNode steps = body.get(STEPS);
        if (!steps.isArray()) {
            throw unusable();
        }
        if (steps.size() > MOST_STEPS) {
            throw LibraryRefusal.STEPS_TOO_MANY.raised();
        }
        List<SentStep> sent = new ArrayList<>(steps.size());
        for (int index = 0; index < steps.size(); index++) {
            sent.add(step(steps.get(index), index, steps.size()));
        }
        return new Sent(revision, sent, bindings(body.get(OUTPUTS), steps.size(), -1));
    }

    private static SentStep step(JsonNode sent, int index, int count) {
        if (!sent.isObject() || !sent.path(RUNS).isObject() && !sent.path(RUNS).isNull()) {
            throw unusable();
        }
        JsonNode runsSent = sent.get(RUNS);
        RunsKind kind = runsSent.isNull() ? null : RunsKind.spelt(textIn(runsSent.path(KIND)));
        if (!runsSent.isNull() && kind == null) {
            throw unusable();
        }
        boolean asks = kind == RunsKind.QUESTION || kind == RunsKind.SEARCH;
        boolean produces = asks || kind == RunsKind.CODE_STEP;
        Set<String> taken = new HashSet<>(Set.of(NAME, RUNS, BINDINGS));
        if (produces) {
            taken.addAll(Set.of(PRODUCER, TRIES, REVIEWER));
        }
        requireMembers(sent, taken, Set.of(STEP_ID));
        return new SentStep(
                keyIn(sent.path(STEP_ID)),
                nameIn(sent.get(NAME)),
                runsIn(runsSent, kind, count, index),
                produces ? producerIn(sent.get(PRODUCER), asks) : null,
                produces ? triesIn(sent.get(TRIES)) : null,
                produces ? choiceIn(sent.get(REVIEWER)) : null,
                bindings(sent.get(BINDINGS), count, index));
    }

    private static SentRuns runsIn(JsonNode sent, @Nullable RunsKind kind, int count, int own) {
        if (kind == null) {
            return new SentRuns.Unchosen();
        }
        return switch (kind) {
            case QUESTION, WORKFLOW -> {
                requireMembers(sent, Set.of(KIND, VERSION), Set.of());
                yield new SentRuns.Pinned(
                        kind == RunsKind.QUESTION ? EntryKind.QUESTION : EntryKind.WORKFLOW,
                        versionIn(sent.get(VERSION)));
            }
            case CODE_STEP -> {
                requireMembers(sent, Set.of(KIND, CODE_STEP), Set.of());
                JsonNode named = sent.get(CODE_STEP);
                yield new SentRuns.Code(named.isNull() ? null : identifierIn(named));
            }
            case ROUTE -> {
                requireMembers(sent, Set.of(KIND, DISCRIMINATOR, GIVES, CASES), Set.of());
                JsonNode chosenBy = sent.get(DISCRIMINATOR);
                SentSource discriminator = chosenBy.isNull() ? null : sourceIn(chosenBy, count, own);
                if (discriminator instanceof SentSource.Written) {
                    throw unusable();
                }
                yield new SentRuns.Route(
                        discriminator,
                        DeclarationBody.read(
                                sent.get(GIVES), DeclarationSide.GIVES, Demands.ofWorkflow(DeclarationSide.GIVES)),
                        cases(sent.get(CASES), count));
            }
            case SEARCH -> {
                requireMembers(sent, Set.of(KIND, SEARCH_WAY, QUESTION, STANDINGS), Set.of());
                JsonNode named = sent.get(SEARCH_WAY);
                yield new SentRuns.Search(
                        named.isNull() ? null : identifierIn(named),
                        question(sent.get(QUESTION), count, own),
                        standingsIn(sent.get(STANDINGS)));
            }
        };
    }

    private static List<SentPart> question(JsonNode sent, int count, int own) {
        if (!sent.isArray()) {
            throw unusable();
        }
        if (sent.size() > MOST_QUESTION_PARTS) {
            throw LibraryRefusal.SEARCH_QUESTION_PARTS_TOO_MANY.raised();
        }
        List<SentPart> parts = new ArrayList<>(sent.size());
        for (JsonNode part : sent) {
            if (part.has(TEXT)) {
                requireMembers(part, Set.of(TEXT), Set.of());
                parts.add(new SentPart.Text(writtenIn(part.get(TEXT))));
            } else {
                requireMembers(part, Set.of(FROM), Set.of());
                parts.add(new SentPart.Read(readIn(part.get(FROM), count, own)));
            }
        }
        return parts;
    }

    /* Refused as an instruction is where it arrives, save that text showing nothing is kept. */
    private static String writtenIn(JsonNode sent) {
        String text = textIn(sent);
        ProseRefusal refusal = ProseRefusal.of(text, true, FlowBody::requireWrittenWell);
        if (refusal == null) {
            return text;
        }
        RefusalCode code = refusal.code(RefusalCode.SEARCH_TEXT_UNUSABLE);
        throw code == RefusalCode.SEARCH_TEXT_UNUSABLE
                ? LibraryRefusal.SEARCH_TEXT_UNUSABLE.raised()
                : LibraryRefusal.answering(code).raised();
    }

    private static void requireWrittenWell(String text) {
        Legibility.requireNotEmpty(text, "Question text");
        Legibility.requireWellFormedProse(text, "Question text");
        Legibility.requireWithinMaximumLength(text, 8192, "Question text");
    }

    /** Each of the way's fields always or never, or none until chosen: a search gives no confidence to stand above. */
    private static Map<FieldName, FieldStanding> standingsIn(JsonNode sent) {
        requireMembers(sent, STANDING_MEMBERS, Set.of());
        Map<FieldName, FieldStanding> standings = HashMap.newHashMap(SearchWay.FIELDS.size());
        for (FieldName field : SearchWay.FIELDS) {
            JsonNode standing = sent.get(field.value());
            if (standing.isNull()) {
                continue;
            }
            String spelled = textIn(standing);
            if (FieldStanding.ALWAYS.published().equals(spelled)) {
                standings.put(field, FieldStanding.ALWAYS);
            } else if (FieldStanding.NEVER.published().equals(spelled)) {
                standings.put(field, FieldStanding.NEVER);
            } else {
                throw unusable();
            }
        }
        return standings;
    }

    private static List<SentCase> cases(JsonNode sent, int count) {
        if (!sent.isArray()) {
            throw unusable();
        }
        List<SentCase> cases = new ArrayList<>(sent.size());
        Set<String> terms = new HashSet<>();
        boolean fallback = false;
        for (JsonNode routeCase : sent) {
            requireMembers(routeCase, Set.of(TERM, WORKFLOW, BINDINGS), Set.of(CASE_ID));
            JsonNode term = routeCase.get(TERM);
            if (term.isNull() && fallback) {
                throw unusable();
            }
            fallback |= term.isNull();
            String on = term.isNull() ? null : termIn(term);
            if (on != null && !terms.add(on)) {
                throw LibraryRefusal.CASE_TERM_REPEATED.raised();
            }
            JsonNode workflow = routeCase.get(WORKFLOW);
            cases.add(new SentCase(
                    keyIn(routeCase.path(CASE_ID)),
                    on,
                    workflow.isNull() ? null : versionIn(workflow),
                    bindings(routeCase.get(BINDINGS), count, -1)));
        }
        return cases;
    }

    /** {@code own} is the place of the step the bindings fill, which none of them may read; none for any other. */
    private static List<SentBinding> bindings(JsonNode sent, int count, int own) {
        if (!sent.isArray()) {
            throw unusable();
        }
        List<SentBinding> bindings = new ArrayList<>(sent.size());
        for (JsonNode binding : sent) {
            requireMembers(binding, Set.of(TARGET, SOURCE), Set.of());
            bindings.add(new SentBinding(pointerIn(binding.get(TARGET)), sourceIn(binding.get(SOURCE), count, own)));
        }
        return bindings;
    }

    private static SentSource sourceIn(JsonNode sent, int count, int own) {
        if (sent.has(INPUT) || sent.has(STEP)) {
            return readIn(sent, count, own);
        }
        requireMembers(sent, Set.of(CONSTANT), Set.of());
        JsonValue constant;
        try {
            constant = ConstantJson.sent(textIn(sent.get(CONSTANT)));
        } catch (ConstantJson.NotWrittenPlainly notPlain) {
            throw LibraryRefusal.CONSTANT_DOES_NOT_FIT.raised();
        } catch (IllegalArgumentException unread) {
            throw unusable();
        }
        ConcealingCharacter concealing = ConstantFit.concealing(constant);
        if (concealing != null) {
            throw LibraryRefusal.refusingProse(ProseRefusal.of(concealing), LibraryRefusal.CONSTANT_DOES_NOT_FIT);
        }
        if (ConstantFit.unwritable(constant)) {
            throw LibraryRefusal.CONSTANT_DOES_NOT_FIT.raised();
        }
        try {
            return new SentSource.Written(ConstantJson.written(constant));
        } catch (IllegalArgumentException unwritable) {
            throw unusable();
        }
    }

    private static SentRead readIn(JsonNode sent, int count, int own) {
        if (sent.has(INPUT)) {
            requireMembers(sent, Set.of(INPUT), Set.of());
            return new SentSource.Input(pointerIn(sent.get(INPUT)));
        }
        requireMembers(sent, Set.of(STEP, PATH), Set.of());
        JsonNode index = sent.get(STEP);
        if (!index.isInt() || index.intValue() < 0 || index.intValue() >= count || index.intValue() == own) {
            throw unusable();
        }
        return new SentSource.Step(index.intValue(), pointerIn(sent.get(PATH)));
    }

    /**
     * A question or a search is produced by a model or a person, a code step by code or a person saying it was
     * done.
     */
    private static @Nullable Producer producerIn(JsonNode sent, boolean asks) {
        if (sent.isNull()) {
            return null;
        }
        String kind = sent.isObject() ? textIn(sent.path(KIND)) : null;
        if (StepProducer.PERSON.published().equals(kind)) {
            requireMembers(sent, Set.of(KIND), Set.of());
            return new Producer.Person();
        }
        if (!asks && StepProducer.CODE.published().equals(kind)) {
            requireMembers(sent, Set.of(KIND), Set.of());
            return new Producer.Code();
        }
        if (!asks || !StepProducer.MODEL.published().equals(kind)) {
            throw unusable();
        }
        requireMembers(sent, Set.of(KIND, MODEL, MODE, TOLD), Set.of());
        JsonNode told = sent.get(TOLD);
        if (!told.isBoolean()) {
            throw unusable();
        }
        return new Producer.Model(chosen(sent), told.booleanValue());
    }

    private static @Nullable ModelChoice choiceIn(JsonNode sent) {
        if (sent.isNull()) {
            return null;
        }
        requireMembers(sent, Set.of(MODEL, MODE), Set.of());
        return chosen(sent);
    }

    /** A model and the mode it runs in, from an object known to hold both members. */
    static ModelChoice chosen(JsonNode sent) {
        JsonNode mode = sent.get(MODE);
        try {
            return new ModelChoice(
                    new ModelName(textIn(sent.get(MODEL))), mode.isNull() ? null : new ModelMode(textIn(mode)));
        } catch (IllegalArgumentException refused) {
            throw unusable();
        }
    }

    private static @Nullable Integer triesIn(JsonNode sent) {
        if (sent.isNull()) {
            return null;
        }
        if (!sent.isInt() || sent.intValue() < 1) {
            throw unusable();
        }
        return sent.intValue();
    }

    private static StepId nameIn(JsonNode sent) {
        String name = textIn(sent);
        try {
            return new StepId(name);
        } catch (IllegalArgumentException refused) {
            throw LibraryRefusal.STEP_NAME_UNUSABLE.raised(refused);
        }
    }

    private static String identifierIn(JsonNode sent) {
        try {
            return Identifiers.requireText(textIn(sent), "A name a release gives");
        } catch (IllegalArgumentException refused) {
            throw unusable();
        }
    }

    /** One to a term's most characters, on one line; whether a list offers it is submitting's to judge. */
    private static String termIn(JsonNode sent) {
        String term = textIn(sent);
        try {
            Legibility.requireNotEmpty(term, "Term");
            Legibility.requireOneWellFormedLine(term, "Term");
            Legibility.requireWithinMaximumLength(term, Term.MAXIMUM_LENGTH, "Term");
        } catch (IllegalArgumentException refused) {
            throw unusable();
        }
        return term;
    }

    private static Pointer pointerIn(JsonNode sent) {
        try {
            return Pointer.parse(textIn(sent));
        } catch (IllegalArgumentException refused) {
            throw unusable();
        }
    }

    private static EntryVersionId versionIn(JsonNode sent) {
        return MintedIdentifiers.read(textIn(sent))
                .map(EntryVersionId::new)
                .orElseThrow(LibraryRefusal.VERSION_NOT_PINNABLE::raised);
    }

    /* A key that is no identifier this system mints was never read here, so it keeps nothing. */
    private static @Nullable UUID keyIn(JsonNode sent) {
        if (sent.isMissingNode() || sent.isNull()) {
            return null;
        }
        return MintedIdentifiers.read(textIn(sent)).orElse(null);
    }

    private static String textIn(JsonNode sent) {
        if (!sent.isString()) {
            throw unusable();
        }
        return sent.asString();
    }

    private static void requireMembers(JsonNode sent, Set<String> required, Set<String> optional) {
        if (!sent.isObject()) {
            throw unusable();
        }
        int present = 0;
        for (String member : sent.propertyNames()) {
            if (required.contains(member)) {
                present++;
            } else if (!optional.contains(member)) {
                throw unusable();
            }
        }
        if (present != required.size()) {
            throw unusable();
        }
    }

    static ApiErrorException unusable() {
        return new ApiErrorException(RefusalCode.BODY_UNUSABLE, REFUSED);
    }
}
