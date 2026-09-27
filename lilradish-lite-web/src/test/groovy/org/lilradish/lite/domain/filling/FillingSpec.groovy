package org.lilradish.lite.domain.filling

import org.lilradish.lite.domain.declaration.FieldKind
import org.lilradish.lite.domain.declaration.FieldName
import org.lilradish.lite.domain.declaration.OfferedTerms
import org.lilradish.lite.domain.referencelist.Term
import org.lilradish.lite.domain.referencelist.TermMeaning
import org.lilradish.lite.domain.wire.JsonValue
import org.lilradish.lite.testutil.inference.Json
import spock.lang.Specification

class FillingSpec extends Specification {

    static final String GRINNING = Character.toString(0x1F600)

    static final String NUL = Character.toString(0)

    static final String CR = Character.toString(0xD)

    static final String HIGH_HALF = Character.toString(0xD83D)

    static final String LOW_HALF = Character.toString(0xDE00)

    static final String RIGHT_TO_LEFT_OVERRIDE = Character.toString(0x202E)

    static final String FIRST_STRONG_ISOLATE = Character.toString(0x2068)

    static final String TAG_A = Character.toString(0xE0041)

    static final String LINE_SEPARATOR = Character.toString(0x2028)

    static final String RIGHT_TO_LEFT_MARK = Character.toString(0x200F)

    static final String LEFT_TO_RIGHT_MARK = Character.toString(0x200E)

    static final String ARABIC_LETTER_MARK = Character.toString(0x061C)

    static final String ZERO_WIDTH_JOINER = Character.toString(0x200D)

    static final String NO_BREAK_SPACE = Character.toString(0xA0)

    static final String IDEOGRAPHIC_SPACE = Character.toString(0x3000)

    static final OfferedTerms CATEGORIES = new OfferedTerms(
            [new OfferedTerms.Offered(new Term("Billing"), new TermMeaning("A charge is what is disputed.")),
             new OfferedTerms.Offered(new Term("Delivery"), new TermMeaning("It came late or not at all."))], null)

    /**
     * Every kind once and many times over, text both required and not, fields that may be left empty holding a
     * required field, fields that must be given holding only what may be left empty, and many fields.
     */
    static final List<FillField> FIELDS = [
            field("complaint", FieldKind.TEXT, true, 10),
            field("amount", FieldKind.NUMBER),
            field("due", FieldKind.DATE),
            field("at", FieldKind.MOMENT),
            field("urgent", FieldKind.YES_NO),
            new FillField(new FieldName("category"), null, null, FieldKind.TERM, null, null, false, CATEGORIES, []),
            new FillField(new FieldName("tags"), null, null, FieldKind.TEXT, 6, 2, true, null, []),
            new FillField(new FieldName("contact"), null, null, FieldKind.FIELDS, null, null, false, null, [
                    field("email", FieldKind.TEXT, true, 20),
                    new FillField(new FieldName("phones"), null, null, FieldKind.TEXT, 12, 2, false, null, [])]),
            new FillField(new FieldName("address"), null, null, FieldKind.FIELDS, null, null, true, null, [
                    field("street", FieldKind.TEXT, false, 20),
                    new FillField(new FieldName("site"), null, null, FieldKind.FIELDS, null, null, false, null, [
                            field("floor", FieldKind.NUMBER),
                            new FillField(new FieldName("doors"), null, null, FieldKind.TEXT, 5, 2, false, null, [])])]),
            new FillField(new FieldName("amounts"), null, null, FieldKind.NUMBER, null, 2, false, null, []),
            new FillField(new FieldName("flags"), null, null, FieldKind.YES_NO, null, 2, false, null, []),
            new FillField(new FieldName("categories"), null, null, FieldKind.TERM, null, 2, false, CATEGORIES, []),
            new FillField(new FieldName("items"), null, null, FieldKind.FIELDS, null, 2, false, null, [
                    field("name", FieldKind.TEXT, true, 10),
                    field("count", FieldKind.NUMBER)]),
            field("note", FieldKind.TEXT, false, 10)]

    /** As little as fits: what must be given, and nothing else. */
    static final Map<String, Object> LEAST = [complaint : "Broken", amount: null, due: null, at: null, urgent: null,
                                              category  : null, tags: ["kettle"], contact: null,
                                              address   : [street: "1 Main", site: null], amounts: [], flags: [],
                                              categories: [], items: [], note: null]

    static FillField field(String name, FieldKind kind, boolean mustBeGiven = false, Integer longest = null) {
        new FillField(new FieldName(name), null, null, kind, longest, null, mustBeGiven, null, [])
    }

    static FillField manyNumbers(String name, int most) {
        new FillField(new FieldName(name), null, null, FieldKind.NUMBER, null, most, false, null, [])
    }

    static FillField named(String name) {
        FIELDS.find { it.name().value() == name }
    }

    static FillPath path(Object... steps) {
        new FillPath(steps.collect { it instanceof Integer ? new FillPath.Place(it) : new FillPath.Named(new FieldName(it as String)) })
    }

    static FillOutcome filled(Map<String, Object> changed) {
        Filling.of(FIELDS, Json.of(LEAST + changed))
    }

    static FillOutcome kept(Map<String, Object> changed) {
        new FilledFields(Json.of(LEAST + changed) as JsonValue.JsonObject)
    }

    static FillOutcome problem(FillPath path, FillReason reason) {
        new FillProblems([new FillProblem(path, reason)], 1)
    }

    def "values that all fit are kept as their kinds are, every field in declared order whatever order they came in"() {
        given:
        def sent = [note      : " Late\tno\n", items: [[count: "3", name: "Mug"], [name: "Lid", count: null]],
                    categories: ["Delivery", "Billing"], flags: ["false", "true"], amounts: ["1.0", "-2"],
                    address   : [site: [doors: ["3A"], floor: "2"], street: "1 Main"],
                    contact   : [phones: ["+44 20 7946", "0"], email: "a@example.org"],
                    tags      : ["kettle", "lid"], category: "Billing", urgent: "true", at: "2026-09-26T10:00:00.5+08:00",
                    due       : "2026-09-26", amount: "-1.50", complaint: "Broken"]

        when:
        def outcome = Filling.of(FIELDS, Json.of(sent))

        then:
        outcome == new FilledFields(Json.of([
                complaint : "Broken", amount: new BigDecimal("-1.50"), due: "2026-09-26",
                at        : "2026-09-26T10:00:00.5+08:00", urgent: true, category: "Billing", tags: ["kettle", "lid"],
                contact   : [email: "a@example.org", phones: ["+44 20 7946", "0"]],
                address   : [street: "1 Main", site: [floor: new BigDecimal("2"), doors: ["3A"]]],
                amounts   : [new BigDecimal("1.0"), new BigDecimal("-2")], flags: [false, true],
                categories: ["Delivery", "Billing"],
                items     : [[name: "Mug", count: new BigDecimal("3")], [name: "Lid", count: null]],
                note      : " Late\tno\n"]) as JsonValue.JsonObject)
    }

    def "a field that may be left empty is kept as no value whether it came as null, as empty text or as spacing alone"() {
        when:
        def outcome = filled([(name): given])

        then:
        outcome == kept([(name): null])

        where:
        [name, given] << [["amount", "due", "at", "urgent", "category", "note"],
                          [null, "", " ", "\t\n", NO_BREAK_SPACE + IDEOGRAPHIC_SPACE]].combinations()
    }

    def "a value of a kind other than text that must be given is missing whether it came as null, as empty text or as spacing alone"() {
        expect:
        Filling.of([declared], Json.of([given: sent])) == problem(path("given"), FillReason.MISSING)

        where:
        [declared, sent] << [[field("given", FieldKind.NUMBER, true), field("given", FieldKind.DATE, true),
                              field("given", FieldKind.MOMENT, true), field("given", FieldKind.YES_NO, true),
                              new FillField(new FieldName("given"), null, null, FieldKind.TERM, null, null, true,
                                      CATEGORIES, [])],
                             [null, "", " ", "\t\n", NO_BREAK_SPACE + IDEOGRAPHIC_SPACE]].combinations()
    }

    def "spacing alone among many of a kind other than text is missing at its place"() {
        expect:
        filled(changed) == problem(missingAt, FillReason.MISSING)

        where:
        changed                             || missingAt
        [amounts: ["1", " "]]               || path("amounts", 1)
        [flags: ["\t"]]                     || path("flags", 0)
        [categories: ["Billing", " "]]      || path("categories", 1)
        [items: [[name: "Mug", count: "1"], [name: " ", count: " "]]] || path("items", 1)
    }

    /** Spacing is judged by what prose counts as showing nothing, the tab and line feed among it. */
    def "text that may be left empty and shows nothing is kept as no value, as empty text would be"() {
        expect:
        filled([note: blank]) == kept([:])

        where:
        blank << [" ", "\t\n", " " * 30, NO_BREAK_SPACE, IDEOGRAPHIC_SPACE + " ", RIGHT_TO_LEFT_MARK,
                  ZERO_WIDTH_JOINER + " "]
    }

    def "text that must be given and shows nothing is missing, wherever it is held"() {
        expect:
        filled(changed) == problem(missingAt, FillReason.MISSING)

        where:
        changed                                  || missingAt
        [complaint: " "]                         || path("complaint")
        [complaint: "\n\t" + NO_BREAK_SPACE]     || path("complaint")
        [tags: ["kettle", "  "]]                 || path("tags", 1)
        [contact: [email: "\t", phones: ["1"]]]  || path("contact", "email")
        [items: [[name: " ", count: "3"]]]       || path("items", 0, "name")
    }

    /** Refused, never dropped as blank: what text is refused for is said even where nothing else is there. */
    def "text showing nothing but holding what text is refused for is refused for that, not read as no value"() {
        expect:
        filled([(name): said]) == problem(path(name), reason)

        where:
        name        | said                   || reason
        "note"      | RIGHT_TO_LEFT_OVERRIDE || FillReason.DIRECTION_CONTROL
        "note"      | TAG_A + " "            || FillReason.TAG
        "note"      | "\r\n"                 || FillReason.CRLF
        "note"      | CR                     || FillReason.UNUSABLE
        "complaint" | " " + FIRST_STRONG_ISOLATE || FillReason.DIRECTION_CONTROL
    }

    /** Fields in which nothing is given are nothing given, however they were spelt and whatever they hold. */
    def "fields that may be left empty are kept as no value when nothing in them is given, and as what they hold once anything is"() {
        expect:
        filled(changed) == kept(keptChange)

        where:
        changed                                                           || keptChange
        [contact: null]                                                   || [contact: null]
        [contact: [email: null, phones: []]]                              || [contact: null]
        [contact: [email: "", phones: []]]                                || [contact: null]
        [contact: [email: "\t", phones: []]]                              || [contact: null]
        [contact: [email: "a@b.c", phones: []]]                           || [contact: [email: "a@b.c", phones: []]]
        [contact: [phones: ["1", "2"], email: "a@b"]]                     || [contact: [email: "a@b", phones: ["1", "2"]]]
        [address: [street: "1 Main", site: [floor: null, doors: []]]]     || [address: [street: "1 Main", site: null]]
        [address: [street: "1 Main", site: [floor: "", doors: []]]]       || [address: [street: "1 Main", site: null]]
        [address: [site: [doors: [], floor: "2"], street: null]]          || [address: [street: null, site: [floor: new BigDecimal("2"), doors: []]]]
        [address: [street: null, site: [floor: null, doors: ["3A"]]]]     || [address: [street: null, site: [floor: null, doors: ["3A"]]]]
    }

    def "what must be given and is not is missing, in the one place it is missing from"() {
        expect:
        filled(changed) == problem(missingAt, FillReason.MISSING)

        where:
        changed                                                     || missingAt
        [complaint: null]                                           || path("complaint")
        [complaint: ""]                                             || path("complaint")
        [tags: []]                                                  || path("tags")
        [tags: ["kettle", ""]]                                      || path("tags", 1)
        [contact: [email: null, phones: ["1"]]]                     || path("contact", "email")
        [contact: [email: "", phones: ["1"]]]                       || path("contact", "email")
        [contact: [email: "a@b", phones: [""]]]                     || path("contact", "phones", 0)
        [address: null]                                             || path("address")
        [address: [street: null, site: null]]                       || path("address")
        [address: [street: "", site: [floor: null, doors: []]]]     || path("address")
        [address: [street: " ", site: [floor: "", doors: []]]]      || path("address")
        [items: [[name: null, count: "3"]]]                         || path("items", 0, "name")
        [items: [[name: null, count: null]]]                        || path("items", 0)
        [items: [[name: "Mug", count: "1"], [name: "", count: ""]]] || path("items", 1)
        [amounts: ["1", ""]]                                        || path("amounts", 1)
        [flags: [""]]                                               || path("flags", 0)
        [categories: ["Billing", ""]]                               || path("categories", 1)
    }

    /** Only what is missing goes with fields holding nothing; anything else that does not fit is named. */
    def "fields holding nothing kept because something in them does not fit name that, and are not missing"() {
        expect:
        filled(changed) == problem(at, reason)

        where:
        changed                                                       || at                                | reason
        [address: [street: RIGHT_TO_LEFT_OVERRIDE, site: null]]       || path("address", "street")         | FillReason.DIRECTION_CONTROL
        [address: [street: null, site: [floor: "x", doors: []]]]      || path("address", "site", "floor")  | FillReason.MALFORMED
        [contact: [email: "a@b" + TAG_A, phones: []]]                 || path("contact", "email")          | FillReason.TAG
    }

    def "a value written as its kind writes it is kept as that kind"() {
        expect:
        filled([(name): written]) == kept([(name): keptAs])

        where:
        name         | written                                || keptAs
        "amount"     | "0"                                    || BigDecimal.ZERO
        "amount"     | "0.0"                                  || new BigDecimal("0.0")
        "amount"     | "-12.5"                                || new BigDecimal("-12.5")
        "amount"     | "9" * 38                               || new BigDecimal("9" * 38)
        "amount"     | "-" + "9" * 37 + ".9"                  || new BigDecimal("-" + "9" * 37 + ".9")
        "amount"     | "0." + "1" * 37                        || new BigDecimal("0." + "1" * 37)
        "due"        | "0001-01-01"                           || "0001-01-01"
        "due"        | "9999-12-31"                           || "9999-12-31"
        "due"        | "2024-02-29"                           || "2024-02-29"
        "at"         | "2026-09-26T10:00:00+08:00"            || "2026-09-26T10:00:00+08:00"
        "at"         | "2026-09-26T23:59:59.123456-03:30"     || "2026-09-26T23:59:59.123456-03:30"
        "at"         | "2026-09-26T00:00:00+00:00"            || "2026-09-26T00:00:00+00:00"
        "at"         | "2026-09-26T00:00:00-14:00"            || "2026-09-26T00:00:00-14:00"
        "urgent"     | "true"                                 || true
        "urgent"     | "false"                                || false
        "amounts"    | ["0", "-12.50"]                        || [BigDecimal.ZERO, new BigDecimal("-12.50")]
        "flags"      | ["true", "false"]                      || [true, false]
        "categories" | ["Billing"]                            || ["Billing"]
    }

    def "a value not written as its kind writes it is malformed, and nothing is kept"() {
        expect:
        filled([(name): written]) == problem(path(name), FillReason.MALFORMED)

        where:
        name     | written
        "amount" | "-0"
        "amount" | "-0.0"
        "amount" | "-00"
        "amount" | "-0e0"
        "amount" | "-.0"
        "amount" | "-0.000"
        "amount" | "1e3"
        "amount" | "1E3"
        "amount" | "+1"
        "amount" | "01"
        "amount" | ".5"
        "amount" | "5."
        "amount" | " 1"
        "amount" | " 12 "
        "amount" | "\t12"
        "amount" | "1,5"
        "amount" | "1 234"
        "amount" | "Infinity"
        "amount" | "9" * 39
        "amount" | "0." + "1" * 38
        "amount" | "9" * 100_000
        "due"    | "0000-01-01"
        "due"    | "10000-01-01"
        "due"    | "2025-02-29"
        "due"    | "2026-9-1"
        "due"    | " 2026-09-26 "
        "due"    | "2026-09-26T10:00:00+08:00"
        "at"     | "2026-09-26T10:00:00Z"
        "at"     | "2026-09-26T10:00:00-00:00"
        "at"     | "2026-09-26T10:00:00+14:01"
        "at"     | "2026-09-26T10:00:00+15:00"
        "at"     | "2026-09-26T10:00+08:00"
        "at"     | "2026-09-26T24:00:00+08:00"
        "at"     | "2026-09-26T23:59:60+08:00"
        "at"     | "2026-09-26T10:00:00.1234567+08:00"
        "at"     | "2026-09-26"
        "urgent" | "True"
        "urgent" | "1"
        "urgent" | "yes"
        "urgent" | " true"
    }

    def "a value among many that does not fit is named at its place"() {
        expect:
        filled(changed) == problem(at, reason)

        where:
        changed                               || at                     | reason
        [amounts: ["1", "1e3"]]               || path("amounts", 1)     | FillReason.MALFORMED
        [flags: ["true", "yes"]]              || path("flags", 1)       | FillReason.MALFORMED
        [categories: ["Billing", "billing"]]  || path("categories", 1)  | FillReason.NOT_A_TERM
    }

    def "text as long as its field allows is kept, counted in characters rather than in halves of pairs"() {
        expect:
        filled([complaint: text]) == kept([complaint: text])

        where:
        text << ["x" * 10, GRINNING * 10, GRINNING + "x" * 9]
    }

    def "text longer than its field allows is too long, wherever the field is held"() {
        expect:
        filled(changed) == problem(longAt, FillReason.TOO_LONG)

        where:
        changed                                           || longAt
        [complaint: "x" * 11]                             || path("complaint")
        [complaint: GRINNING * 11]                        || path("complaint")
        [tags: ["kettle", "x" * 7]]                       || path("tags", 1)
        [contact: [email: "a@b", phones: ["1" * 13]]]     || path("contact", "phones", 0)
        [items: [[name: "x" * 11, count: null]]]          || path("items", 0, "name")
    }

    def "as many as a field holds are kept"() {
        expect:
        filled(changed) == kept(changed)

        where:
        changed << [[tags: ["a"]], [tags: ["a", "b"]], [items: [[name: "a", count: null], [name: "b", count: null]]]]
    }

    def "more than a field holds is too many, named once on the field and on no element, however each would read"() {
        expect:
        filled(changed) == problem(at, FillReason.TOO_MANY)

        where:
        changed                                                || at
        [tags: ["a", "b", "c"]]                                || path("tags")
        [tags: ["a", "b", "c", "d"]]                           || path("tags")
        [tags: ["x" * 7] * 10_000]                             || path("tags")
        [tags: [1, 2, 3]]                                      || path("tags")
        [amounts: ["1", "2", "3"]]                             || path("amounts")
        [items: [[name: "a", count: null]] * 3]                || path("items")
    }

    def "a term its list offers is kept word for word"() {
        expect:
        filled([category: said]) == kept([category: said])

        where:
        said << ["Billing", "Delivery"]
    }

    def "a term its list does not offer word for word is not a term"() {
        expect:
        filled([category: said]) == problem(path("category"), FillReason.NOT_A_TERM)

        where:
        said << ["billing", "Billing ", "Returns", "Billing" + NUL, HIGH_HALF]
    }

    /** What a person writes in another script is theirs: a mark only steers how it reads. */
    def "text holding a mark, a joiner, a tab, a line feed or another space is kept as it came"() {
        expect:
        filled([note: said]) == kept([note: said])

        where:
        said << ["a" + RIGHT_TO_LEFT_MARK + "b", LEFT_TO_RIGHT_MARK + "a", "a" + ARABIC_LETTER_MARK,
                 "a" + ZERO_WIDTH_JOINER + "b", "a\tb\nc", "a" + NO_BREAK_SPACE + "b", GRINNING]
    }

    def "text holding what prose is refused for is refused for the first of it, before how long it is"() {
        expect:
        filled([(name): said]) == problem(at, reason)

        where:
        name        | said                                || at                 | reason
        "note"      | "a\r\nb"                            || path("note")       | FillReason.CRLF
        "note"      | "a" + RIGHT_TO_LEFT_OVERRIDE + "b"  || path("note")       | FillReason.DIRECTION_CONTROL
        "note"      | FIRST_STRONG_ISOLATE + "a"          || path("note")       | FillReason.DIRECTION_CONTROL
        "note"      | "a" + TAG_A                         || path("note")       | FillReason.TAG
        "note"      | "a" + CR + "b"                      || path("note")       | FillReason.UNUSABLE
        "note"      | "a" + NUL + "b"                     || path("note")       | FillReason.UNUSABLE
        "note"      | "a" + Character.toString(1)         || path("note")       | FillReason.UNUSABLE
        "note"      | "a" + LINE_SEPARATOR                || path("note")       | FillReason.UNUSABLE
        "note"      | "a" + HIGH_HALF                     || path("note")       | FillReason.UNUSABLE
        "note"      | LOW_HALF + HIGH_HALF                || path("note")       | FillReason.UNUSABLE
        "complaint" | "x" * 20 + RIGHT_TO_LEFT_OVERRIDE   || path("complaint")  | FillReason.DIRECTION_CONTROL
        "complaint" | "\r\n" + TAG_A                      || path("complaint")  | FillReason.CRLF
        "tags"      | ["kettle", "a" + TAG_A]             || path("tags", 1)    | FillReason.TAG
    }

    /** No page's controls send any of these, so nothing is named, however much else does not fit. */
    def "values that are not the declaration's shape are no values at all, whatever else is wrong with them"() {
        expect:
        Filling.of(FIELDS, Json.of(sent)) == new FillOutcome.Unshaped()

        where:
        sent << [
                null,
                "Broken",
                [LEAST],
                LEAST.findAll { it.key != "due" },
                LEAST + [extra: null],
                LEAST + [amount: 12.5],
                LEAST + [urgent: true],
                LEAST + [complaint: ["Broken"]],
                LEAST + [complaint: [text: "Broken"]],
                LEAST + [tags: null],
                LEAST + [tags: "kettle"],
                LEAST + [tags: ["kettle", null]],
                LEAST + [tags: [["kettle"]]],
                LEAST + [contact: ""],
                LEAST + [contact: "a@b"],
                LEAST + [contact: [[email: "a@b", phones: []]]],
                LEAST + [contact: [email: "a@b"]],
                LEAST + [contact: [email: "a@b", phones: [], fax: null]],
                LEAST + [contact: [email: "a@b", phones: null]],
                LEAST + [address: ""],
                LEAST + [address: [street: "1 Main", site: [floor: null]]],
                LEAST + [amounts: [1]],
                LEAST + [flags: [true]],
                LEAST + [items: null],
                LEAST + [items: [null]],
                LEAST + [items: [[name: "a"]]],
                LEAST + [complaint: null, amount: "x", contact: [email: "a@b", phones: [1]]],
        ]
    }

    def "every value that does not fit is named, in declared order depth first, whatever order they came in"() {
        given:
        def sent = [note      : "a" + TAG_A, items: [[name: null, count: null], [count: "x", name: "Mug"]],
                    categories: ["Returns"], flags: ["yes"], amounts: ["1e3"],
                    address   : [site: [floor: null, doors: []], street: "x" * 21],
                    contact   : [phones: ["1" * 13, "2", "3"], email: null], tags: [],
                    category  : "Returns", urgent: "yes", at: "noon", due: "tomorrow", amount: "1e3", complaint: "x" * 11]

        when:
        def outcome = Filling.of(FIELDS, Json.of(sent))

        then:
        outcome == new FillProblems([
                new FillProblem(path("complaint"), FillReason.TOO_LONG),
                new FillProblem(path("amount"), FillReason.MALFORMED),
                new FillProblem(path("due"), FillReason.MALFORMED),
                new FillProblem(path("at"), FillReason.MALFORMED),
                new FillProblem(path("urgent"), FillReason.MALFORMED),
                new FillProblem(path("category"), FillReason.NOT_A_TERM),
                new FillProblem(path("tags"), FillReason.MISSING),
                new FillProblem(path("contact", "email"), FillReason.MISSING),
                new FillProblem(path("contact", "phones"), FillReason.TOO_MANY),
                new FillProblem(path("address", "street"), FillReason.TOO_LONG),
                new FillProblem(path("amounts", 0), FillReason.MALFORMED),
                new FillProblem(path("flags", 0), FillReason.MALFORMED),
                new FillProblem(path("categories", 0), FillReason.NOT_A_TERM),
                new FillProblem(path("items", 0), FillReason.MISSING),
                new FillProblem(path("items", 1, "count"), FillReason.MALFORMED),
                new FillProblem(path("note"), FillReason.TAG)], 16)
    }

    def "a field's first twenty problems are named and every one counted, however deep they stand: #found found"() {
        given:
        def fields = [manyNumbers("amounts", 30),
                      new FillField(new FieldName("items"), null, null, FieldKind.FIELDS, null, 30, false, null, [
                              manyNumbers("counts", 30)]),
                      field("after", FieldKind.NUMBER)]
        def sent = [amounts: ["x"] * found, items: [[counts: ["x"] * found]], after: "x"]

        when:
        def outcome = Filling.of(fields, Json.of(sent)) as FillProblems

        then:
        outcome.problems() == (0..<named).collect { new FillProblem(path("amounts", it), FillReason.MALFORMED) } +
                (0..<named).collect { new FillProblem(path("items", 0, "counts", it), FillReason.MALFORMED) } +
                [new FillProblem(path("after"), FillReason.MALFORMED)]
        outcome.found() == 2 * found + 1

        where:
        found || named
        19    || 19
        20    || 20
        21    || 20
        30    || 20
    }

    def "no more than two hundred are named across fields, and every one past them is counted"() {
        given:
        def fields = (0..<11).collect { manyNumbers("field" + it, 30) }
        def sent = (0..<11).collectEntries { ["field" + it, ["x"] * 21] }

        when:
        def outcome = Filling.of(fields, Json.of(sent)) as FillProblems

        then:
        outcome.problems() == (0..<10).collectMany { field ->
            (0..<20).collect { new FillProblem(path("field" + field, it), FillReason.MALFORMED) }
        }
        outcome.found() == 11 * 21
    }

    def "a field past its most is counted once past the most named, none of what it holds read"() {
        given:
        def fields = (0..<10).collect { manyNumbers("field" + it, 20) } + [manyNumbers("past", 2)]
        def sent = (0..<10).collectEntries { ["field" + it, ["x"] * 20] } + [past: ["x"] * 10_000]

        when:
        def outcome = Filling.of(fields, Json.of(sent)) as FillProblems

        then:
        outcome.problems().size() == 200
        outcome.problems().every { it.reason() == FillReason.MALFORMED }
        outcome.found() == 201
    }

    def "what is missing within fields holding nothing is not counted, whether or not it would have been named"() {
        given:
        def fields = [new FillField(new FieldName("items"), null, null, FieldKind.FIELDS, null, 30, false, null, [
                field("name", FieldKind.TEXT, true, 10), field("count", FieldKind.NUMBER)])]
        def sent = [items: [[name: "Mug", count: "x"]] * 21 + [[name: "", count: ""]]]

        when:
        def outcome = Filling.of(fields, Json.of(sent)) as FillProblems

        then:
        outcome.problems() == (0..<20).collect { new FillProblem(path("items", it, "count"), FillReason.MALFORMED) }
        outcome.found() == 22
    }

    def "a declaration taking nothing keeps an empty object, and takes nothing else"() {
        expect:
        Filling.of([], Json.of(sent)) == outcome

        where:
        sent          || outcome
        [:]           || new FilledFields(new JsonValue.JsonObject([]))
        [extra: null] || new FillOutcome.Unshaped()
        null          || new FillOutcome.Unshaped()
    }

    /** The store keeps members in an order of its own, so the declared order is all that puts them back. */
    def "values as kept are written as sent, in declared order at every level, each value as its text"() {
        given:
        def keptValues = Json.of([
                note      : "Late", items: [[count: null, name: "Mug"]], categories: ["Delivery"], flags: [true],
                amounts   : [new BigDecimal("1.0")], address: [site: [doors: ["3A"], floor: new BigDecimal("2")], street: "1 Main"],
                tags      : ["kettle"], contact: [phones: [], email: "a@b"], urgent: false,
                complaint : "Broken", amount: new BigDecimal("-1.50"), category: "Billing", due: "2026-09-26",
                at        : "2026-09-26T10:00:00+08:00"]) as JsonValue.JsonObject

        when:
        def written = Filling.wire(FIELDS, keptValues)

        then:
        written == Json.of([
                complaint : "Broken", amount: "-1.50", due: "2026-09-26", at: "2026-09-26T10:00:00+08:00",
                urgent    : "false", category: "Billing", tags: ["kettle"], contact: [email: "a@b", phones: []],
                address   : [street: "1 Main", site: [floor: "2", doors: ["3A"]]], amounts: ["1.0"], flags: ["true"],
                categories: ["Delivery"], items: [[name: "Mug", count: null]],
                note      : "Late"])
    }

    def "no value is written as none, and many holding none as none of them"() {
        when:
        def written = Filling.wire(FIELDS, Json.of(LEAST) as JsonValue.JsonObject)

        then:
        written == Json.of(LEAST)
    }

    def "values kept and written back as sent are read again into the very values kept"() {
        given:
        def keptValues = (filled(changed) as FilledFields).values()

        expect:
        Filling.of(FIELDS, Filling.wire(FIELDS, keptValues)) == new FilledFields(keptValues)

        where:
        changed << [[:],
                    [amount: "0." + "0" * 36 + "1", urgent: "true", at: "2026-09-26T10:00:00.000100-03:30"],
                    [amount: "100.000", urgent: "false", contact: [email: "a@b", phones: ["1", "2"]]],
                    [address: [street: null, site: [floor: "0.50", doors: ["1"]]], amounts: ["-3", "0.0"],
                     flags  : ["false"], items: [[name: "Mug", count: "-3"]]]]
    }

    /** What is kept was read by {@link Filling#of} against these very fields, so a mismatch is a store gone wrong. */
    def "values kept in no shape of the fields are refused as a store gone wrong"() {
        when:
        Filling.wire(FIELDS, Json.of(keptValues) as JsonValue.JsonObject)

        then:
        def refused = thrown(IllegalStateException)
        refused.message == "Filling was handed values kept in no shape of the fields they fill"

        where:
        keptValues << [
                LEAST.findAll { it.key != "due" },
                LEAST + [extra: null],
                LEAST + [amount: "12.5"],
                LEAST + [amount: new BigDecimal("1E+3")],
                LEAST + [urgent: "true"],
                LEAST + [complaint: 12],
                LEAST + [due: "tomorrow"],
                LEAST + [tags: "kettle"],
                LEAST + [tags: null],
                LEAST + [items: null],
                LEAST + [contact: [email: "a@b", phones: null]],
                LEAST + [tags: ["kettle", null]],
                LEAST + [complaint: ["Broken"]],
                LEAST + [contact: "a@b"],
                LEAST + [contact: [email: "a@b"]],
                LEAST + [amounts: [new BigDecimal("1E+3")]],
                LEAST + [flags: ["true"]],
                LEAST + [address: [street: "1 Main"]],
        ]
    }

    def "one value as kept is written as sent, and none where many are held as none, as a model may give back"() {
        expect:
        Filling.wire(named(name), Json.of(keptValue)) == Json.of(written)

        where:
        name      | keptValue                                                        || written
        "amount"  | new BigDecimal("-1.50")                                          || "-1.50"
        "amount"  | null                                                             || null
        "urgent"  | true                                                             || "true"
        "tags"    | ["kettle"]                                                       || ["kettle"]
        "tags"    | null                                                             || null
        "contact" | [phones: null, email: "a@b"]                                     || [email: "a@b", phones: null]
        "address" | [site: [doors: [], floor: new BigDecimal("2")], street: "1 Main"] || [street: "1 Main", site: [floor: "2", doors: []]]
        "items"   | [[count: new BigDecimal("3"), name: "Mug"]]                      || [[name: "Mug", count: "3"]]
    }

    def "one value kept in no shape of its field is refused as a store gone wrong"() {
        when:
        Filling.wire(named(name), Json.of(keptValue))

        then:
        def refused = thrown(IllegalStateException)
        refused.message == "Filling was handed values kept in no shape of the fields they fill"

        where:
        name      | keptValue
        "amount"  | new BigDecimal("1E+3")
        "amount"  | "12.5"
        "tags"    | "kettle"
        "address" | [street: "1 Main"]
    }
}
