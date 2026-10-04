package org.lilradish.lite.domain.inference;

import static java.util.Objects.requireNonNull;

import java.util.IdentityHashMap;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.lilradish.lite.domain.declaration.AskedField;
import org.lilradish.lite.domain.declaration.Asking;
import org.lilradish.lite.domain.declaration.FieldKind;
import org.lilradish.lite.domain.declaration.Instruction;
import org.lilradish.lite.domain.declaration.OfferedTerms;
import org.lilradish.lite.domain.wire.CanonicalJson;
import org.lilradish.lite.domain.wire.JsonValue;
import org.lilradish.lite.domain.wire.JsonValue.JsonArray;
import org.lilradish.lite.domain.wire.JsonValue.JsonMember;
import org.lilradish.lite.domain.wire.JsonValue.JsonNull;
import org.lilradish.lite.domain.wire.JsonValue.JsonObject;
import org.lilradish.lite.domain.wire.JsonValue.JsonString;

/** Counts what would be kept in Unicode code points without materializing declared maxima.
 * A count beyond {@link Long#MAX_VALUE} saturates there.
 */
public final class SendMeasure {

    private static final JsonValue NOTHING = new JsonNull();

    private static final long NONE = written(NOTHING);

    private static final long QUOTES = written(new JsonString(""));

    private static final long EMPTY_OBJECT = written(new JsonObject(List.of()));

    private static final long EMPTY_ARRAY = written(new JsonArray(List.of()));

    private static final long COMMA = written(new JsonArray(List.of(NOTHING, NOTHING))) - EMPTY_ARRAY - 2 * NONE;

    private static final long COLON =
            written(new JsonObject(List.of(new JsonMember("", NOTHING)))) - EMPTY_OBJECT - QUOTES - NONE;

    /* A control with no short escape is written as six characters, the most any one character is. */
    private static final long WORST_CHARACTER = written(new JsonString(Character.toString(0x1))) - QUOTES;

    private static final long DOUBLE_WORST_CHARACTER =
            doubleWritten(new JsonString(Character.toString(0x1))) - doubleWritten(new JsonString(""));

    /* The words a production is refused with hold no control but a tab and a line feed, so a quote is worst. */
    private static final long WORST_WORDS = written(new JsonString("\"".repeat(Envelope.MOST_REFUSAL_WORDS)));

    private SendMeasure() {}

    /**
     * The most one asking to produce could send.
     *
     * @param toldWhatHappened whether the step tells the next asking what happened, which only the step says
     */
    public static long mostSent(Asking asking, boolean toldWhatHappened) {
        requireNonNull(asking, "SendMeasure asking must not be null");
        return measured(
                PayloadMember.sent(toldWhatHappened, false, !asking.takes().isEmpty()),
                asking.instruction(),
                asking.takes(),
                asking.gives(),
                Envelope.toProduce(asking.gives()));
    }

    /**
     * A review counts a refused answer even if the step would not tell its next asking:
     * a person answering in the model's place would be shown it.
     */
    public static long mostSentToReview(Asking asking) {
        requireNonNull(asking, "SendMeasure asking must not be null");
        return measured(
                PayloadMember.sent(true, true, !asking.takes().isEmpty()),
                asking.instruction(),
                asking.takes(),
                asking.gives(),
                Envelope.toReview(asking.gives()));
    }

    /**
     * With no instruction to count, a review still counts declared inputs and the answer
     * at their limits, and a refused answer with its words.
     */
    public static long mostSentToReview(List<AskedField> takes, List<AskedField> gives) {
        requireNonNull(takes, "SendMeasure takes must not be null");
        requireNonNull(gives, "SendMeasure gives must not be null");
        return measured(
                PayloadMember.sentUninstructed(true, !takes.isEmpty()),
                null,
                takes,
                gives,
                Envelope.toReviewUninstructed(gives));
    }

    /** The most one search could send, including a refused output when the next asking is told. */
    public static long mostSentToSearch(
            List<Payload.SearchPart> parts, List<AskedField> gives, boolean toldWhatHappened) {
        requireNonNull(parts, "SendMeasure search parts must not be null");
        requireNonNull(gives, "SendMeasure gives must not be null");
        return measured(
                PayloadMember.sentSearch(toldWhatHappened), null, List.of(), gives, Envelope.toSearch(gives), parts);
    }

    /** The longest search review without its not-yet-known search record. */
    public static long mostSentToReviewSearch(List<Payload.SearchPart> question, List<AskedField> gives) {
        requireNonNull(question, "SendMeasure search parts must not be null");
        requireNonNull(gives, "SendMeasure gives must not be null");
        return measured(
                PayloadMember.sentReviewSearch(true, false),
                null,
                List.of(),
                gives,
                Envelope.toReviewSearch(gives),
                question);
    }

    private static long measured(
            List<PayloadMember> sent,
            @Nullable Instruction instruction,
            List<AskedField> takes,
            List<AskedField> gives,
            String envelope) {
        return measured(sent, instruction, takes, gives, envelope, null);
    }

    private static long measured(
            List<PayloadMember> sent,
            @Nullable Instruction instruction,
            List<AskedField> takes,
            List<AskedField> gives,
            String envelope,
            @Nullable List<Payload.SearchPart> parts) {
        LengthCache cache = new LengthCache();
        long payload = framed(EMPTY_OBJECT, sent.size());
        for (PayloadMember member : sent) {
            long most =
                    switch (member) {
                        case VERSION -> written(Payload.VERSION_WRITTEN);
                        case INSTRUCTION ->
                            written(new JsonString(requireNonNull(instruction).value()));
                        case QUESTION -> searchQuestion(requireNonNull(parts), cache);
                        case TAKES -> half(takes, cache);
                        case FOUND_ON_THE_WEB -> fromTheWeb(takes);
                        case REFUSED -> refused(gives, cache);
                        case SEARCH_RECORD -> throw new IllegalStateException("Search record length is not declared");
                        case ANSWER -> half(gives, cache);
                        case DECIDING -> deciding(gives);
                    };
            payload = sum(payload, sum(named(member.written()), most));
        }
        return sum(envelope.codePointCount(0, envelope.length()), payload);
    }

    private static long searchQuestion(List<Payload.SearchPart> parts, LengthCache cache) {
        long length = QUOTES;
        for (Payload.SearchPart part : parts) {
            long added =
                    switch (part) {
                        case Payload.SearchPart.LiteralPart literal -> literalLength(literal.text());
                        case Payload.SearchPart.ValuePart from -> searchValue(from.field(), cache);
                    };
            length = sum(length, added);
        }
        return length;
    }

    private static long literalLength(String text) {
        for (int index = 0; index < text.length(); index++) {
            if (Character.isSurrogate(text.charAt(index)) && CanonicalJson.halfAPairAt(text, index)) {
                throw new IllegalArgumentException("CanonicalJson refuses a text holding an unpaired surrogate");
            }
        }
        // Sent and stored JSON strings use the same escaping lengths.
        return CanonicalJson.storedLength(new JsonString(text)) - QUOTES;
    }

    private static long searchValue(AskedField field, LengthCache cache) {
        if (Payload.searchValueRaw(field)) {
            return switch (field.kind()) {
                case TEXT -> product(requireNonNull(field.longest()), WORST_CHARACTER);
                case TERM -> longestRawTerm(requireNonNull(field.terms()), cache);
                case DATE, MOMENT, FIELDS, NUMBER, YES_NO, FILE ->
                    throw new IllegalStateException("Search value kind is not raw");
            };
        }
        if (field.most() == null) {
            return embeddedOne(field, cache);
        }
        if (embeddedOne(field, cache) == 0) {
            return 0;
        }
        return embedded(field, cache);
    }

    private static long longestRawTerm(OfferedTerms offered, LengthCache cache) {
        TermLengths lengths = cache.terms.computeIfAbsent(offered, ignored -> new TermLengths());
        if (lengths.raw >= 0) {
            return lengths.raw;
        }
        long longest = 0;
        for (OfferedTerms.Offered term : offered.terms()) {
            longest = Math.max(longest, written(new JsonString(term.term().value())) - QUOTES);
        }
        lengths.raw = longest;
        return longest;
    }

    private static long embedded(AskedField field, LengthCache cache) {
        long item = embeddedOne(field, cache);
        Integer most = field.most();
        if (most == null) {
            return item;
        }
        return item == 0 ? EMPTY_ARRAY : sum(framed(EMPTY_ARRAY, most), product(most, item));
    }

    private static long embeddedOne(AskedField field, LengthCache cache) {
        return switch (field.kind()) {
            case TEXT ->
                sum(
                        doubleWritten(new JsonString("")),
                        product(requireNonNull(field.longest()), DOUBLE_WORST_CHARACTER));
            case TERM -> longestDoubleTerm(requireNonNull(field.terms()), cache);
            case FIELDS -> embeddedFields(field, cache);
            case NUMBER, DATE, MOMENT, YES_NO -> doubleWritten(field.kind().longestWritten());
            case FILE -> throw new IllegalArgumentException("A file is not sent to a model");
        };
    }

    private static long embeddedFields(AskedField outer, LengthCache cache) {
        Long remembered = cache.embeddedFields.get(outer);
        if (remembered != null) {
            return remembered;
        }
        List<AskedField> fields = outer.fields();
        long length = EMPTY_OBJECT;
        boolean first = true;
        for (AskedField field : fields) {
            if (field.kind() == FieldKind.FILE) {
                continue;
            }
            if (!first) {
                length = sum(length, COMMA);
            }
            first = false;
            long name = sum(doubleWritten(new JsonString(field.name().value())), COLON);
            length = sum(length, sum(name, Math.max(doubleWritten(NOTHING), embedded(field, cache))));
        }
        cache.embeddedFields.put(outer, length);
        return length;
    }

    private static long longestDoubleTerm(OfferedTerms offered, LengthCache cache) {
        TermLengths lengths = cache.terms.computeIfAbsent(offered, ignored -> new TermLengths());
        if (lengths.embedded >= 0) {
            return lengths.embedded;
        }
        long longest = 0;
        for (OfferedTerms.Offered term : offered.terms()) {
            longest = Math.max(longest, doubleWritten(new JsonString(term.term().value())));
        }
        lengths.embedded = longest;
        return longest;
    }

    private static long doubleWritten(JsonValue value) {
        String once = CanonicalJson.write(value);
        return written(new JsonString(once)) - QUOTES;
    }

    private static long fromTheWeb(List<AskedField> takes) {
        long length = EMPTY_ARRAY;
        boolean first = true;
        for (AskedField field : takes) {
            if (field.kind() == FieldKind.FILE) {
                continue;
            }
            if (!first) {
                length = sum(length, COMMA);
            }
            first = false;
            length = sum(length, written(new JsonString(field.name().value())));
        }
        return length;
    }

    private static long refused(List<AskedField> gives, LengthCache cache) {
        long values = sum(named(PayloadMember.REFUSED_VALUES), half(gives, cache));
        long words = sum(named(PayloadMember.REFUSED_WORDS), words(gives));
        return sum(framed(EMPTY_OBJECT, 2), sum(values, words));
    }

    /** Each field at its longest, or none where none is longer. */
    private static long half(List<AskedField> fields, LengthCache cache) {
        long length = EMPTY_OBJECT;
        boolean first = true;
        for (AskedField field : fields) {
            if (field.kind() == FieldKind.FILE) {
                continue;
            }
            if (!first) {
                length = sum(length, COMMA);
            }
            first = false;
            length = sum(length, sum(named(field.name().value()), Math.max(NONE, longest(field, cache))));
        }
        return length;
    }

    private static long words(List<AskedField> gives) {
        long length = EMPTY_OBJECT;
        boolean first = true;
        for (AskedField field : gives) {
            if (field.kind() == FieldKind.FILE) {
                continue;
            }
            if (!first) {
                length = sum(length, COMMA);
            }
            first = false;
            length = sum(length, sum(named(field.name().value()), WORST_WORDS));
        }
        return length;
    }

    private static long deciding(List<AskedField> gives) {
        long length = EMPTY_ARRAY;
        boolean first = true;
        for (AskedField field : gives) {
            if (field.kind() == FieldKind.FILE) {
                continue;
            }
            if (!first) {
                length = sum(length, COMMA);
            }
            first = false;
            length = sum(length, written(new JsonString(field.name().value())));
        }
        return length;
    }

    /** The longest value of the field other than none, which is nothing where it could hold none other. */
    private static long longest(AskedField field, LengthCache cache) {
        long item = item(field, cache);
        Integer most = field.most();
        if (most == null) {
            return item;
        }
        return item == 0 ? EMPTY_ARRAY : sum(framed(EMPTY_ARRAY, most), product(most, item));
    }

    private static long item(AskedField field, LengthCache cache) {
        return switch (field.kind()) {
            case TEXT -> sum(QUOTES, product(requireNonNull(field.longest()), WORST_CHARACTER));
            case TERM -> longestTerm(requireNonNull(field.terms()), cache);
            case FIELDS -> half(field.fields(), cache);
            case NUMBER, DATE, MOMENT, YES_NO -> written(field.kind().longestWritten());
            case FILE -> throw new IllegalArgumentException("A file is not sent to a model");
        };
    }

    private static long longestTerm(OfferedTerms offered, LengthCache cache) {
        TermLengths lengths = cache.terms.computeIfAbsent(offered, ignored -> new TermLengths());
        if (lengths.written >= 0) {
            return lengths.written;
        }
        long longest = 0;
        for (OfferedTerms.Offered term : offered.terms()) {
            longest = Math.max(longest, written(new JsonString(term.term().value())));
        }
        lengths.written = longest;
        return longest;
    }

    private static final class LengthCache {
        private final IdentityHashMap<OfferedTerms, TermLengths> terms = new IdentityHashMap<>();
        private final IdentityHashMap<AskedField, Long> embeddedFields = new IdentityHashMap<>();
    }

    private static final class TermLengths {
        private long raw = -1;
        private long embedded = -1;
        private long written = -1;
    }

    private static long framed(long empty, long members) {
        return members == 0 ? empty : sum(empty, product(members - 1, COMMA));
    }

    private static long named(String name) {
        return sum(written(new JsonString(name)), COLON);
    }

    private static long written(JsonValue value) {
        String text = CanonicalJson.write(value);
        return text.codePointCount(0, text.length());
    }

    /* Every count here is at least zero, so a sum past a long wraps below zero and nowhere else. */
    private static long sum(long left, long right) {
        long total = left + right;
        return total < 0 ? Long.MAX_VALUE : total;
    }

    private static long product(long left, long right) {
        return left == 0 || right <= Long.MAX_VALUE / left ? left * right : Long.MAX_VALUE;
    }
}
