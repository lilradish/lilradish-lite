package com.lilradish.lite.domain.inference;

import static java.util.Objects.requireNonNull;

import com.lilradish.lite.domain.declaration.AskedField;
import com.lilradish.lite.domain.declaration.Asking;
import com.lilradish.lite.domain.declaration.FieldKind;
import com.lilradish.lite.domain.declaration.Instruction;
import com.lilradish.lite.domain.declaration.OfferedTerms;
import com.lilradish.lite.domain.wire.CanonicalJson;
import com.lilradish.lite.domain.wire.JsonValue;
import com.lilradish.lite.domain.wire.JsonValue.JsonArray;
import com.lilradish.lite.domain.wire.JsonValue.JsonMember;
import com.lilradish.lite.domain.wire.JsonValue.JsonNull;
import com.lilradish.lite.domain.wire.JsonValue.JsonObject;
import com.lilradish.lite.domain.wire.JsonValue.JsonString;
import java.util.IdentityHashMap;
import java.util.List;
import org.jspecify.annotations.Nullable;

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

    /* The words a production is refused with hold no control but a tab and a line feed, so a quote is worst. */
    private static final long WORST_WORDS = written(new JsonString("\"".repeat(Envelope.MOST_REFUSAL_WORDS)));

    private SendMeasure() {}

    /** @param toldWhatHappened whether the step tells the next asking what happened, which only the step says */
    public static long mostSent(Asking asking, boolean toldWhatHappened) {
        requireNonNull(asking, "SendMeasure asking must not be null");
        return measured(
                PayloadMember.sent(toldWhatHappened, false, !asking.takes().isEmpty()),
                asking.instruction(),
                asking.takes(),
                asking.gives(),
                Envelope.toProduce(asking.gives(), asking.instruction()));
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
                Envelope.toReview(asking.gives(), asking.instruction()));
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
    public static long mostSentToSearch(Asking asking, boolean toldWhatHappened) {
        requireNonNull(asking, "SendMeasure asking must not be null");
        return measured(
                PayloadMember.sent(toldWhatHappened, false, !asking.takes().isEmpty()),
                asking.instruction(),
                asking.takes(),
                asking.gives(),
                Envelope.toSearch(asking.gives(), asking.instruction()));
    }

    /** The longest search review without its not-yet-known search record. */
    public static long mostSentToReviewSearch(Asking asking) {
        requireNonNull(asking, "SendMeasure asking must not be null");
        return measured(
                PayloadMember.sent(true, true, !asking.takes().isEmpty()),
                asking.instruction(),
                asking.takes(),
                asking.gives(),
                Envelope.toReviewSearch(asking.gives(), asking.instruction()));
    }

    private static long measured(
            List<PayloadMember> sent,
            @Nullable Instruction instruction,
            List<AskedField> takes,
            List<AskedField> gives,
            String envelope) {
        LengthCache cache = new LengthCache();
        long payload = framed(EMPTY_OBJECT, sent.size());
        for (PayloadMember member : sent) {
            long most =
                    switch (member) {
                        case VERSION -> written(Payload.VERSION_WRITTEN);
                        case INSTRUCTION ->
                            written(new JsonString(requireNonNull(instruction).value()));
                        case TAKES -> half(takes, cache);
                        case FOUND_ON_THE_WEB -> fromTheWeb(takes);
                        case REFUSED -> refused(gives, cache);
                        case ANSWER -> half(gives, cache);
                        case DECIDING -> deciding(gives);
                    };
            payload = sum(payload, sum(named(member.written()), most));
        }
        return sum(envelope.codePointCount(0, envelope.length()), payload);
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

    /** Written length excluding none: zero for a scalar with no possible value, empty-array length for a many. */
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
    }

    private static final class TermLengths {
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
