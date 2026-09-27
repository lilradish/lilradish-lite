package org.lilradish.lite.app.run

import static org.lilradish.lite.domain.identity.GroupPermission.READ_ALL_RUNS
import static org.lilradish.lite.domain.identity.GroupPermission.READ_OWN_RUNS
import static org.lilradish.lite.domain.identity.GroupPermission.START_RUN

import spock.lang.Specification

class RunScopeSpec extends Specification {

    def "somebody reads a run they just started exactly where they may read their own runs or every run"() {
        expect:
        RunScope.readsWhatTheyStart(permitted as Set) == reads

        where:
        permitted                      || reads
        [READ_OWN_RUNS]                || true
        [READ_ALL_RUNS]                || true
        [READ_OWN_RUNS, READ_ALL_RUNS] || true
        [START_RUN]                    || false
        []                             || false
    }
}
