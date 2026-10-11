package com.lilradish.lite

import static com.lilradish.lite.testutil.RedactionSamples.BUDGET
import static com.lilradish.lite.testutil.RedactionSamples.DECIDED_AT
import static com.lilradish.lite.testutil.RedactionSamples.FINANCE
import static com.lilradish.lite.testutil.RedactionSamples.NIGHTLY_DIGEST
import static com.lilradish.lite.testutil.RedactionSamples.OVERSEER
import static com.lilradish.lite.testutil.RedactionSamples.RUN

import com.lilradish.lite.app.inference.ConvertedCallAnswer
import com.lilradish.lite.app.inference.SearchObservation
import com.lilradish.lite.app.inference.ToolCallOutcome
import com.lilradish.lite.domain.declaration.Demand
import com.lilradish.lite.domain.identity.Delegation
import com.lilradish.lite.domain.identity.GroupPermission
import com.lilradish.lite.domain.identity.GroupRole
import com.lilradish.lite.domain.identity.HumanPrincipal
import com.lilradish.lite.domain.identity.SystemPrincipal
import com.lilradish.lite.domain.identity.UserId
import com.lilradish.lite.domain.listing.ListFilter
import com.lilradish.lite.domain.run.ReviewPayload
import com.lilradish.lite.domain.wire.JsonValue
import com.lilradish.lite.domain.workflow.StepId
import org.libprunus.core.log.runtime.Loggable
import spock.lang.Specification

/** AppLoggingConvention suffixes drive rewriting; assert redaction and readable identifiers to catch silent misses or empty output. */
class LoggingRedactionIntegrationSpec extends Specification {

    static final StepId CLASSIFY = new StepId("classify")

    def "the pinned rewrite matches every newly named sensitive carrier, including the nested binary name"() {
        expect:
        Loggable.isAssignableFrom(carrier)

        where:
        carrier << [ConvertedCallAnswer, ToolCallOutcome, SearchObservation, ListFilter, ReviewPayload.Built]
    }

    def "typed filters and built review payloads have complete safe renderings"() {
        expect:
        new ListFilter("private search words").toString() == 'ListFilter()'
        new ReviewPayload.Built(new JsonValue.JsonObject([
                new JsonValue.JsonMember("summary", new JsonValue.JsonString("private result"))])).toString() == 'ReviewPayload$Built()'
    }

    /** Identifiers must remain readable so a logged act can be traced. */
    def "the identifiers a line has to be traced by are readable rather than identity hashes"() {
        expect:
        RUN.toString() == "RunId(value=44440000-0000-4000-8000-000000000001)"
        CLASSIFY.toString() == "StepId(value=classify)"
        OVERSEER.toString() == "SubjectId(value=44440000-0000-4000-8000-000000000003)"

        and: "including what a provider calls a person, which reaches a line about the act it is recorded against"
        new UserId("overseer@example.com").toString() == "UserId(value=overseer@example.com)"
    }

    def "a system actor renders who it acts as"() {
        when:
        def rendered = SystemPrincipal.WORKFLOW_RUNNER.toString()

        then:
        rendered.startsWith("SystemPrincipal(")

        and:
        rendered.contains("subject=SubjectId(value=00000000-0000-4000-8000-000000000001)")

        and: "and the constant's own name is absent, which is how a rendering that took statics in would show"
        !rendered.contains("WORKFLOW_RUNNER")
    }

    def "a delegation renders what acted, who it is charged to, where, and what it was bounded to"() {
        given:
        def owner = HumanPrincipal.of(OVERSEER, [] as Set, [(FINANCE): [GroupRole.OVERSEER] as Set])
        def registration = new Delegation.Registration(
                UUID.fromString("44440000-0000-4000-8000-000000000004"),
                NIGHTLY_DIGEST,
                OVERSEER,
                FINANCE,
                DECIDED_AT,
                null,
                [GroupPermission.READ_ALL_RUNS] as Set)

        when:
        def rendered = Delegation.of(registration, owner, DECIDED_AT.minusSeconds(1)).toString()

        then:
        rendered.startsWith("Delegation(")

        and: "naming the subject that acted and the one it is charged to, both a level down"
        rendered.contains("label=SubjectId(value=" + NIGHTLY_DIGEST.value() + ")")
        rendered.contains("owner=SubjectId(value=" + OVERSEER.value() + ")")

        and: "and where it may be exercised, down to the identifier the group is named by"
        rendered.contains('group=Scope$Group(groupId=GroupId(value=44440000-0000-4000-8000-000000000005))')

        and: "said once, the slot repeating it being dropped so the budget reaches what bounds the call"
        !rendered.contains("scopes=")

        and: "and what it was bounded to, which is what a line about a refused call is read for"
        rendered.contains("permissions=[READ_ALL_RUNS]")
        rendered.contains("roles=[OVERSEER]")

        and: "while the permission its owner holds and it was never granted is absent"
        !rendered.contains("REVIEW_AT_GATE")

        and: "and the registration is rendered rather than falling back to a hash, which names neither subject"
        rendered.contains('registration=Delegation$Registration(')
        !rendered.contains("Registration@")

        and: "and the line ran to its end, a cut one having lost the bounds rather than rendered them"
        !rendered.endsWith('...[TRUNCATED])')

        and: "with the margin the dropped slot bought, so a slot added back fails here rather than in a log"
        BUDGET - rendered.length() == 60
    }

    def "whether a declared field must be given renders as it is, under a head only the rewrite emits"() {
        when:
        def rendered = new Demand.Given(true).toString()

        then:
        rendered == 'Demand$Given(mustBe=true)'
    }
}
