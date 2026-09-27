package org.lilradish.lite.app.identification.development

import jakarta.servlet.http.Cookie
import org.lilradish.lite.domain.identity.UserId
import org.springframework.mock.web.MockHttpServletRequest
import spock.lang.Specification

class DevelopmentUserIdentificationSpec extends Specification {

    /** Named by its code point rather than typed, for the reason the table below gives. */
    static final String A_CONTROL_CHARACTER = Character.toString(1)

    DevelopmentUserIdentification identification = new DevelopmentUserIdentification()

    private static MockHttpServletRequest carrying(Cookie... presented) {
        def request = new MockHttpServletRequest()
        request.setCookies(presented)
        request
    }

    def "a user a developer named for themselves is who this request is from"() {
        when:
        def identified = identification.identify(carrying(new Cookie("lilradish_user", "000001")))

        then:
        identified == Optional.of(new UserId("000001"))
    }

    /**
     * Read by name out of whatever else the browser is carrying, rather than off whichever cookie
     * happens to come first: a session cookie set by some other tool on the same host would
     * otherwise be read as the user, and its value would name somebody.
     */
    def "the user is read by name from among whatever else the request carries"() {
        when:
        def identified = identification.identify(carrying(
                new Cookie("theme", "dark"),
                new Cookie("lilradish_user", "000002"),
                new Cookie("locale", "en")))

        then:
        identified == Optional.of(new UserId("000002"))
    }

    /**
     * Every shape of "nothing said who this is" answers alike, which is why the outcome column is
     * one answer repeated rather than a column worth varying: what arrived, and whether anything
     * did, is a fact about the carrier, and the only thing above this that could tell two of these
     * apart is a caller free to change what is sent.
     *
     * <p>The control character is named above rather than typed into its row. Typed in, it is
     * invisible, and the row carrying it reads as an exact duplicate of the one before it — so a
     * reviewer tidying the table deletes a case nobody could see. The baseline forbids exactly this
     * of itself, and the rule does not stop at SQL.
     */
    def "#carrier names no user, and is answered as nobody"() {
        expect:
        identification.identify(presented) == identified

        where:
        carrier                          | presented                                                  || identified
        "no cookie at all"               | new MockHttpServletRequest()                               || Optional.empty()
        "only somebody else's cookie"    | carrying(new Cookie("theme", "dark"))                      || Optional.empty()
        "the cookie, set to nothing"     | carrying(new Cookie("lilradish_user", ""))             || Optional.empty()
        "the cookie, set to space alone" | carrying(new Cookie("lilradish_user", "   "))          || Optional.empty()
        "a value that opens with space"  | carrying(new Cookie("lilradish_user", " 000001"))      || Optional.empty()
        "a value hiding a control"       | carrying(new Cookie("lilradish_user", hidingAControl())) || Optional.empty()
    }

    /** The value the row above is about: a user number with U+0001 buried in the middle of it. */
    private static String hidingAControl() {
        "0000" + A_CONTROL_CHARACTER + "01"
    }

    /**
     * The direction that cannot be left to a reviewer's eye. A value the user type refuses is
     * not a bad request, because the reader typed nothing: it is nobody, and it has to reach the
     * caller as nobody rather than as an exception escaping into whatever answers next.
     */
    def "a value no user could be named by is answered as nobody rather than thrown"() {
        given:
        def request = carrying(new Cookie("lilradish_user", "x".repeat(257)))

        when:
        def identified = identification.identify(request)

        then:
        noExceptionThrown()
        identified.isEmpty()
    }
}
