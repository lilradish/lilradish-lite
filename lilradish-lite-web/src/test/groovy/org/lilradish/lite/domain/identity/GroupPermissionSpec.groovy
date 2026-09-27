package org.lilradish.lite.domain.identity

import spock.lang.Specification

class GroupPermissionSpec extends Specification {

    /**
     * Locked as a list rather than as a set: a gate demanding two permissions somebody lacks names
     * the one declared first, so reordering these changes what a refusal says. Widening the list
     * widens every owner, who holds all of it.
     */
    def "the vocabulary of what somebody may do inside a group is fixed, in the order a refusal names it"() {
        expect:
        GroupPermission.values() as List == [
                GroupPermission.READ_MEMBERSHIP,
                GroupPermission.CHANGE_MEMBERSHIP,
                GroupPermission.START_RUN,
                GroupPermission.READ_OWN_RUNS,
                GroupPermission.READ_ALL_RUNS,
                GroupPermission.READ_INFERENCE_CONTENT,
                GroupPermission.ANSWER_STEP,
                GroupPermission.REVIEW_AT_GATE,
                GroupPermission.AUTHOR_ENTRY,
                GroupPermission.APPROVE_ENTRY,
                GroupPermission.REVOKE_ENTRY,
        ]
    }

    /** A reader gates a group's pages on these strings, so a spelling moving with a rename shuts every page. */
    def "each permission is published under the spelling the reader gates on"() {
        given:
        def expected = [
                (GroupPermission.READ_MEMBERSHIP)       : "read_membership",
                (GroupPermission.CHANGE_MEMBERSHIP)     : "change_membership",
                (GroupPermission.START_RUN)             : "start_run",
                (GroupPermission.READ_OWN_RUNS)         : "read_own_runs",
                (GroupPermission.READ_ALL_RUNS)         : "read_all_runs",
                (GroupPermission.READ_INFERENCE_CONTENT): "read_inference_content",
                (GroupPermission.ANSWER_STEP)           : "answer_step",
                (GroupPermission.REVIEW_AT_GATE)        : "review_at_gate",
                (GroupPermission.AUTHOR_ENTRY)          : "author_entry",
                (GroupPermission.APPROVE_ENTRY)         : "approve_entry",
                (GroupPermission.REVOKE_ENTRY)          : "revoke_entry",
        ]

        expect:
        GroupPermission.values().collectEntries { [(it): it.published()] } == expected
    }

    /** A shared spelling opens one page on another's permission, and this side's own codes are lowercase. */
    def "no two permissions are published under one spelling, and none of them is screaming snake"() {
        expect:
        GroupPermission.values()*.published().toSet().size() == GroupPermission.values().length

        and:
        GroupPermission.values().every { it.published() ==~ /^[a-z][a-z0-9_]*$/ }

        and: "over a vocabulary that is populated, an empty one passing both"
        GroupPermission.values().length > 0
    }
}
