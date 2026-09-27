package org.lilradish.lite.domain.registry

import spock.lang.Specification

class EntryKindSpec extends Specification {

    /**
     * Pinned whole and in order: the store's vocabulary is held to this declaration label by label,
     * so a kind added, dropped or moved here is a change to what the store accepts as well.
     */
    def "the kinds an entry may be are fixed, in the order they are declared"() {
        expect:
        EntryKind.values().toList() == [EntryKind.WORKFLOW, EntryKind.QUESTION, EntryKind.REFERENCE_LIST]
    }

    /** A reader names what it shows by these, so a constant renamed without its spelling staying put breaks it. */
    def "each kind is published under the spelling a reader names it by"() {
        expect:
        EntryKind.values().collectEntries { [(it): it.published()] } == [
                (EntryKind.WORKFLOW)      : "workflow",
                (EntryKind.QUESTION)      : "question",
                (EntryKind.REFERENCE_LIST): "reference_list",
        ]
    }

    /** A saved address names a kind by its segment, so a segment moved is every such address broken. */
    def "each kind is addressed under the segment a reader's page of it is at"() {
        expect:
        EntryKind.values().collectEntries { [(it): it.segment()] } == [
                (EntryKind.WORKFLOW)      : "workflows",
                (EntryKind.QUESTION)      : "questions",
                (EntryKind.REFERENCE_LIST): "reference-lists",
        ]
    }
}
