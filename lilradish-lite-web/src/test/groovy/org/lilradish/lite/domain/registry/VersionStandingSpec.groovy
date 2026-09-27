package org.lilradish.lite.domain.registry

import spock.lang.Specification

class VersionStandingSpec extends Specification {

    /** Pinned whole: a fifth standing would have to say what being it and stopped at once means. */
    def "the standings a version may be at are these four and no other"() {
        expect:
        VersionStanding.values().toList() ==
                [VersionStanding.DRAFT, VersionStanding.SUBMITTED, VersionStanding.IN_SERVICE, VersionStanding.RETIRED]
    }

    def "each standing is published under the spelling a reader names it by"() {
        expect:
        VersionStanding.values().collectEntries { [(it): it.published()] } == [
                (VersionStanding.DRAFT)     : "draft",
                (VersionStanding.SUBMITTED) : "submitted",
                (VersionStanding.IN_SERVICE): "in_service",
                (VersionStanding.RETIRED)   : "retired",
        ]
    }

    /** Approving leaves its submission open as the record of it, so approval outranks a submission. */
    def "a standing is read off which of the three marks a version holds"() {
        expect:
        VersionStanding.of(submitted, approved, retired) == standing

        where:
        submitted | approved | retired || standing
        false     | false    | false   || VersionStanding.DRAFT
        true      | false    | false   || VersionStanding.SUBMITTED
        false     | true     | false   || VersionStanding.IN_SERVICE
        true      | true     | false   || VersionStanding.IN_SERVICE
        false     | true     | true    || VersionStanding.RETIRED
        true      | true     | true    || VersionStanding.RETIRED
    }

    def "a version retired without ever being approved stands nowhere, and is refused rather than read"() {
        when:
        VersionStanding.of(submitted, false, true)

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "VersionStanding cannot be read off a version retired unapproved"

        where:
        submitted << [false, true]
    }
}
