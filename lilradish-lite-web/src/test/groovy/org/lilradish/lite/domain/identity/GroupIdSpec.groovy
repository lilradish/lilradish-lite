package org.lilradish.lite.domain.identity

import spock.lang.Specification

class GroupIdSpec extends Specification {

    def "keeps the identity it was constructed with, the group's name being nowhere in it"() {
        given:
        def value = UUID.fromString("5e1f0000-0000-4000-8000-000000000001")

        when:
        def identifier = new GroupId(value)

        then:
        identifier.value() == value

        and: "and two of them naming the same group are the same, which is what keys a holding by group"
        identifier == new GroupId(UUID.fromString("5e1f0000-0000-4000-8000-000000000001"))
        identifier != new GroupId(UUID.fromString("5e1f0000-0000-4000-8000-000000000002"))
    }

    def "refuses to stand for no group at all"() {
        when:
        new GroupId(null)

        then:
        def error = thrown(NullPointerException)
        error.message == "GroupId must not be null"
    }
}
