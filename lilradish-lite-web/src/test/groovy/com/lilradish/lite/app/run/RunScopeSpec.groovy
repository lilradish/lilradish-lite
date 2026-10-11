package com.lilradish.lite.app.run

import static com.lilradish.lite.domain.identity.GroupPermission.READ_ALL_RUNS
import static com.lilradish.lite.domain.identity.GroupPermission.READ_OWN_RUNS
import static com.lilradish.lite.domain.identity.GroupPermission.START_RUN

import spock.lang.Specification

class RunScopeSpec extends Specification {

    def "a group's run scope follows its reading grants, with every run taking precedence over one's own"() {
        expect:
        RunScope.reachedBy(permitted as Set) == reach

        where:
        permitted                     || reach
        [READ_OWN_RUNS]               || RunScope.Reach.OWN
        [READ_ALL_RUNS]               || RunScope.Reach.EVERY
        [READ_OWN_RUNS, READ_ALL_RUNS] || RunScope.Reach.EVERY
        [START_RUN]                   || RunScope.Reach.NONE
        []                            || RunScope.Reach.NONE
    }
}
