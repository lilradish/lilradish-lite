package org.lilradish.lite.domain.inference;

import static java.util.Objects.requireNonNull;

import java.util.List;
import org.jspecify.annotations.Nullable;
import org.lilradish.lite.domain.declaration.AskedField;
import org.lilradish.lite.domain.declaration.Asking;
import org.lilradish.lite.domain.declaration.Instruction;
import org.lilradish.lite.domain.declaration.OfferedTerms;
import org.lilradish.lite.domain.wire.CanonicalJson;
import org.lilradish.lite.domain.wire.JsonValue;
import org.lilradish.lite.domain.wire.JsonValue.JsonArray;
import org.lilradish.lite.domain.wire.JsonValue.JsonMember;
import org.lilradish.lite.domain.wire.JsonValue.JsonNull;
import org.lilradish.lite.domain.wire.JsonValue.JsonObject;
import org.lilradish.lite.domain.wire.JsonValue.JsonString;

/**
 * The most one asking could send, counted as it would be kept: the {@link Envelope}'s text beside the
 * {@link Payload} as {@link CanonicalJson} writes it, escapes included, a character being a code point. Every
 * input is at its declared limit, every free text escaped at its worst, and where the step tells the next
 * asking, or a review is sent, a refused answer at its limit is told with the words at theirs. Worked out, never
 * written: a text's limit may run to millions.
 *
 * <p>A count past what a long holds is held at {@link Long#MAX_VALUE}, which is then past counting: a many held
 * within a many reaches it, the limit each field is held to counting only its text.
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

    /**
     * The most one asking to produce could send.
     *
     * @param toldWhatHappened whether the step tells the next asking what happened, which only the step says
     */
    public static long mostSent(Asking asking, boolean toldWhatHappened) {
        requireNonNull(asking, "SendMeasure asking must not be null");
        return measured(
                PayloadMember.sent(toldWhatHappened, false),
                asking.instruction(),
                asking.takes(),
                asking.gives(),
                Envelope.toProduce(asking.gives()));
    }

    /**
     * The most a review of one production could send: what that production was sent, told a refused answer
     * whatever the step says, since a person answering in a model's place is shown one; and every value it gave
     * back at its limit, each to be decided.
     */
    public static long mostSentToReview(Asking asking) {
        requireNonNull(asking, "SendMeasure asking must not be null");
        return measured(
                PayloadMember.sent(true, true),
                asking.instruction(),
                asking.takes(),
                asking.gives(),
                Envelope.toReview(asking.gives()));
    }

    /**
     * The most a review could send of a production no instruction asked for, taking and giving back what
     * {@code takes} and {@code gives} declare: every input at its limit, what is added, an answer at its limit to
     * decide, and a refused one with the words it was refused with.
     */
    public static long mostSentToReview(List<AskedField> takes, List<AskedField> gives) {
        requireNonNull(takes, "SendMeasure takes must not be null");
        requireNonNull(gives, "SendMeasure gives must not be null");
        return measured(PayloadMember.sentUninstructed(true), null, takes, gives, Envelope.toReviewUninstructed(gives));
    }

    private static long measured(
            List<PayloadMember> sent,
            @Nullable Instruction instruction,
            List<AskedField> takes,
            List<AskedField> gives,
            String envelope) {
        long payload = framed(EMPTY_OBJECT, sent.size());
        for (PayloadMember member : sent) {
            long most =
                    switch (member) {
                        case VERSION -> written(Payload.VERSION_WRITTEN);
                        case INSTRUCTION ->
                            written(new JsonString(requireNonNull(instruction).value()));
                        case TAKES -> half(takes);
                        case REFUSED -> refused(gives);
                        case ANSWER -> half(gives);
                        case DECIDING -> deciding(gives);
                    };
            payload = sum(payload, sum(named(member.written()), most));
        }
        return sum(envelope.codePointCount(0, envelope.length()), payload);
    }

    private static long refused(List<AskedField> gives) {
        long values = sum(named(PayloadMember.REFUSED_VALUES), half(gives));
        long words = sum(named(PayloadMember.REFUSED_WORDS), words(gives));
        return sum(framed(EMPTY_OBJECT, 2), sum(values, words));
    }

    /** Each field at its longest, or none where none is longer. */
    private static long half(List<AskedField> fields) {
        long length = framed(EMPTY_OBJECT, fields.size());
        for (AskedField field : fields) {
            length = sum(length, sum(named(field.name().value()), Math.max(NONE, longest(field))));
        }
        return length;
    }

    private static long words(List<AskedField> gives) {
        long length = framed(EMPTY_OBJECT, gives.size());
        for (AskedField field : gives) {
            length = sum(length, sum(named(field.name().value()), WORST_WORDS));
        }
        return length;
    }

    private static long deciding(List<AskedField> gives) {
        long length = framed(EMPTY_ARRAY, gives.size());
        for (AskedField field : gives) {
            length = sum(length, written(new JsonString(field.name().value())));
        }
        return length;
    }

    /** The longest value of the field other than none, which is nothing where it could hold none other. */
    private static long longest(AskedField field) {
        long item = item(field);
        Integer most = field.most();
        if (most == null) {
            return item;
        }
        return item == 0 ? EMPTY_ARRAY : sum(framed(EMPTY_ARRAY, most), product(most, item));
    }

    private static long item(AskedField field) {
        return switch (field.kind()) {
            case TEXT -> sum(QUOTES, product(requireNonNull(field.longest()), WORST_CHARACTER));
            case TERM -> longestTerm(requireNonNull(field.terms()));
            case FIELDS -> half(field.fields());
            case NUMBER, DATE, MOMENT, YES_NO -> written(field.kind().longestWritten());
        };
    }

    private static long longestTerm(OfferedTerms offered) {
        long longest = 0;
        for (OfferedTerms.Offered term : offered.terms()) {
            longest = Math.max(longest, written(new JsonString(term.term().value())));
        }
        return longest;
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
