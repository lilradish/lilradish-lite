package org.lilradish.lite.domain.registry

import spock.lang.Specification

class ContentPlaceSpec extends Specification {

    static final UUID FIELD = UUID.fromString("0000000b-0000-4000-8000-000000000101")

    static final UUID STEP = UUID.fromString("0000000c-0000-4000-8000-000000000101")

    static final UUID CASE = UUID.fromString("0000000d-0000-4000-8000-000000000101")

    static final UUID BINDING = UUID.fromString("0000000e-0000-4000-8000-000000000101")

    /** Named by its key alone: what its authors called it is read off the version by whoever shows it. */
    def "a part as a whole is a place, and so is one field in it, by its key"() {
        expect:
        new ContentPlace.Whole(ContentPart.GIVES).part() == ContentPart.GIVES
        new ContentPlace.AtField(ContentPart.TAKES, FIELD).part() == ContentPart.TAKES
        new ContentPlace.AtField(ContentPart.TAKES, FIELD).fieldId() == FIELD
        ContentPlace.AtField.recordComponents*.name == ["part", "fieldId"]
    }

    def "a drawing reference carries its name and is at the drawing"() {
        given:
        def place = new ContentPlace.AtDrawing("missing")

        expect:
        place.part() == ContentPart.DRAWING
        place.name() == "missing"
    }

    /** A step, a case of it and an input either leads to are in the steps, whatever else a version holds. */
    def "a step, a case and an input are places in the steps, each by its keys"() {
        expect:
        place.part() == ContentPart.STEPS
        place.class.recordComponents*.name == components

        where:
        place                                        || components
        new ContentPlace.AtStep(STEP)                || ["stepId"]
        new ContentPlace.AtStepFields(STEP, ["body"]) || ["stepId", "fields"]
        new ContentPlace.AtCase(STEP, CASE)          || ["stepId", "caseId"]
        new ContentPlace.AtCaseField(STEP, CASE, FIELD) || ["stepId", "caseId", "fieldId"]
        new ContentPlace.AtInput(STEP, null, FIELD)  || ["stepId", "caseId", "fieldId"]
        new ContentPlace.AtInput(STEP, CASE, FIELD)  || ["stepId", "caseId", "fieldId"]
    }

    def "a field's place names its part and its key"() {
        when:
        new ContentPlace.AtField(part, fieldId)

        then:
        def refused = thrown(NullPointerException)
        refused.message == expectedMessage

        where:
        part              | fieldId || expectedMessage
        null              | FIELD   || "ContentPlace.AtField part must not be null"
        ContentPart.TAKES | null    || "ContentPlace.AtField fieldId must not be null"
    }

    def "a part as a whole names its part"() {
        when:
        new ContentPlace.Whole(null)

        then:
        def refused = thrown(NullPointerException)
        refused.message == "ContentPlace.Whole part must not be null"
    }

    def "a step's place names the step"() {
        when:
        new ContentPlace.AtStep(null)

        then:
        def refused = thrown(NullPointerException)
        refused.message == "ContentPlace.AtStep stepId must not be null"
    }

    def "a step's place naming fields keeps them in the order named, whatever becomes of the list they were named in"() {
        given:
        def named = ["references", "other"]

        when:
        def place = new ContentPlace.AtStepFields(STEP, named)
        named.add("title")

        then:
        place.stepId() == STEP
        place.fields() == ["references", "other"]
    }

    def "a step's place naming fields names its step and its fields"() {
        when:
        new ContentPlace.AtStepFields(stepId, fields)

        then:
        def refused = thrown(NullPointerException)
        refused.message == expectedMessage

        where:
        stepId | fields   || expectedMessage
        null   | ["body"] || "ContentPlace.AtStepFields stepId must not be null"
        STEP   | null     || "ContentPlace.AtStepFields fields must not be null"
    }

    def "a step's place naming fields names at least one, each by a name that is not empty"() {
        when:
        new ContentPlace.AtStepFields(STEP, fields)

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "ContentPlace.AtStepFields names at least one field, none empty"

        where:
        fields << [[], [""], ["body", ""]]
    }

    def "a case's place names both it and its step"() {
        when:
        new ContentPlace.AtCase(stepId, caseId)

        then:
        def refused = thrown(NullPointerException)
        refused.message == expectedMessage

        where:
        stepId | caseId || expectedMessage
        null   | CASE   || "ContentPlace.AtCase stepId must not be null"
        STEP   | null   || "ContentPlace.AtCase caseId must not be null"
    }

    def "a case output field's place names its case, step and field"() {
        when:
        new ContentPlace.AtCaseField(stepId, caseId, fieldId)

        then:
        def refused = thrown(NullPointerException)
        refused.message == expectedMessage

        where:
        stepId | caseId | fieldId || expectedMessage
        null   | CASE   | FIELD   || "ContentPlace.AtCaseField stepId must not be null"
        STEP   | null   | FIELD   || "ContentPlace.AtCaseField caseId must not be null"
        STEP   | CASE   | null    || "ContentPlace.AtCaseField fieldId must not be null"
    }

    def "a binding's place names its part and its key"() {
        when:
        new ContentPlace.AtBinding(part, bindingId)

        then:
        def refused = thrown(NullPointerException)
        refused.message == expectedMessage

        where:
        part              | bindingId || expectedMessage
        null              | BINDING   || "ContentPlace.AtBinding part must not be null"
        ContentPart.STEPS | null      || "ContentPlace.AtBinding bindingId must not be null"
    }

    /** A case is the one thing an input's place may leave unnamed, which then is the step's own. */
    def "an input's place names its step and its field"() {
        when:
        new ContentPlace.AtInput(stepId, CASE, fieldId)

        then:
        def refused = thrown(NullPointerException)
        refused.message == expectedMessage

        where:
        stepId | fieldId || expectedMessage
        null   | FIELD   || "ContentPlace.AtInput stepId must not be null"
        STEP   | null    || "ContentPlace.AtInput fieldId must not be null"
    }

    /** A term's place is always among the terms, so nothing sent can put it in another part. */
    def "one term is a place among the terms, by its key"() {
        given:
        def term = UUID.fromString("0000000a-0000-4000-8000-000000000101")

        when:
        def place = new ContentPlace.AtTerm(term)

        then:
        place.part() == ContentPart.TERMS
        place.termId() == term
        ContentPlace.AtTerm.recordComponents*.name == ["termId"]
    }

    def "a term's place names its key"() {
        when:
        new ContentPlace.AtTerm(null)

        then:
        def refused = thrown(NullPointerException)
        refused.message == "ContentPlace.AtTerm termId must not be null"
    }
}
