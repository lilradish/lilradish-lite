package org.lilradish.lite.domain.identity

import java.lang.reflect.Modifier
import spock.lang.Specification

class SystemPrincipalSpec extends Specification {

    /**
     * Discovered from the class rather than listed here on purpose. A list written by hand would be
     * a second declaration of what the system principals are, and a constant added without a row in
     * it would go unexamined by every total mapping below.
     */
    static final Map<String, SystemPrincipal> DECLARED = SystemPrincipal.class.declaredFields
            .findAll { it.type == SystemPrincipal }
            .collectEntries { [(it.name): SystemPrincipal."$it.name"] }

    def "the system principals that exist are exactly those declared in code"() {
        given:
        def distinct = Collections.newSetFromMap(new IdentityHashMap<SystemPrincipal, Boolean>())
        distinct.addAll(DECLARED.values())

        expect:
        DECLARED.keySet() == ["WORKFLOW_RUNNER"] as Set

        and: "and each is a distinct actor rather than one constant reachable under several names"
        distinct.size() == DECLARED.size()
    }

    /**
     * This is what the structural argument rests on, and it has two halves. Every system principal
     * is declared in this file, because the constructor is private; and nothing but a subject can be
     * declared, because no other parameter exists. Widening either — opening the constructor up, or
     * adding a role to it — breaks this, which is the point: the guarantee is in the signature rather
     * than in a comment asking editors to remember it.
     */
    def "a subject is the only thing a system principal can be constructed from"() {
        given:
        def declared = SystemPrincipal.class.declaredConstructors

        expect:
        declared.length == 1
        Modifier.isPrivate(declared[0].modifiers)
        declared[0].parameterTypes*.simpleName == ["String"]
    }

    /**
     * Written out rather than read back off the constants, which would agree with whatever they say
     * and prove nothing. Each literal is one half of a pair whose other half is a row the baseline
     * seeds, so a constant edited away from its row fails here rather than at the first write.
     */
    def "each system principal names the subject row the baseline seeds for it"() {
        given:
        def expected = [
                WORKFLOW_RUNNER: new SubjectId(UUID.fromString("00000000-0000-4000-8000-000000000001")),
        ]

        expect:
        DECLARED.collectEntries { name, principal -> [(name): principal.subject()] } == expected
    }

    def "a system principal is charged for what it does to itself, having no owner to charge"() {
        expect:
        DECLARED.values().every { it.accountableSubject() == it.subject() }

        and: "and each answers with its own name rather than all of them with one shared stand-in"
        DECLARED.values().collect { it.accountableSubject() }.toSet().size() == DECLARED.size()
    }

    def "no system principal may surface what it read"() {
        expect:
        DECLARED.values().every { !it.maySurfaceContent() }
    }
}
