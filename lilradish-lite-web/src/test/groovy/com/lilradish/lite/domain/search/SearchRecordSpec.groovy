package com.lilradish.lite.domain.search

import com.lilradish.lite.domain.inference.KeptAnswer
import spock.lang.Specification

class SearchRecordSpec extends Specification {

    // Named by code point, not typed: typed in, most are invisible and a row reads as a duplicate of the last.
    static final String NUL = Character.toString(0x0)
    static final String HIGH = Character.toString(0xD83D)
    static final String LOW = Character.toString(0xDE00)
    static final String GRINNING = Character.toString(0x1F600)
    static final String REPLACED = Character.toString(0xFFFD)
    static final String RIGHT_TO_LEFT_OVERRIDE = Character.toString(0x202E)
    static final String TAG_LATIN_A = Character.toString(0xE0041)
    static final String ZERO_WIDTH_SPACE = Character.toString(0x200B)

    static final int MOST = KeptAnswer.MOST_KEPT

    def "a record keeping neither what was searched for nor what was found is refused"() {
        when:
        new SearchRecord(null, null)

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message.contains("searchedFor, found, or both")
    }

    def "nothing said keeps nothing, neither cut nor altered nor leaving anything out"() {
        expect:
        SearchRecord.kept([]) == new SearchRecord.CallSearchRecord([], false, false, 0)
    }

    def "what the store can hold is kept as said, unsaid kept absent and empty kept empty, the whole neither cut nor altered"() {
        when:
        def kept = SearchRecord.kept([new SearchSaid(text, other)])

        then:
        kept == new SearchRecord.CallSearchRecord([new SearchRecord(text, other)], false, false, 0)
        kept.searches().first().searchedFor().is(text)

        where:
        text                                                          | other
        "weather in Rome"                                             | "Sunny."
        ""                                                            | null
        null                                                          | ""
        ""                                                            | ""
        "\t\r\n" + Character.toString(0x1) + Character.toString(0x85) | REPLACED
        "a" + RIGHT_TO_LEFT_OVERRIDE + "b"                            | TAG_LATIN_A + ZERO_WIDTH_SPACE
        GRINNING                                                      | " "
    }

    def "a search saying neither what it searched for nor what it found is dropped wherever it stands, and counted nowhere"() {
        when:
        def kept = SearchRecord.kept([new SearchSaid(null, null), new SearchSaid("a", null), new SearchSaid(null, null),
                                      new SearchSaid(null, "b"), new SearchSaid(null, null)])

        then:
        kept == new SearchRecord.CallSearchRecord([new SearchRecord("a", null), new SearchRecord(null, "b")], false, false, 0)
    }

    def "each null character and each half of a pair standing alone is replaced, the whole said not to be as it came, and the rest kept as said"() {
        when:
        def kept = SearchRecord.kept([new SearchSaid(said, "found"), new SearchSaid("again", said)])

        then:
        kept == new SearchRecord.CallSearchRecord(
                [new SearchRecord(expected, "found"), new SearchRecord("again", expected)], false, true, 0)

        where:
        said             || expected
        NUL              || REPLACED
        "a" + HIGH + "b" || "a" + REPLACED + "b"
        LOW + GRINNING   || REPLACED + GRINNING
        HIGH + HIGH + LOW || REPLACED + HIGH + LOW
    }

    /* Millions of characters are built inside the feature and compared there: as a row, or rendered by a failed
     * condition, each would be written into the name of the iteration or the report in full. */
    def "a search running to exactly what one call keeps is kept as said, the record cut only where a text after it says something, and a search after it with nothing kept left out"() {
        given:
        def said = piece * MOST

        when:
        def kept = SearchRecord.kept([new SearchSaid(said, null), new SearchSaid(null, null), next])
        boolean asSaid = kept.searches().first().searchedFor().is(said)

        then:
        asSaid
        kept.searches().first().found() == null
        kept.searches().drop(1) == after
        kept.cut() == cut
        !kept.notAsItCame()
        kept.leftOut() == leftOut

        where:
        piece    | next                       || after                        | cut   | leftOut
        "a"      | new SearchSaid("b", "c")   || []                           | true  | 1
        GRINNING | new SearchSaid("b", "c")   || []                           | true  | 1
        "a"      | new SearchSaid("", "z")    || [new SearchRecord("", null)] | true  | 0
        GRINNING | new SearchSaid(null, "z")  || []                           | true  | 1
        "a"      | new SearchSaid("", "")     || [new SearchRecord("", "")]   | false | 0
        GRINNING | new SearchSaid(null, null) || []                           | false | 0
    }

    def "a text crossing what one call keeps is cut where it crosses, a pair never split, what is beside it absent, a search after it left out, and one after it saying nothing not counted"() {
        given:
        def first = "a" * (MOST - room)

        when:
        def kept = SearchRecord.kept([new SearchSaid(first, null), new SearchSaid(crossing, "after"), new SearchSaid("x", "y"), new SearchSaid(null, null)])
        boolean firstAsSaid = kept.searches().first().searchedFor().is(first)

        then:
        firstAsSaid
        kept.searches().size() == 2
        kept.searches()[1] == new SearchRecord(cut, null)
        kept.cut()
        kept.notAsItCame() == replaced
        kept.leftOut() == 1

        where:
        room | crossing             || cut                     | replaced
        1    | "bc"                 || "b"                     | false
        1    | GRINNING * 2         || GRINNING                | false
        2    | "b" + GRINNING + "c" || "b" + GRINNING          | false
        3    | GRINNING + HIGH * 3  || GRINNING + REPLACED * 2 | true
        1    | "b" + NUL            || "b"                     | false
    }

    def "a found text spending what one call keeps is kept as far as it fits, the record cut only where something said is not kept"() {
        given:
        def first = "a" * (MOST - 1)

        when:
        def kept = SearchRecord.kept([new SearchSaid(first, said)] + afterwards)
        boolean firstAsSaid = kept.searches().first().searchedFor().is(first)

        then:
        firstAsSaid
        kept.searches().size() == 1
        kept.searches().first().found() == found
        kept.cut() == cut
        !kept.notAsItCame()

        where:
        said | afterwards                 || found | cut
        "z"  | []                         || "z"   | false
        "z"  | [new SearchSaid("b", "c")] || "z"   | true
        "zy" | []                         || "z"   | true
    }

    def "a found text beside a searched-for text that spent what one call keeps is absent and the record cut, kept empty only where said empty, unsaid as said"() {
        given:
        def first = "a" * MOST

        when:
        def kept = SearchRecord.kept([new SearchSaid(first, said)])

        then:
        kept.searches().size() == 1
        kept.searches().first().found() == found
        kept.cut() == cut
        !kept.notAsItCame()

        where:
        said || found | cut
        "z"  || null  | true
        ""   || ""    | false
        null || null  | false
    }

    def "a found text said empty beside a searched-for text cut short is absent, and the record stays cut"() {
        given:
        def first = "a" * (MOST - 1)

        when:
        def kept = SearchRecord.kept([new SearchSaid(first, null), new SearchSaid("bc", "")])

        then:
        kept.searches().size() == 2
        kept.searches()[1] == new SearchRecord("b", null)
        kept.cut()
        !kept.notAsItCame()
        kept.leftOut() == 0
    }

    def "a single text longer than what one call keeps is kept as far as that, as it came, and the record cut"() {
        given:
        def said = piece * (MOST + 1)

        when:
        def kept = SearchRecord.kept([new SearchSaid(said, null)])
        boolean keptAsFarAsFits = kept.searches().first().searchedFor() == piece * MOST

        then:
        keptAsFarAsFits
        kept.searches().size() == 1
        kept.searches().first().found() == null
        kept.cut()
        !kept.notAsItCame()
        kept.leftOut() == 0

        where:
        piece << ["a", GRINNING]
    }

    def "no more than the most searches are kept, the first in the order said, the rest that say anything left out and counted, those saying nothing not"() {
        given:
        def said = (1..SearchRecord.MOST_SEARCHES + extra).collectMany {
            def numbered = new SearchSaid(String.valueOf(it), null)
            withUnsaid ? [new SearchSaid(null, null), numbered] : [numbered]
        }

        when:
        def kept = SearchRecord.kept(said)

        then:
        kept.searches()*.searchedFor() == (1..Math.min(SearchRecord.MOST_SEARCHES + extra, SearchRecord.MOST_SEARCHES))*.toString()
        kept.leftOut() == Math.max(extra, 0)
        !kept.cut()
        !kept.notAsItCame()

        where:
        [extra, withUnsaid] << [[-1, 0, 1, 5], [true, false]].combinations()
    }

    def "each search saying something of which nothing is kept is left out and counted, past the most searches or past a cut, the search cut part-way counted only as cut"() {
        given:
        def last = "a" * (MOST - (count - 1) + over)
        def said = (1..<count).collect { new SearchSaid("s", null) } + [new SearchSaid(last, null)] +
                (1..after).collect { new SearchSaid("t", "u") }

        when:
        def kept = SearchRecord.kept(said)
        boolean lastKeptAsFarAsFits = kept.searches().last().searchedFor().length() == MOST - (count - 1)

        then:
        kept.searches().size() == count
        lastKeptAsFarAsFits
        kept.cut() == cut
        !kept.notAsItCame()
        kept.leftOut() == leftOut

        where:
        count                      | over | after || cut   | leftOut
        SearchRecord.MOST_SEARCHES | 0    | 5     || false | 5
        SearchRecord.MOST_SEARCHES | 1    | 5     || true  | 5
        3                          | 1    | 2000  || true  | 2000
    }

    def "a call's record leaving searches out neither past a cut nor past the most kept, or a negative count of them, is refused"() {
        given:
        def searches = (0..<count).collect { new SearchRecord("s", null) }

        when:
        new SearchRecord.CallSearchRecord(searches, cut, false, leftOut)

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message.contains("leaves out only past a cut or the most searches kept")

        where:
        count                          | cut   | leftOut
        SearchRecord.MOST_SEARCHES - 1 | false | 1
        0                              | false | 1
        SearchRecord.MOST_SEARCHES     | false | -1
        0                              | true  | -1
    }

    def "a record renders that it is one and drops each text"() {
        when:
        def record = new SearchRecord(searchedFor, found)
        def rendered = record.toString()

        then:
        rendered == "SearchRecord()"
        record.searchedFor() == searchedFor
        record.found() == found

        where:
        searchedFor       | found
        "weather in Rome" | "Sunny."
        null              | "Sunny."
        "weather in Rome" | null
    }

    def "a call's record renders whether it is cut, not as it came and how many were left out, each search without its texts"() {
        when:
        def record = new SearchRecord.CallSearchRecord([new SearchRecord("weather in Rome", "Sunny.")], cut, notAsItCame, leftOut)
        def rendered = record.toString()

        then:
        rendered == "SearchRecord\$CallSearchRecord(searches=[SearchRecord()], cut=${cut}, notAsItCame=${notAsItCame}, leftOut=${leftOut})" as String
        record.searches().first().searchedFor() == "weather in Rome"
        record.searches().first().found() == "Sunny."

        where:
        cut   | notAsItCame | leftOut
        true  | false       | 0
        false | true        | 0
        true  | true        | 2
    }
}
