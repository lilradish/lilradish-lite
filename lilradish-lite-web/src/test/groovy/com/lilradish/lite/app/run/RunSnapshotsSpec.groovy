package com.lilradish.lite.app.run

import static com.lilradish.lite.domain.run.fixture.Runs.person
import static com.lilradish.lite.domain.run.fixture.Runs.pinned
import static com.lilradish.lite.domain.run.fixture.Runs.question
import static com.lilradish.lite.domain.run.fixture.Runs.searchStep

import spock.lang.Specification

class RunSnapshotsSpec extends Specification {

    def "search and ordinary question steps each pin their own question entry"() {
        expect:
        RunSnapshots.pinnedEntry(searchStep(1)) == pinned(1).entry()
        RunSnapshots.pinnedEntry(question(2, person(), 1)) == pinned(2).entry()
    }
}
