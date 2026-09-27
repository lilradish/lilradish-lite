package org.lilradish.lite.domain.declaration

import org.lilradish.lite.domain.referencelist.ListNote
import org.lilradish.lite.domain.referencelist.Term
import org.lilradish.lite.domain.referencelist.TermMeaning
import org.lilradish.lite.domain.registry.EntryVersionId
import spock.lang.Specification

class AskingSpec extends Specification {

    static final EntryVersionId LIST = new EntryVersionId(UUID.fromString("00000007-0000-4000-8000-000000000106"))

    static final Instruction INSTRUCTION = new Instruction("Say which category.")

    static final OfferedTerms OFFERED = new OfferedTerms(
            [new OfferedTerms.Offered(new Term("Billing"), new TermMeaning("A charge is what is disputed.")),
             new OfferedTerms.Offered(new Term("Delivery"), new TermMeaning("It came late or not at all."))],
            new ListNote("Choose what the customer asks to have put right."))

    static final Declaration TAKES = new Declaration(DeclarationSide.TAKES, Demands.ofQuestion(DeclarationSide.TAKES), [
            new Field(new FieldName("complaint"), new FieldLabel("The complaint"), new FieldHelp("As written."),
                    new FieldShape.Text(4000), new HowMany.One(), new Demand.Given(true)),
            new Field(new FieldName("received"), null, null, new FieldShape.Plain(FieldKind.MOMENT),
                    new HowMany.Many(3), new Demand.Given(false))])

    static final Declaration GIVES = new Declaration(DeclarationSide.GIVES, Demands.ofQuestion(DeclarationSide.GIVES), [
            new Field(new FieldName("category"), new FieldLabel("Category"), null, new FieldShape.Term(LIST),
                    new HowMany.One(), new Demand.Stands(true, FieldStanding.ABOVE_CONFIDENCE, 80)),
            new Field(new FieldName("details"), null, new FieldHelp("What it names."), new FieldShape.Nested([
                    new Field(new FieldName("product"), null, null, new FieldShape.Text(128), new HowMany.One(),
                            new Demand.Given(true)),
                    new Field(new FieldName("order_reference"), null, null, new FieldShape.Text(64),
                            new HowMany.Many(2), new Demand.Given(false))]),
                    new HowMany.One(), new Demand.Stands(false, FieldStanding.NEVER, null)),
            new Field(new FieldName("urgent"), null, null, new FieldShape.Plain(FieldKind.YES_NO), new HowMany.One(),
                    new Demand.Stands(true, FieldStanding.ALWAYS, null))])

    static Declaration unfinished(DeclarationSide side) {
        new Declaration(side, Demands.ofQuestion(side), [new Field(new FieldName("summary"), null, null,
                new FieldShape.Text(null), new HowMany.One(),
                side == DeclarationSide.TAKES
                        ? new Demand.Given(true)
                        : new Demand.Stands(true, FieldStanding.NEVER, null))])
    }

    def "a question is told as its instruction and both halves, field by field, each list's terms beside its field"() {
        when:
        def asking = Asking.of(INSTRUCTION, TAKES, GIVES, [(LIST): OFFERED])

        then:
        asking.instruction() == INSTRUCTION
        asking.takes() == [
                new AskedField(new FieldName("complaint"), FieldKind.TEXT, 4000, null, null, [], true, false),
                new AskedField(new FieldName("received"), FieldKind.MOMENT, null, 3, null, [], false, false)]
        asking.gives() == [
                new AskedField(new FieldName("category"), FieldKind.TERM, null, null, OFFERED, [], true, true),
                new AskedField(new FieldName("details"), FieldKind.FIELDS, null, null, null, [
                        new AskedField(new FieldName("product"), FieldKind.TEXT, 128, null, null, [], true, false),
                        new AskedField(new FieldName("order_reference"), FieldKind.TEXT, 64, 2, null, [], false,
                                false)],
                        false, false),
                new AskedField(new FieldName("urgent"), FieldKind.YES_NO, null, null, null, [], true, false)]
    }

    /** What a page shows as added is the half given back told exactly as it is told whole. */
    def "one half is told alone as it is told beside the other, needing only the lists it pins"() {
        expect:
        Asking.told(GIVES, [(LIST): OFFERED]) == Asking.of(INSTRUCTION, TAKES, GIVES, [(LIST): OFFERED]).gives()
        Asking.told(TAKES, [:]) == Asking.of(INSTRUCTION, TAKES, GIVES, [(LIST): OFFERED]).takes()
    }

    /** A question giving nothing back is refused at submitting, so there is nothing to tell of that half. */
    def "a half can be told where it holds, and a half given back only where it gives something"() {
        expect:
        Asking.tellable(half) == tellable

        where:
        half                                  || tellable
        TAKES                                 || true
        GIVES                                 || true
        new Declaration(DeclarationSide.TAKES, Demands.ofQuestion(DeclarationSide.TAKES), []) || true
        new Declaration(DeclarationSide.GIVES, Demands.ofQuestion(DeclarationSide.GIVES), []) || false
        unfinished(DeclarationSide.TAKES)     || false
        unfinished(DeclarationSide.GIVES)     || false
    }

    def "the halves are refused handed the wrong way round"() {
        when:
        Asking.of(INSTRUCTION, takes, gives, [(LIST): OFFERED])

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "Asking takes what is taken and what is given back, in that order"

        where:
        takes | gives
        GIVES | TAKES
        TAKES | TAKES
        GIVES | GIVES
    }

    def "a half that could not be told is never told, whichever half it is"() {
        when:
        Asking.of(INSTRUCTION, side == DeclarationSide.TAKES ? unfinished(side) : TAKES,
                side == DeclarationSide.GIVES ? unfinished(side) : GIVES, [(LIST): OFFERED])

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "Asking is worked out only from what submitting would take"

        where:
        side << DeclarationSide.values()
    }

    def "a list pinned whose terms were not handed over is refused rather than told as none"() {
        when:
        Asking.of(INSTRUCTION, TAKES, GIVES, [:])

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "Asking was handed no terms for list " + LIST.value()
    }

    def "what is told is held apart from the list it was built from"() {
        given:
        def takes = []

        when:
        def asking = new Asking(INSTRUCTION, takes, [])
        takes.add(new AskedField(new FieldName("late"), FieldKind.DATE, null, null, null, [], false, false))

        then:
        asking.takes() == []
    }

    def "what is told says what the question tells, and is refused without it"() {
        when:
        new Asking(null, [], [])

        then:
        def refused = thrown(NullPointerException)
        refused.message == "Asking instruction must not be null"
    }
}
