package org.lilradish.lite.app.run

import static org.mockito.ArgumentMatchers.any
import static org.mockito.BDDMockito.given
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get

import jakarta.servlet.http.HttpServletRequest
import java.time.Instant
import org.libprunus.spring.error.ApiErrorAutoConfiguration
import org.lilradish.lite.app.estate.EstateRoleGrants
import org.lilradish.lite.app.group.GroupRoles
import org.lilradish.lite.app.identification.UserIdentification
import org.lilradish.lite.app.pool.PersonRows
import org.lilradish.lite.domain.identity.EstateRole
import org.lilradish.lite.domain.identity.GroupId
import org.lilradish.lite.domain.identity.GroupRole
import org.lilradish.lite.domain.identity.SubjectId
import org.lilradish.lite.domain.identity.UserId
import org.lilradish.lite.domain.people.PersonName
import org.lilradish.lite.domain.registry.EntryId
import org.lilradish.lite.domain.registry.EntryName
import org.lilradish.lite.domain.run.Ceiling
import org.lilradish.lite.domain.run.CeilingChangeId
import org.lilradish.lite.domain.run.RunAct
import org.lilradish.lite.domain.run.RunId
import org.lilradish.lite.domain.run.RunName
import org.lilradish.lite.domain.run.RunState
import org.lilradish.lite.domain.run.WorkflowStepId
import org.lilradish.lite.domain.workflow.StepId
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import spock.lang.Specification
import tools.jackson.databind.json.JsonMapper

/**
 * What reading a run is answered with, over a real dispatcher: the run as read, spelt as a page draws it, and
 * every refusal met before the store is asked. The store and who is calling are replaced.
 */
@WebMvcTest([RunsController, RunChangesController])
@ImportAutoConfiguration(ApiErrorAutoConfiguration)
class RunsControllerIntegrationSpec extends Specification {

    static final UserId READER = new UserId("000001")

    static final JsonMapper JSON = JsonMapper.builder().build()

    static final UUID GROUP = UUID.fromString("00000003-0000-4000-8000-000000001201")

    static final UUID RUN = UUID.fromString("00000008-0000-4000-8000-000000001201")

    static final UUID ABOVE = UUID.fromString("00000008-0000-4000-8000-000000001202")

    static final UUID WORKFLOW = UUID.fromString("00000006-0000-4000-8000-000000001201")

    static final UUID RAISE = UUID.fromString("0000000b-0000-4000-8000-000000001201")

    static final UUID STEP = UUID.fromString("00000009-0000-4000-8000-000000001201")

    static final String ADDRESS = "/api/groups/${GROUP}/runs/${RUN}"

    static final Instant STARTED = Instant.parse("2026-09-25T08:00:00Z")

    static final PersonRows.Person ADA = new PersonRows.Person(
            new SubjectId(UUID.fromString("00000002-0000-4000-8000-000000001201")), new UserId("001201"),
            new PersonName("Ada Lovelace"))

    static final PersonRows.Person GRACE = new PersonRows.Person(
            new SubjectId(UUID.fromString("00000002-0000-4000-8000-000000001202")), new UserId("001202"), null)

    static final Runs.Workflow HANDLING = new Runs.Workflow(new EntryId(WORKFLOW), new EntryName("Handle a claim"), 3)

    static final Runs.NumberedRun ABOVE_RUN = new Runs.NumberedRun(new RunId(ABOVE), 7)

    static final String STARTED_WITH = """
            {"claim":"Lost bag","amount":"12.50","urgent":"true","noticed":null,"tags":["late","torn"],
             "sender":{"name":"Ada","since":"2026-09-24T10:00:00+02:00"}}"""

    /**
     * Stopped by Grace, spent past what a double holds, part of it as this system measured it, at the highest ceiling a
     * reader holds, a removal asked.
     */
    static final Runs.RunView STOPPED = new Runs.RunView(new RunId(RUN), 7, new RunName("Claim from Ada"), null, HANDLING,
            ADA, STARTED, JSON.readTree(STARTED_WITH), RunState.STOPPED, null,
            new Runs.Stop(STARTED.plusSeconds(60), new Runs.Stopper.ByPerson(GRACE)),
            new RunBudget.Spend(9007199254740993L, 20, true, true),
            new Runs.CeilingHeld.Own(new RunBudget.InForce(new Ceiling(Ceiling.LARGEST), true),
                    new CeilingRaises.Waiting(new CeilingChangeId(RAISE), null, ADA, STARTED.plusSeconds(120), true)),
            EnumSet.of(RunAct.OPEN_AGAIN, RunAct.WITHDRAW_RAISE))

    /** Beneath another, its ceiling reached, and held to the ceiling at the top, keeping none of its own. */
    static final Runs.RunView BENEATH = new Runs.RunView(new RunId(RUN), 8, null, new RunId(ABOVE), HANDLING, null,
            STARTED, null, RunState.STOPPED, null,
            new Runs.Stop(STARTED.plusSeconds(60), new Runs.Stopper.ByCeiling(ABOVE_RUN)),
            new RunBudget.Spend(0, 0, false, false), new Runs.CeilingHeld.AtTop(ABOVE_RUN), EnumSet.noneOf(RunAct))

    @Autowired
    private MockMvc mockMvc

    @MockitoBean
    private UserIdentification identification

    @MockitoBean
    private EstateRoleGrants grants

    @MockitoBean
    private GroupRoles groupRoles

    @MockitoBean
    private Runs runs

    @MockitoBean
    private RunChanges changes

    private void holding(Set<GroupRole> held) {
        given(identification.identify(any(HttpServletRequest))).willReturn(Optional.of(READER))
        given(grants.heldBy(READER)).willReturn(EnumSet.allOf(EstateRole))
        given(groupRoles.heldBy(READER, new GroupId(GROUP))).willReturn(held)
    }

    private MvcResult reading(String address) {
        mockMvc.perform(get(address)).andReturn()
    }

    def "answers a run as it is read, every count in digits so none arrives rounded"() {
        given:
        holding(EnumSet.of(GroupRole.OPERATOR))
        given(runs.run(new GroupId(GROUP), new RunId(RUN), READER)).willReturn(STOPPED)

        when:
        def answered = reading(ADDRESS)

        then:
        answered.response.status == 200
        JSON.readTree(answered.response.contentAsString) == JSON.readTree("""
                {"runId":"${RUN}","number":7,"name":"Claim from Ada",
                 "workflow":{"entryId":"${WORKFLOW}","name":"Handle a claim","version":3},
                 "startedBy":{"userId":"001201","displayName":"Ada Lovelace"},"startedAt":"2026-09-25T08:00:00Z",
                 "startedWith":${STARTED_WITH},
                 "state":"stopped","stopped":{"at":"2026-09-25T08:01:00Z","by":{"userId":"001202"}},
                 "spend":{"sent":"9007199254740993","cameBack":"20","spent":"9007199254741013","cameBackUnknown":true,
                          "measuredHere":true},
                 "ceiling":{"inForce":"9007199254740991","raiseNeedsApproval":true,
                            "waiting":{"changeId":"${RAISE}",
                                       "askedBy":{"userId":"001201","displayName":"Ada Lovelace"},
                                       "askedAt":"2026-09-25T08:02:00Z"}},
                 "acts":["open_again","withdraw_raise"]}
                """ as String)
        def startedWith = JSON.readTree(answered.response.contentAsString).get("startedWith")
        startedWith.propertyNames().toList() == ["claim", "amount", "urgent", "noticed", "tags", "sender"]
        startedWith.get("sender").propertyNames().toList() == ["name", "since"]

        and:
        readOnce()
    }

    /** Void, so the verification is what fails rather than the null a mock's answer would assert as. */
    private void readOnce() {
        Mockito.verify(runs).run(new GroupId(GROUP), new RunId(RUN), READER)
        Mockito.verifyNoMoreInteractions(runs)
        Mockito.verifyNoInteractions(changes)
    }

    /** What a run beneath has none of is left out, never sent as null; a ceiling reached, or held to, names its run. */
    def "answers a run beneath another with the run above, no starter, a stop by a ceiling, and the ceiling it is held to"() {
        given:
        holding(EnumSet.of(GroupRole.OVERSEER))
        given(runs.run(new GroupId(GROUP), new RunId(RUN), READER)).willReturn(BENEATH)

        when:
        def answered = reading(ADDRESS)

        then:
        JSON.readTree(answered.response.contentAsString) == JSON.readTree("""
                {"runId":"${RUN}","number":8,"above":"${ABOVE}",
                 "workflow":{"entryId":"${WORKFLOW}","name":"Handle a claim","version":3},
                 "startedAt":"2026-09-25T08:00:00Z","state":"stopped",
                 "stopped":{"at":"2026-09-25T08:01:00Z","ceilingOf":{"runId":"${ABOVE}","number":7}},
                 "spend":{"sent":"0","cameBack":"0","spent":"0","cameBackUnknown":false},
                 "ceiling":{"heldBy":{"runId":"${ABOVE}","number":7}},"acts":[]}
                """ as String)
    }

    def "answers a running run with the step it is on, and a failed or done one with none"() {
        given:
        holding(EnumSet.of(GroupRole.OPERATOR))
        given(runs.run(new GroupId(GROUP), new RunId(RUN), READER)).willReturn(new Runs.RunView(new RunId(RUN), 7,
                new RunName("Claim from Ada"), null, HANDLING, ADA, STARTED, JSON.createObjectNode(), state, at, null,
                new RunBudget.Spend(0, 0, false, false), new Runs.CeilingHeld.Own(new RunBudget.InForce(null, false), null),
                EnumSet.of(RunAct.RENAME)))

        when:
        def answered = JSON.readTree(reading(ADDRESS).response.contentAsString)

        then:
        answered.get("state").asString() == published
        answered.get("startedWith") == JSON.createObjectNode()
        answered.path("at").isMissingNode() == (at == null)
        at == null || answered.get("at") == JSON.readTree("""{"stepId":"${STEP}","name":"summarise"}""" as String)

        and: "and nothing says it was stopped"
        answered.path("stopped").isMissingNode()

        where:
        state            | at                                                                || published
        RunState.RUNNING | new Runs.At(new WorkflowStepId(STEP), new StepId("summarise"))    || "running"
        RunState.FAILED  | null                                                              || "failed"
        RunState.DONE    | null                                                              || "done"
    }

    /** An address naming no identifier is answered as a run the caller may not read, before the store is asked. */
    def "refuses an address naming no run, and never asks the store"() {
        given:
        holding(EnumSet.of(GroupRole.OWNER))

        when:
        def answered = reading("/api/groups/${GROUP}/runs/${named}")

        then:
        answered.response.status == 404
        JSON.readTree(answered.response.contentAsString).get("code").asString() == "RUN_NOT_IN_VIEW"
        Mockito.verifyNoInteractions(runs, changes)

        where:
        named << ["not-a-run", "0000000812014000800000000001201"]
    }

    def "refuses a parameter, which reading a run does not take, and never asks the store"() {
        given:
        holding(EnumSet.of(GroupRole.OWNER))

        when:
        def answered = mockMvc.perform(get(ADDRESS).queryParam("view", "detail")).andReturn()

        then:
        answered.response.status == 400
        JSON.readTree(answered.response.contentAsString).get("code").asString() == "PARAMETER_UNKNOWN"
        Mockito.verifyNoInteractions(runs, changes)
    }

    /** Membership alone reaches the read; what the member may read of it is the store's to judge. */
    def "refuses somebody holding nothing in the group as no group, and never asks the store"() {
        given:
        holding(EnumSet.noneOf(GroupRole))

        when:
        def answered = reading(ADDRESS)

        then:
        answered.response.status == 404
        JSON.readTree(answered.response.contentAsString).get("code").asString() == "GROUP_NOT_IN_VIEW"
        Mockito.verifyNoInteractions(runs, changes)
    }
}
