package com.lilradish.lite.domain.failure

import static org.libprunus.core.error.ErrorCategory.NOT_FOUND

import com.lilradish.lite.testutil.failure.EstateRefusals
import com.lilradish.lite.testutil.failure.LibraryRefusals
import com.lilradish.lite.testutil.failure.MembershipRefusals
import com.lilradish.lite.testutil.failure.RunRefusals
import com.lilradish.lite.testutil.failure.TransportRefusals
import com.lilradish.lite.testutil.failure.WritingRefusals
import spock.lang.Specification

class RefusalCodeSpec extends Specification {

    private static final List<List<List<Object>>> DOMAINS = [
        TransportRefusals.EXPECTED,
        EstateRefusals.EXPECTED,
        MembershipRefusals.EXPECTED,
        LibraryRefusals.EXPECTED,
        WritingRefusals.EXPECTED,
        RunRefusals.EXPECTED,
    ]

    private static final List<List<Object>> EXPECTED = DOMAINS.collectMany { it }

    /**
     * Locked as a set because nothing reads a refusal's ordinal. The lock is what a rename has to
     * get past: these codes are published to whatever consumes the API, and no test anywhere else
     * ties them to a vocabulary, so a rename is otherwise free.
     */
    def "the published codes are fixed, since a rename here is a breaking change and not a refactor"() {
        given:
        def published = EXPECTED*.first()
        def declared = RefusalCode.values()*.code()

        expect:
        DOMAINS.every { !it.isEmpty() }
        !published.isEmpty()
        published.toSet().size() == published.size()
        declared.size() == published.size()
        declared.toSet() == published.toSet()

        and: "and no constant carries a code that is anything other than its own name"
        RefusalCode.values().every { it.code() == it.name() }
    }

    /**
     * A client mints lowercase codes of its own for states that are not a server refusal at all —
     * a request it abandoned, a response that was not a problem document — and tells them apart
     * from a served code by exactly this. A published code in that lowercase space would be read
     * as one of those and drop off the screen, with nobody left to notice.
     */
    def "every published code is screaming snake, so none lands in the space a client mints in"() {
        expect:
        code ==~ /^[A-Z][A-Z0-9_]*$/

        where:
        code << RefusalCode.values()*.code()
    }

    /**
     * The categories are the rulings themselves, not a detail of them: every one of these that
     * answers with something other than a refusal was a deliberate call.
     */
    def "each refusal declares the category that decides its status"() {
        given:
        def published = EXPECTED*.first()
        def declared = RefusalCode.values()

        expect:
        DOMAINS.every { !it.isEmpty() }
        !published.isEmpty()
        published.toSet().size() == published.size()
        declared.size() == published.size()
        declared*.code().toSet() == published.toSet()
        declared.collectEntries { [(it.code()): it.category()] } ==
                EXPECTED.collectEntries { [(it.first()): it.last()] }
    }

    /**
     * The ordering rule the class Javadoc states, in the one form a test can hold it: a constant
     * that withholds a row's existence must not sit in a category that concedes it.
     */
    def "every NOT_IN_VIEW refusal answers NOT_FOUND, so no refusal confirms a row it is hiding"() {
        expect:
        RefusalCode.values().findAll { it.name().endsWith("_NOT_IN_VIEW") }*.category().toSet() == [NOT_FOUND].toSet()

        and: "and nothing else claims that category, which would hide a row no rule says to hide"
        RefusalCode.values().findAll { it.category() == NOT_FOUND }.every { it.name().endsWith("_NOT_IN_VIEW") }
    }
}
