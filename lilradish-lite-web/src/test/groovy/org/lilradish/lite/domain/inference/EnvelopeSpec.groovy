package org.lilradish.lite.domain.inference

import static org.lilradish.lite.testutil.inference.Askings.GIVES
import static org.lilradish.lite.testutil.inference.Askings.held
import static org.lilradish.lite.testutil.inference.Askings.offered
import static org.lilradish.lite.testutil.inference.Askings.term
import static org.lilradish.lite.testutil.inference.Askings.text

import org.lilradish.lite.domain.declaration.Asking
import org.lilradish.lite.domain.declaration.Declaration
import org.lilradish.lite.domain.declaration.DeclarationSide
import org.lilradish.lite.domain.declaration.Demand
import org.lilradish.lite.domain.declaration.Demands
import org.lilradish.lite.domain.declaration.Field
import org.lilradish.lite.domain.declaration.FieldName
import org.lilradish.lite.domain.declaration.FieldShape
import org.lilradish.lite.domain.declaration.FieldStanding
import org.lilradish.lite.domain.declaration.HowMany
import org.lilradish.lite.domain.declaration.OfferedTerms
import org.lilradish.lite.domain.referencelist.ListNote
import spock.lang.Specification

class EnvelopeSpec extends Specification {

    static final String SENT = 'You are sent a JSON object. "instruction" says what is to be done. "takes" holds what it is done ' +
            'with, a member for each field it takes, null where nothing was given. "refused", where it is there, holds ' +
            'an answer given before and refused: "values" as they were given, and "words" saying why each refused ' +
            'value was refused.\n'

    static final String TO_PRODUCE = SENT +
            'Answer with one JSON object and nothing else: no words around it and no code fence. It has two ' +
            'members. "values" holds a member for every field below and no other, each as its line says. ' +
            '"confidences" holds a member for every field below that asks how sure you are and no other, each a ' +
            'whole number from 0 to 100.\n' +
            'A field holding many is an array of at most as many values as it says.\n' +
            'A field that must be given is never null, and where it holds many, never empty.\n' +
            'The fields:\n'

    /** Each of {@code GIVES}, none of which must be given, as a model producing is told it. */
    static final List<String> PRODUCED = [
            '- "summary": null where there is nothing to give; text of at most 5 characters, as a JSON string; say ' +
                    'how sure you are',
            '- "tags": null or an empty array where there is nothing to give; many, at most 2, each a term, as a ' +
                    'JSON string, one of these:',
            '  - "Billing", meaning "A charge is disputed."',
            '  - "Delivery", meaning "It came \\"late\\"."',
            '  On choosing: "Choose what is asked."',
            '- "amount": null where there is nothing to give; a number, as a JSON number written plainly with no ' +
                    'exponent, of at most 38 digits in all',
            '- "due": null where there is nothing to give; a date, as a JSON string written year-month-day such as ' +
                    '"2024-03-31", the year from 0001 to 9999',
            '- "at": null where there is nothing to give; a moment, as a JSON string written as a date, "T", a time ' +
                    'to the second with at most 6 digits of its fraction, and its offset as a sign, hours and minutes ' +
                    'no further than 14:00 either way, such as "2024-03-31T09:30:00.5+02:00"; never "Z" or "-00:00"',
            '- "urgent": null where there is nothing to give; yes or no, as JSON true or false',
            '- "details": null where there is nothing to give; fields, as a JSON object holding a member for each ' +
                    'field below it and no other:',
            '  - "product": null where there is nothing to give; text of at most 3 characters, as a JSON string',
            '  - "codes": null or an empty array where there is nothing to give; many, at most 2, each text of at ' +
                    'most 2 characters, as a JSON string']

    /** Each of {@code GIVES} as a model reviewing is told it: as declared, with nothing of how it may be empty. */
    static final List<String> DECLARED = [
            '- "summary": text of at most 5 characters, as a JSON string',
            '- "tags": many, at most 2, each a term, as a JSON string, one of these:',
            '  - "Billing", meaning "A charge is disputed."',
            '  - "Delivery", meaning "It came \\"late\\"."',
            '  On choosing: "Choose what is asked."',
            '- "amount": a number, as a JSON number written plainly with no exponent, of at most 38 digits in all',
            '- "due": a date, as a JSON string written year-month-day such as "2024-03-31", the year from 0001 to 9999',
            '- "at": a moment, as a JSON string written as a date, "T", a time to the second with at most 6 digits of ' +
                    'its fraction, and its offset as a sign, hours and minutes no further than 14:00 either way, such ' +
                    'as "2024-03-31T09:30:00.5+02:00"; never "Z" or "-00:00"',
            '- "urgent": yes or no, as JSON true or false',
            '- "details": fields, as a JSON object holding a member for each field below it and no other:',
            '  - "product": text of at most 3 characters, as a JSON string',
            '  - "codes": many, at most 2, each text of at most 2 characters, as a JSON string']

    /** Pinned whole: this text is what every model producing is told, and a release changing it changes the version. */
    def "a model producing is told how to answer, and each field given back by name, kind, limit and how it is empty"() {
        expect:
        Envelope.toProduce(GIVES) == TO_PRODUCE + PRODUCED.join("\n") + "\n"
    }

    /** Pinned whole: a term and its meaning are written as JSON writes them, and a list with no note says none. */
    def "a model is told a list with no note as its terms alone, each term written as JSON writes it"() {
        given:
        def list = new OfferedTerms([offered('say "no"\\', 'Refused, "flatly".')], null)

        expect:
        Envelope.toProduce([term("verdict", list)]) == TO_PRODUCE +
                '- "verdict": null where there is nothing to give; a term, as a JSON string, one of these:\n' +
                '  - "say \\"no\\"\\\\", meaning "Refused, \\"flatly\\"."\n'
    }

    /** Pinned whole: a note may hold a tab and a line feed, told escaped as JSON escapes them, so on one line. */
    def "a note holding a tab and a line feed is told with both escaped, on the note's one line"() {
        given:
        def list = new OfferedTerms([offered("One", "Just one.")], new ListNote("Choose\tone.\nOnly one."))

        expect:
        Envelope.toProduce([term("pick", list)]) == TO_PRODUCE +
                '- "pick": null where there is nothing to give; a term, as a JSON string, one of these:\n' +
                '  - "One", meaning "Just one."\n' +
                '  On choosing: "Choose\\tone.\\nOnly one."\n'
    }

    def "a model producing is asked how sure it is where a value stands above a confidence, never told above which"() {
        given:
        def gives = Asking.told(new Declaration(DeclarationSide.GIVES, Demands.ofQuestion(DeclarationSide.GIVES), [
                new Field(new FieldName("summary"), null, null, new FieldShape.Text(400), new HowMany.One(),
                        new Demand.Stands(true, FieldStanding.ABOVE_CONFIDENCE, 87))]), [:])

        when:
        def told = Envelope.toProduce(gives)

        then:
        told.endsWith(
                '- "summary": must be given; text of at most 400 characters, as a JSON string; say how sure you are\n')
        !told.contains("87")
    }

    /**
     * Pinned whole: a field that must be given is said to be at every depth, fields holding fields included, and
     * never how it could be empty; one that need not be is told how it says nothing.
     */
    def "a model producing is told which fields must be given at every depth, and a model reviewing is told none"() {
        given:
        def gives = [
                text("summary", 5, null, true, true),
                held("details", [text("product", 3, null, false, true), text("codes", 2, 2, false, true),
                                 text("note", 4)], null, true),
                text("labels", 6, 3)]

        expect:
        Envelope.toProduce(gives) == TO_PRODUCE +
                '- "summary": must be given; text of at most 5 characters, as a JSON string; say how sure you are\n' +
                '- "details": must be given; fields, as a JSON object holding a member for each field below it and ' +
                'no other:\n' +
                '  - "product": must be given; text of at most 3 characters, as a JSON string\n' +
                '  - "codes": must be given; many, at most 2, each text of at most 2 characters, as a JSON string\n' +
                '  - "note": null where there is nothing to give; text of at most 4 characters, as a JSON string\n' +
                '- "labels": null or an empty array where there is nothing to give; many, at most 3, each text of at ' +
                'most 6 characters, as a JSON string\n'
        !Envelope.toReview(gives).contains("must be given;")
        !Envelope.toReview(gives).contains("where there is nothing to give")
    }

    /** Pinned whole, as what producing is told is; how sure the producer was is no part of it. */
    def "a model reviewing is told how to decide, and each field as it was declared"() {
        when:
        def told = Envelope.toReview(GIVES)

        then:
        told == SENT +
                '"answer" holds the values given back for it, a member for each field below, null where there was ' +
                'nothing. "deciding" names the fields whose values are to be decided.\n' +
                'Answer with one JSON object and nothing else: no words around it and no code fence. It has one ' +
                'member, "decisions", holding a member for every field "deciding" names and no other: ' +
                '{"outcome":"assured"} where its value holds, or {"outcome":"refused","words":"..."} where it does ' +
                'not, the words saying why in at most 2048 characters.\n' +
                'A field holding many is an array of at most as many values as it says.\n' +
                'The fields as declared:\n' +
                DECLARED.join("\n") + "\n"
        !told.contains("say how sure")
    }

    /** Pinned whole: nothing stands in the instruction's place, and what follows it is a review's, word for word. */
    def "a model reviewing a production no instruction asked for is told of no instruction, and otherwise as any reviewer"() {
        when:
        def told = Envelope.toReviewUninstructed(GIVES)

        then:
        told == 'You are sent a JSON object. "takes" holds what was taken in producing the answer, a member for each ' +
                'field taken, null where nothing was given. "refused", where it is there, holds an answer given before and ' +
                'refused: "values" as they were given, and "words" saying why each refused value was refused.\n' +
                Envelope.toReview(GIVES).substring(SENT.length())
        !told.contains('"instruction"')
        !told.contains("what is to be done")
    }

    def "an envelope of no fields at all is refused by name"() {
        when:
        Envelope."$told"(null)

        then:
        def refused = thrown(NullPointerException)
        refused.message == "Envelope gives must not be null"

        where:
        told << ["toProduce", "toReview", "toReviewUninstructed"]
    }

    def "a stored payload written under the version this release holds may be sent again"() {
        when:
        Envelope.requireHeld(2)

        then:
        noExceptionThrown()
    }

    def "a stored payload written under any other version is refused, naming it, rather than sent again"() {
        when:
        Envelope.requireHeld(version)

        then:
        def refused = thrown(IllegalStateException)
        refused.message == "Envelope version " + version + " is not one this release holds"

        where:
        version << [1, 3, 0, -1, Integer.MAX_VALUE]
    }
}
