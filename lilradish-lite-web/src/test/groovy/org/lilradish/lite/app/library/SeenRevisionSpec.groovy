package org.lilradish.lite.app.library

import org.libprunus.core.error.ApiErrorException
import org.lilradish.lite.domain.failure.RefusalCode
import spock.lang.Specification
import tools.jackson.databind.json.JsonMapper

class SeenRevisionSpec extends Specification {

    static final JsonMapper JSON = JsonMapper.builder().build()

    static final String REFUSED = "This takes the revision read."

    def "the revision a body sends back is read as the whole number it is"() {
        expect:
        SeenRevision.in(JSON.readTree(body), REFUSED) == seen

        where:
        body                                     || seen
        '{"revision":1}'                         || 1
        '{"revision":7,"note":null}'             || 7
        '{"revision":2147483647}'                || Integer.MAX_VALUE
    }

    /** No reading of a draft ever gave any of these, so none reaches the store. */
    def "anything no revision could be is refused as the body is, under the sentence the address gave"() {
        when:
        SeenRevision.in(JSON.readTree(body), REFUSED)

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.BODY_UNUSABLE
        refused.message == REFUSED

        where:
        body << ['{}', '{"revision":0}', '{"revision":-1}', '{"revision":1.5}', '{"revision":1.0}', '{"revision":"1"}',
                 '{"revision":null}', '{"revision":true}', '{"revision":2147483648}', '{"revision":[1]}',
                 '{"Revision":1}', '[1]']
    }
}
