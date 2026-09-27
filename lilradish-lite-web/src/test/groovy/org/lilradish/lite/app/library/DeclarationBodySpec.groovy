package org.lilradish.lite.app.library

import org.libprunus.core.error.ApiErrorException
import org.lilradish.lite.domain.declaration.Declaration
import org.lilradish.lite.domain.declaration.DeclarationSide
import org.lilradish.lite.domain.declaration.Demand
import org.lilradish.lite.domain.declaration.Demands
import org.lilradish.lite.domain.declaration.Field
import org.lilradish.lite.domain.declaration.FieldHelp
import org.lilradish.lite.domain.declaration.FieldKind
import org.lilradish.lite.domain.declaration.FieldLabel
import org.lilradish.lite.domain.declaration.FieldName
import org.lilradish.lite.domain.declaration.FieldShape
import org.lilradish.lite.domain.declaration.FieldStanding
import org.lilradish.lite.domain.declaration.HowMany
import org.lilradish.lite.domain.failure.RefusalCode
import org.lilradish.lite.domain.registry.EntryVersionId
import spock.lang.Specification
import tools.jackson.databind.json.JsonMapper

class DeclarationBodySpec extends Specification {

    static final JsonMapper JSON = JsonMapper.builder().build()

    static final String LIST = "00000007-0000-4000-8000-000000000106"

    /** A field of text taken, as a page sends one, with whatever {@code changed} says instead. */
    static Map taken(Map changed = [:]) {
        [name: "complaint", label: null, help: null, kind: "text", many: false, most: null, longest: 4000,
         mustBeGiven: true] + changed
    }

    /** A value of text given back that no field holds, as a page sends one, with whatever {@code changed} says. */
    static Map given(Map changed = [:]) {
        [name: "summary", label: null, help: null, kind: "text", many: false, most: null, longest: 1000,
         mustBeGiven: true, stands: "never", floor: null] + changed
    }

    /** A field of text held inside a value given back. */
    static Map held(Map changed = [:]) {
        [name: "product", label: null, help: null, kind: "text", many: false, most: null, longest: 128,
         mustBeGiven: false] + changed
    }

    /** A field as sent, but for the member {@code left} out, which a field of another kind takes and this does not. */
    static Map without(Map sent, String left) {
        sent.findAll { it.key != left }
    }

    static Declaration read(List sent, DeclarationSide side) {
        DeclarationBody.read(JSON.valueToTree(sent), side, Demands.ofQuestion(side)).half()
    }

    /** The key a field was read under goes beside the half, never into it, every field before those it holds. */
    def "the key each field was read under is read beside the half, none where it was added or names no key"() {
        given:
        def first = "0000000b-0000-4000-8000-000000000101"
        def inner = "0000000B-0000-4000-8000-000000000102"

        when:
        def sent = DeclarationBody.read(JSON.valueToTree([
                taken(fieldId: first),
                without(taken(name: "details", kind: "fields", fieldId: null, fields: [
                        taken(name: "product", fieldId: inner),
                        taken(name: "sku", fieldId: "not-a-key"),
                        taken(name: "added")]), "longest")]),
                DeclarationSide.TAKES, Demands.ofQuestion(DeclarationSide.TAKES))

        then:
        sent.readAs() == [UUID.fromString(first), null, UUID.fromString(inner), null, null]
        sent.half() == read([
                taken(),
                without(taken(name: "details", kind: "fields", fields: [
                        taken(name: "product"), taken(name: "sku"), taken(name: "added")]), "longest")],
                DeclarationSide.TAKES)
    }

    def "a half is read field by field in the order sent, each field of fields holding its own"() {
        when:
        def declared = read([
                taken(label: "The complaint", help: "As written."),
                without(taken(name: "received", kind: "moment", many: true, most: 3, mustBeGiven: false), "longest"),
                without(taken(name: "details", kind: "fields", fields: [taken(name: "product", longest: null)]), "longest")],
                DeclarationSide.TAKES)

        then:
        declared == new Declaration(DeclarationSide.TAKES, Demands.ofQuestion(DeclarationSide.TAKES), [
                new Field(new FieldName("complaint"), new FieldLabel("The complaint"), new FieldHelp("As written."),
                        new FieldShape.Text(4000), new HowMany.One(), new Demand.Given(true)),
                new Field(new FieldName("received"), null, null, new FieldShape.Plain(FieldKind.MOMENT),
                        new HowMany.Many(3), new Demand.Given(false)),
                new Field(new FieldName("details"), null, null, new FieldShape.Nested([
                        new Field(new FieldName("product"), null, null, new FieldShape.Text(null), new HowMany.One(),
                                new Demand.Given(true))]),
                        new HowMany.One(), new Demand.Given(true))])
    }

    /** What a draft has not chosen yet is sent as null, and read as nothing chosen rather than refused. */
    def "what is given back says how each value stands and whether it must be given, and a field it holds only the latter"() {
        when:
        def declared = read([
                without(given(name: "category", kind: "term", list: LIST, stands: "above_confidence", floor: 80), "longest"),
                without(given(name: "tags", kind: "term", list: null, many: true, most: null, stands: null,
                        mustBeGiven: false), "longest"),
                without(given(name: "details", kind: "fields", fields: [held()]), "longest")],
                DeclarationSide.GIVES)

        then:
        declared == new Declaration(DeclarationSide.GIVES, Demands.ofQuestion(DeclarationSide.GIVES), [
                new Field(new FieldName("category"), null, null,
                        new FieldShape.Term(new EntryVersionId(UUID.fromString(LIST))), new HowMany.One(),
                        new Demand.Stands(true, FieldStanding.ABOVE_CONFIDENCE, 80)),
                new Field(new FieldName("tags"), null, null, new FieldShape.Term(null), new HowMany.Many(null),
                        new Demand.Stands(false, null, null)),
                new Field(new FieldName("details"), null, null, new FieldShape.Nested([
                        new Field(new FieldName("product"), null, null, new FieldShape.Text(128), new HowMany.One(),
                                new Demand.Given(false))]),
                        new HowMany.One(), new Demand.Stands(true, FieldStanding.NEVER, null))])
    }

    /**
     * A member of another kind, half or depth, one missing, and a value of the wrong type: each refused whole
     * under the one code for a body this does not take.
     */
    def "a field holding anything but exactly the members its kind, half and depth take is refused whole"() {
        when:
        DeclarationBody.read(JSON.valueToTree(sent), side, Demands.ofQuestion(side))

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.BODY_UNUSABLE
        refused.message == DeclarationBody.REFUSED

        where:
        side                  | sent
        DeclarationSide.TAKES | [[complaint: "text"]]
        DeclarationSide.TAKES | ["complaint"]
        DeclarationSide.TAKES | [without(taken(), "kind")]
        DeclarationSide.TAKES | [taken(kind: "prose")]
        DeclarationSide.TAKES | [taken(kind: 3)]
        DeclarationSide.TAKES | [taken(stands: "never")]
        DeclarationSide.TAKES | [without(taken(), "mustBeGiven")]
        DeclarationSide.GIVES | [without(given(), "mustBeGiven")]
        DeclarationSide.GIVES | [without(given(kind: "fields", fields: [without(held(), "mustBeGiven")]), "longest")]
        DeclarationSide.GIVES | [without(given(kind: "fields", fields: [held(stands: "never")]), "longest")]
        DeclarationSide.GIVES | [without(given(kind: "fields", fields: [held(floor: null)]), "longest")]
        DeclarationSide.TAKES | [taken(kind: "number")]
        DeclarationSide.TAKES | [taken(list: null)]
        DeclarationSide.TAKES | [without(taken(kind: "term"), "longest")]
        DeclarationSide.TAKES | [without(taken(kind: "fields"), "longest")]
        DeclarationSide.TAKES | [without(taken(kind: "fields", fields: "product"), "longest")]
        DeclarationSide.TAKES | [taken(name: 3)]
        DeclarationSide.TAKES | [taken(fieldId: 3)]
        DeclarationSide.TAKES | [taken(fieldId: true)]
        DeclarationSide.TAKES | [taken(label: 3)]
        DeclarationSide.TAKES | [taken(help: true)]
        DeclarationSide.TAKES | [taken(many: "yes")]
        DeclarationSide.TAKES | [taken(many: false, most: 3)]
        DeclarationSide.TAKES | [taken(many: true, most: 1.5)]
        DeclarationSide.TAKES | [taken(many: true, most: "3")]
        DeclarationSide.TAKES | [taken(longest: 4000000000L)]
        DeclarationSide.TAKES | [taken(mustBeGiven: "true")]
        DeclarationSide.TAKES | [without(taken(kind: "term", list: 106), "longest")]
        DeclarationSide.GIVES | [given(stands: "sometimes")]
        DeclarationSide.GIVES | [given(stands: 1)]
        DeclarationSide.GIVES | [given(stands: "never", floor: 80)]
        DeclarationSide.GIVES | [given(stands: null, floor: 80)]
        DeclarationSide.GIVES | [given(stands: "above_confidence", floor: "80")]
    }

    def "a half that is no list of fields is refused as a body this does not take"() {
        when:
        DeclarationBody.read(JSON.readTree(sent), DeclarationSide.TAKES, Demands.ofQuestion(DeclarationSide.TAKES))

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.BODY_UNUSABLE

        where:
        sent << ['{"fields":[]}', '"complaint"', 'null']
    }

    /** Each value its type admits, refused under the code for its own rule, so a page can say which rule. */
    def "a value that is of its type but not one its rule admits is refused under that rule's code"() {
        when:
        read(sent, side)

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == code

        where:
        side                  | sent                                                        || code
        DeclarationSide.TAKES | [taken(name: "Complaint")]                                  || RefusalCode.FIELD_NAME_UNUSABLE
        DeclarationSide.TAKES | [taken(name: "")]                                           || RefusalCode.FIELD_NAME_UNUSABLE
        DeclarationSide.TAKES | [taken(name: "order-reference")]                            || RefusalCode.FIELD_NAME_UNUSABLE
        DeclarationSide.TAKES | [taken(label: "")]                                          || RefusalCode.FIELD_WORDS_UNUSABLE
        DeclarationSide.TAKES | [taken(label: "l" * 129)]                                   || RefusalCode.FIELD_WORDS_UNUSABLE
        DeclarationSide.TAKES | [taken(help: "Two\nlines")]                                 || RefusalCode.FIELD_WORDS_UNUSABLE
        DeclarationSide.TAKES | [taken(longest: 0)]                                         || RefusalCode.FIELD_LIMIT_UNUSABLE
        DeclarationSide.TAKES | [taken(many: true, most: 0)]                                || RefusalCode.FIELD_LIMIT_UNUSABLE
        DeclarationSide.GIVES | [given(stands: "above_confidence", floor: 0)]               || RefusalCode.FIELD_LIMIT_UNUSABLE
        DeclarationSide.GIVES | [given(stands: "above_confidence", floor: 101)]             || RefusalCode.FIELD_LIMIT_UNUSABLE
        DeclarationSide.TAKES | [without(taken(kind: "term", list: "not-a-version"), "longest")] || RefusalCode.VERSION_NOT_PINNABLE
    }

    /** Sixteen names of 63 joined by dots run to 1023, the most anything may be bound through. */
    def "a field nested deeper than any path could bind through is refused, and one at the bound read"() {
        given:
        def name = "a" * 63
        def deepest = (0..<15).inject(taken(name: name)) { holding, level ->
            without(taken(name: name, kind: "fields", fields: [holding]), "longest")
        }

        expect:
        read([deepest], DeclarationSide.TAKES).fields().size() == 1

        when:
        read([without(taken(name: "b", kind: "fields", fields: [deepest]), "longest")], DeclarationSide.TAKES)

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.DECLARATION_TOO_DEEP
    }

    /** Short names reach far deeper than the path bound lets long ones, and the depth bound answers first there. */
    def "a field held 33 deep is refused under the depth's own code, and one 32 deep read, however short its names"() {
        given:
        def nested = { int levels ->
            (1..<levels).inject(taken(name: "a")) { holding, level ->
                without(taken(name: "a", kind: "fields", fields: [holding]), "longest")
            }
        }

        expect:
        read([nested(32)], DeclarationSide.TAKES).fields().size() == 1

        when:
        read([nested(33)], DeclarationSide.TAKES)

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.DECLARATION_TOO_DEEP
    }

    /** Counted as it is read, so a body past the bound is refused before it is all turned into fields. */
    def "a half of 256 fields at every depth together is read, and one of 257 refused under its own code"() {
        given:
        def fields = { int count -> (1..count).collect { taken(name: "f" + it, longest: 1) } }

        expect:
        read([without(taken(name: "held", kind: "fields", fields: fields(255)), "longest")], DeclarationSide.TAKES)
                .fields().size() == 1

        when:
        read([without(taken(name: "held", kind: "fields", fields: fields(256)), "longest")], DeclarationSide.TAKES)

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.DECLARATION_TOO_LARGE
    }

    /** What a depth takes is its host's to say: a half given back asking no standing takes no standing member. */
    def "a field is read as the host's demands take it, and refused with a member they do not"() {
        given:
        def workflowGives = Demands.ofWorkflow(DeclarationSide.GIVES)

        expect:
        DeclarationBody.read(JSON.valueToTree([held()]), DeclarationSide.GIVES, workflowGives).half().fields()*.demand() ==
                [new Demand.Given(false)]

        when:
        DeclarationBody.read(JSON.valueToTree([given()]), DeclarationSide.GIVES, workflowGives)

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.BODY_UNUSABLE
    }
}
