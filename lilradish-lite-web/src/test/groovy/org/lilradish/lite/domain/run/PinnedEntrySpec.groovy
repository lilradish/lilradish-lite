package org.lilradish.lite.domain.run

import static org.lilradish.lite.domain.run.fixture.Runs.key

import org.lilradish.lite.domain.registry.EntryId
import org.lilradish.lite.domain.registry.EntryName
import org.lilradish.lite.domain.registry.EntryVersionId
import spock.lang.Specification

class PinnedEntrySpec extends Specification {

    def "a pinned version is refused numbered below one"() {
        when:
        new PinnedEntry(new EntryId(key(101)), new EntryName("Demo Sample"), new EntryVersionId(key(201)), number)

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "PinnedEntry number must be positive: " + number

        where:
        number << [0, -1]
    }
}
