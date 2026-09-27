package org.lilradish.lite.domain.codestep

import org.lilradish.lite.domain.declaration.FieldName
import spock.lang.Specification

class CodeErrorSpec extends Specification {

    static final List<FieldName> LINES_SKU = [new FieldName("lines"), new FieldName("sku")]

    static final CodeError.ReadBy CHECK_READS =
            new CodeError.StepReads(UUID.fromString("00000009-0000-4000-8000-000000000c02"))

    static final List<CodeErrorReason> ABOUT_NO_FIELD =
            [CodeErrorReason.GAVE_NOTHING, CodeErrorReason.FAILED_ON_THIS_SIDE, CodeErrorReason.SAID_NOTHING]

    static final List<CodeErrorReason> ABOUT_A_FIELD =
            CodeErrorReason.values().toList() - ABOUT_NO_FIELD - [CodeErrorReason.NOT_DECLARED]

    /** One character the store counts as one, and Java as two. */
    static final String PAIR = Character.toString(0x1F600)

    def "#reason is taken naming exactly the field it is about, and what reads it only where that is the reason"() {
        when:
        new CodeError.Fault(reason, path, null, reason == CodeErrorReason.GIVES_OTHERWISE ? CHECK_READS : null)

        then:
        noExceptionThrown()

        where:
        [reason, path] << ABOUT_A_FIELD.collect { [it, LINES_SKU] } + ABOUT_NO_FIELD.collect { [it, []] }
    }

    def "a member nothing declares is named, at the first level or within the field holding it"() {
        when:
        new CodeError.Fault(CodeErrorReason.NOT_DECLARED, path, "extra", null)

        then:
        noExceptionThrown()

        where:
        path << [[], LINES_SKU]
    }

    def "keeps the path it names as it was handed over, whatever is done to the list it came in afterwards"() {
        given:
        def handed = [new FieldName("receipt")]
        def fault = new CodeError.Fault(CodeErrorReason.TOO_LONG, handed, null, null)

        when:
        handed << new FieldName("note")

        then:
        fault.path() == [new FieldName("receipt")]
    }

    def "refuses a reason about a field that names none, and one about no field that names one"() {
        when:
        new CodeError.Fault(reason, path, null, null)

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == message

        where:
        reason                                | path      || message
        CodeErrorReason.TOO_LONG              | []        || "CodeError.Fault too_long names the field it is about"
        CodeErrorReason.TAKES_OTHERWISE       | []        || "CodeError.Fault takes_otherwise names the field it is about"
        CodeErrorReason.GIVES_A_LIST_NOT_HERE | []        || "CodeError.Fault gives_a_list_not_here names the field it is about"
        CodeErrorReason.GAVE_NOTHING          | LINES_SKU || "CodeError.Fault gave_nothing is about no field, and names none"
        CodeErrorReason.FAILED_ON_THIS_SIDE   | LINES_SKU || "CodeError.Fault failed_on_this_side is about no field, and names none"
        CodeErrorReason.SAID_NOTHING          | LINES_SKU || "CodeError.Fault said_nothing is about no field, and names none"
    }

    def "refuses a member named for any reason but that it is not declared, and none named where that is the reason"() {
        when:
        new CodeError.Fault(reason, LINES_SKU, member, null)

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "CodeError.Fault names a member exactly where one is not declared"

        where:
        reason                       | member
        CodeErrorReason.NOT_DECLARED | null
        CodeErrorReason.TOO_LONG     | "extra"
        CodeErrorReason.NOT_A_TERM   | ""
    }

    def "refuses what reads the field named for any reason but that it no longer matches, and none named where it is"() {
        when:
        new CodeError.Fault(reason, LINES_SKU, null, readBy)

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "CodeError.Fault names what reads the field exactly where what it gives back no longer matches"

        where:
        reason                                | readBy
        CodeErrorReason.GIVES_OTHERWISE       | null
        CodeErrorReason.TAKES_OTHERWISE       | CHECK_READS
        CodeErrorReason.GIVES_A_LIST_NOT_HERE | new CodeError.OutputReads([new FieldName("result")])
        CodeErrorReason.TOO_LONG              | CHECK_READS
    }

    def "refuses a fault missing its reason or its path"() {
        when:
        new CodeError.Fault(reason, path, null, null)

        then:
        def refused = thrown(NullPointerException)
        refused.message == message

        where:
        reason                   | path      || message
        null                     | LINES_SKU || "CodeError.Fault reason must not be null"
        CodeErrorReason.TOO_LONG | null      || "CodeError.Fault path must not be null"
    }

    /** Whatever the code wrote as a member's name is shown in one line, so what breaks or hides in one is taken out. */
    def "names a member cleaned for one line, and cut alone past 64 characters, saying so"() {
        expect:
        new CodeError.Fault(CodeErrorReason.NOT_DECLARED, [], written, null).member() == kept

        where:
        written                                              || kept
        "extra"                                              || "extra"
        ""                                                   || ""
        "bad" + Character.toString(7) + "bin"                || "badbin"
        "two" + Character.toString(10) + "lines"             || "twolines"
        "tab" + Character.toString(9) + "bed"                || "tabbed"
        "zero" + Character.toString(0x200B) + "width"        || "zerowidth"
        "turned" + Character.toString(0x202E) + "round"      || "turnedround"
        "half" + Character.toString(0xD800) + "pair"         || "halfpair"
        "n" * 64                                             || "n" * 64
        "n" * 65                                             || "n" * 64 + "…"
        "b" * 3000                                           || "b" * 64 + "…"
        PAIR * 65                                            || PAIR * 64 + "…"
        "n" * 64 + Character.toString(7)                     || "n" * 64
        Character.toString(7) * 3                            || ""
    }

    /** Read back from the store it is made again from what was kept, which must come out as it went in. */
    def "names a member already cleaned and cut exactly as it was kept"() {
        given:
        def kept = new CodeError.Fault(CodeErrorReason.NOT_DECLARED, [], written, null).member()

        when:
        def again = new CodeError.Fault(CodeErrorReason.NOT_DECLARED, [], kept, null)

        then:
        again.member() == kept

        where:
        written << ["n" * 65, "n" * 64, "n" * 63 + "…", PAIR * 70, "a" + Character.toString(7)]
    }

    def "what code said is refused where it is not given"() {
        when:
        new CodeError.Said(null)

        then:
        def refused = thrown(NullPointerException)
        refused.message == "CodeError.Said detail must not be null"
    }

    def "a step reading the field is refused where it is not named"() {
        when:
        new CodeError.StepReads(null)

        then:
        def refused = thrown(NullPointerException)
        refused.message == "CodeError.StepReads step must not be null"
    }

    def "an output reading the field is refused where it names no field"() {
        when:
        new CodeError.OutputReads(field)

        then:
        def refused = thrown(expected)
        refused.message == message

        where:
        field || expected                 | message
        null  || NullPointerException     | "CodeError.OutputReads field must not be null"
        []    || IllegalArgumentException | "CodeError.OutputReads names the field it is read into"
    }
}
