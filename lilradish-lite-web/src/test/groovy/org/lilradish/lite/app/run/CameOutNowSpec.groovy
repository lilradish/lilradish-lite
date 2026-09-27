package org.lilradish.lite.app.run

import static org.lilradish.lite.app.run.CameOutNow.NONE
import static org.lilradish.lite.app.run.CameOutNow.REFUSED
import static org.lilradish.lite.app.run.CameOutNow.REFUSED_FOR_LENGTH
import static org.lilradish.lite.app.run.CameOutNow.STANDS
import static org.lilradish.lite.app.run.CameOutNow.WAITING_ON_REVIEW

import org.lilradish.lite.domain.run.ValueStanding
import spock.lang.Specification

class CameOutNowSpec extends Specification {

    def "where each field given back stands is published under the spelling a reader words it by, in this order and no other"() {
        expect:
        CameOutNow.values().collect { [it, it.published()] } == [
                [STANDS, "stands"],
                [WAITING_ON_REVIEW, "waiting_on_review"],
                [REFUSED, "refused"],
                [REFUSED_FOR_LENGTH, "refused_for_length"],
                [NONE, "none"],
        ]
    }

    def "a value that came out stands as its value does, spelt as that standing publishes itself, and never as none"() {
        when:
        def now = CameOutNow.of(standing)

        then:
        now == expected
        now.published() == standing.published()
        now != NONE

        where:
        standing                         || expected
        ValueStanding.STANDS             || STANDS
        ValueStanding.WAITING_ON_REVIEW  || WAITING_ON_REVIEW
        ValueStanding.REFUSED            || REFUSED
        ValueStanding.REFUSED_FOR_LENGTH || REFUSED_FOR_LENGTH
    }
}
