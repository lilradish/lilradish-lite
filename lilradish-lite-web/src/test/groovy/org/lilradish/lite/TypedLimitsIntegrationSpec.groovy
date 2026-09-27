package org.lilradish.lite

import java.nio.file.Files
import org.lilradish.lite.domain.declaration.FieldHelp
import org.lilradish.lite.domain.declaration.FieldLabel
import org.lilradish.lite.domain.declaration.FieldName
import org.lilradish.lite.domain.declaration.Instruction
import org.lilradish.lite.domain.identity.GroupKey
import org.lilradish.lite.domain.identity.GroupName
import org.lilradish.lite.domain.referencelist.ListNote
import org.lilradish.lite.domain.referencelist.Term
import org.lilradish.lite.domain.referencelist.TermMeaning
import org.lilradish.lite.domain.registry.EntryName
import org.lilradish.lite.domain.registry.EntryPurpose
import org.lilradish.lite.domain.run.Ceiling
import org.lilradish.lite.domain.run.RunName
import org.lilradish.lite.testutil.ReaderVocabulary
import spock.lang.Specification
import tools.jackson.databind.json.JsonMapper

/**
 * What may be typed is limited twice: where it is typed, and here, where it arrives. The two are written
 * in two languages and neither compiler sees the other, so both are run over one table of what is typed
 * and whether it is taken, and a case they decide differently fails on one side.
 *
 * <p>A group's name and an entry's are held to one rule, which the reader checks once, so both are run
 * over the one name table; what an entry is for is a line, and the line table is written at its bound.
 * The tables sit in the reader's tree as JSON, which the reader's tests import and this reads as the
 * build already hands that tree to these specs.
 */
class TypedLimitsIntegrationSpec extends Specification {

    static final JsonMapper JSON = JsonMapper.builder().build()

    static final Map<String, List<Map<String, Object>>> KEYS = casesIn("features/groups/typedLimits.cases.json")

    static final Map<String, List<Map<String, Object>>> TEXT = casesIn("lib/text/legibility.cases.json")

    static final Map<String, List<Map<String, Object>>> CEILINGS = casesIn("features/runs/ceilingTyped.cases.json")

    static final Map<String, List<Map<String, Object>>> RUN_NAMES = casesIn("features/runs/runName.cases.json")

    static Map<String, List<Map<String, Object>>> casesIn(String table) {
        JSON.readValue(Files.readString(ReaderVocabulary.FRONTEND.resolve(table)), Map)
    }

    static String typedIn(Map<String, Object> each) {
        (each.typed as String) * ((each.times ?: 1) as int)
    }

    static boolean taken(Closure judging) {
        try {
            judging()
            true
        } catch (IllegalArgumentException ignored) {
            false
        }
    }

    def "the tables hold cases for every limit, an empty table agreeing with anything"() {
        expect:
        !KEYS.key.isEmpty()
        !TEXT.name.isEmpty()
        !TEXT.line.isEmpty()
        !TEXT.label.isEmpty()
        !TEXT.prose.isEmpty()
        !TEXT.fieldName.isEmpty()
        !TEXT.term.isEmpty()
        !TEXT.meaning.isEmpty()
        !TEXT.note.isEmpty()
        !CEILINGS.ceiling.isEmpty()
        !RUN_NAMES.name.isEmpty()
    }

    def "a row of every table naming reasons names one exactly where it is not taken"() {
        expect:
        (each.refused == null) == each.accepted

        where:
        each << TEXT.prose + TEXT.term + TEXT.meaning + TEXT.note
    }

    def "a group's key is taken as typed exactly where the table says it is"() {
        expect:
        taken { GroupKey.typed(typedIn(each)) } == each.accepted

        where:
        each << KEYS.key
    }

    def "a group's name is taken exactly where the name table says it is"() {
        expect:
        taken { new GroupName(typedIn(each)) } == each.accepted

        where:
        each << TEXT.name
    }

    def "an entry's name is taken exactly where the name table says it is"() {
        expect:
        taken { new EntryName(typedIn(each)) } == each.accepted

        where:
        each << TEXT.name
    }

    def "what an entry is for is taken exactly where the line table says it is"() {
        expect:
        taken { new EntryPurpose(typedIn(each)) } == each.accepted

        where:
        each << TEXT.line
    }

    def "a field's help is taken exactly where the line table says it is"() {
        expect:
        taken { new FieldHelp(typedIn(each)) } == each.accepted

        where:
        each << TEXT.line
    }

    def "a field's label is taken exactly where the label table says it is"() {
        expect:
        taken { new FieldLabel(typedIn(each)) } == each.accepted

        where:
        each << TEXT.label
    }

    def "a question's instruction is taken exactly where the prose table says it is"() {
        expect:
        taken { new Instruction(typedIn(each)) } == each.accepted

        where:
        each << TEXT.prose
    }

    /** The reason is what the refusal code is chosen by, so the page names the one the server answers with. */
    def "a question's instruction is refused for exactly the reason the prose table names"() {
        expect:
        Instruction.refusalOf(typedIn(each))?.name()?.toLowerCase(Locale.ROOT) == each.refused

        where:
        each << TEXT.prose
    }

    def "a field's name is taken exactly where the field name table says it is"() {
        expect:
        taken { new FieldName(typedIn(each)) } == each.accepted

        where:
        each << TEXT.fieldName
    }

    def "a list's term is taken exactly where the term table says it is"() {
        expect:
        taken { new Term(typedIn(each)) } == each.accepted

        where:
        each << TEXT.term
    }

    def "a list's term is refused for exactly the reason the term table names"() {
        expect:
        Term.refusalOf(typedIn(each))?.name()?.toLowerCase(Locale.ROOT) == each.refused

        where:
        each << TEXT.term
    }

    def "a list holds at most the number of terms past which the reader stops offering to add one"() {
        given:
        def limits = JSON.readValue(
                Files.readString(ReaderVocabulary.FRONTEND.resolve("features/library/referenceListLimits.json")), Map)

        expect:
        limits == [mostTerms: Term.MOST_IN_A_LIST]
    }

    def "what a term means is taken exactly where the meaning table says it is"() {
        expect:
        taken { new TermMeaning(typedIn(each)) } == each.accepted

        where:
        each << TEXT.meaning
    }

    def "what a term means is refused for exactly the reason the meaning table names"() {
        expect:
        TermMeaning.refusalOf(typedIn(each))?.name()?.toLowerCase(Locale.ROOT) == each.refused

        where:
        each << TEXT.meaning
    }

    def "a list's note is taken exactly where the note table says it is"() {
        expect:
        taken { new ListNote(typedIn(each)) } == each.accepted

        where:
        each << TEXT.note
    }

    def "a list's note is refused for exactly the reason the note table names"() {
        expect:
        ListNote.refusalOf(typedIn(each))?.name()?.toLowerCase(Locale.ROOT) == each.refused

        where:
        each << TEXT.note
    }

    def "a run's ceiling is taken as typed exactly where the ceiling table says it is"() {
        expect:
        taken { Ceiling.typed(typedIn(each)) } == each.accepted

        where:
        each << CEILINGS.ceiling
    }

    def "a run's name is taken exactly where the run name table says it is"() {
        expect:
        taken { new RunName(typedIn(each)) } == each.accepted

        where:
        each << RUN_NAMES.name
    }
}
