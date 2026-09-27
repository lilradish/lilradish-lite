package org.lilradish.lite.app.run

import static org.lilradish.lite.domain.run.fixture.Runs.GROUP
import static org.lilradish.lite.domain.run.fixture.Runs.RUN
import static org.lilradish.lite.domain.run.fixture.Runs.VERSION
import static org.lilradish.lite.domain.run.fixture.Runs.json
import static org.lilradish.lite.domain.run.fixture.Runs.key
import static org.lilradish.lite.domain.run.fixture.Runs.nested
import static org.lilradish.lite.domain.run.fixture.Runs.nestedMany
import static org.lilradish.lite.domain.run.fixture.Runs.questionTaking
import static org.lilradish.lite.domain.run.fixture.Runs.takes
import static org.lilradish.lite.domain.run.fixture.Runs.text
import static org.lilradish.lite.domain.run.fixture.Runs.unstarted

import org.lilradish.lite.domain.declaration.Declaration
import org.lilradish.lite.domain.declaration.DeclarationSide
import org.lilradish.lite.domain.declaration.Demand
import org.lilradish.lite.domain.declaration.Demands
import org.lilradish.lite.domain.declaration.Field
import org.lilradish.lite.domain.declaration.FieldName
import org.lilradish.lite.domain.declaration.FieldShape
import org.lilradish.lite.domain.declaration.HowMany
import org.lilradish.lite.domain.declaration.OfferedTerms
import org.lilradish.lite.domain.filling.FillField
import org.lilradish.lite.domain.referencelist.Term
import org.lilradish.lite.domain.referencelist.TermMeaning
import org.lilradish.lite.domain.registry.EntryVersionId
import org.lilradish.lite.domain.run.RunSnapshot
import org.lilradish.lite.domain.run.RunnableWorkflow
import org.lilradish.lite.domain.wire.JsonValue
import spock.lang.Specification
import tools.jackson.databind.json.JsonMapper

class WrittenValuesSpec extends Specification {

    static final JsonMapper JSON = JsonMapper.builder().build()

    static final List<FillField> FIELDS = FillField.of([text("claim"), nested("sender", [text("name"), text("since")])], [:])

    static final FillField ITEMS = FillField.of([nestedMany("items", [text("name")])], [:])[0]

    /** Written out rather than compared as trees, which match whatever order their members stand in. */
    def "one value is written as a person filling it sends it, fields in declared order, and none as null: #written"() {
        expect:
        JSON.writeValueAsString(WrittenValues.of(field, json(kept))) == written

        where:
        field     | kept                           || written
        FIELDS[1] | [since: "Monday", name: "Ada"] || '{"name":"Ada","since":"Monday"}'
        FIELDS[0] | "Lost bag"                     || '"Lost bag"'
        FIELDS[0] | null                           || 'null'
        ITEMS     | [[name: "Mug"]]                || '[{"name":"Mug"}]'
        ITEMS     | null                           || 'null'
    }

    def "one value kept in no shape of its field is refused as a store gone wrong"() {
        when:
        WrittenValues.of(field, json(kept))

        then:
        def refused = thrown(IllegalStateException)
        refused.message == "Filling was handed values kept in no shape of the fields they fill"

        where:
        field     | kept
        FIELDS[0] | ["Lost bag"]
        FIELDS[1] | [name: "Ada"]
        ITEMS     | [null]
    }

    /** The store keeps members in an order of its own, so the declared order is all that puts them back. */
    def "values as kept are written as a person filling them sends them, every field in declared order"() {
        given:
        def kept = json([sender: [since: "Monday", name: "Ada"], claim: "Lost bag"]) as JsonValue.JsonObject

        when:
        def written = WrittenValues.of(FIELDS, kept)

        then:
        written == JSON.readTree('{"claim": "Lost bag", "sender": {"name": "Ada", "since": "Monday"}}')
        written.propertyNames().toList() == ["claim", "sender"]
        written.get("sender").propertyNames().toList() == ["name", "since"]
    }

    def "values kept without a field the fields hold are refused as a store gone wrong"() {
        when:
        WrittenValues.of(FIELDS, json([claim: "Lost bag"]) as JsonValue.JsonObject)

        then:
        def refused = thrown(IllegalStateException)
        refused.message == "Filling was handed values kept in no shape of the fields they fill"
    }

    def "what a run takes is its version's takes, each term with the terms of the list it pins, and nothing its steps take or give"() {
        given:
        def list = new EntryVersionId(key(500))
        def otherList = new EntryVersionId(key(501))
        def terms = new OfferedTerms([new OfferedTerms.Offered(new Term("late"), new TermMeaning("It came late."))], null)
        def otherTerms = new OfferedTerms([new OfferedTerms.Offered(new Term("lost"), new TermMeaning("It never came."))],
                null)
        def kind = new Field(new FieldName("kind"), null, null, new FieldShape.Term(list), new HowMany.One(),
                new Demand.Given(false))
        def step = questionTaking(1, takes([text("note")]), [])
        def workflow = new RunnableWorkflow(
                new Declaration(DeclarationSide.TAKES, Demands.ofWorkflow(DeclarationSide.TAKES), [text("claim"), kind]),
                new Declaration(DeclarationSide.GIVES, Demands.ofWorkflow(DeclarationSide.GIVES), [text("outcome")]),
                [(list): terms, (otherList): otherTerms], [], [step])
        def snapshot = new RunSnapshot(RUN, RUN, GROUP, VERSION, false, null, json([:]) as JsonValue.JsonObject,
                workflow, [unstarted(step)])

        when:
        def taken = WrittenValues.takesOf(snapshot)

        then:
        taken*.name()*.value() == ["claim", "kind"]
        taken[1].terms() == terms
        taken[1].terms() != otherTerms
        taken[0].terms() == null
    }

    def "the field at names each one level further in is found, and none where none stands: #names"() {
        expect:
        WrittenValues.at(FIELDS, names.collect { new FieldName(it) }) == found

        where:
        names               || found
        ["claim"]           || FIELDS[0]
        ["sender"]          || FIELDS[1]
        ["sender", "since"] || FIELDS[1].fields()[1]
        ["since"]           || null
        ["sender", "claim"] || null
        ["claim", "name"]   || null
        []                  || null
    }
}
