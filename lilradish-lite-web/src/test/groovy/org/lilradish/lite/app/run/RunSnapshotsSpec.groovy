package org.lilradish.lite.app.run

import static org.lilradish.lite.domain.run.fixture.Runs.person
import static org.lilradish.lite.domain.run.fixture.Runs.pinned
import static org.lilradish.lite.domain.run.fixture.Runs.question
import static org.lilradish.lite.domain.run.fixture.Runs.searchStep

import spock.lang.Specification

class RunSnapshotsSpec extends Specification {

    def "a search step pins no entry, where a question pins its own"() {
        expect:
        RunSnapshots.pinnedEntry(searchStep(1)) == null
        RunSnapshots.pinnedEntry(question(2, person(), 1)) == pinned(2).entry()
    }
}
