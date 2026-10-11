package com.lilradish.lite.app.run

import static org.mockito.ArgumentMatchers.any
import static org.mockito.BDDMockito.given
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get

import com.lilradish.lite.app.estate.EstateRoleGrants
import com.lilradish.lite.app.group.GroupRoles
import com.lilradish.lite.app.identification.UserIdentification
import com.lilradish.lite.app.pool.PersonRows
import com.lilradish.lite.domain.identity.EstateRole
import com.lilradish.lite.domain.identity.GroupId
import com.lilradish.lite.domain.identity.GroupRole
import com.lilradish.lite.domain.identity.SubjectId
import com.lilradish.lite.domain.identity.UserId
import com.lilradish.lite.domain.people.PersonName
import com.lilradish.lite.domain.registry.EntryId
import com.lilradish.lite.domain.registry.EntryName
import com.lilradish.lite.domain.run.Ceiling
import com.lilradish.lite.domain.run.CeilingChangeId
import com.lilradish.lite.domain.run.RunAct
import com.lilradish.lite.domain.run.RunId
import com.lilradish.lite.domain.run.RunName
import com.lilradish.lite.domain.run.RunState
import com.lilradish.lite.domain.run.WorkflowStepId
import com.lilradish.lite.domain.workflow.StepId
import jakarta.servlet.http.HttpServletRequest
import java.time.Instant
import org.libprunus.spring.error.ApiErrorAutoConfiguration
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
            new PersonName("Ari Mockvale"))

    static final PersonRows.Person GRACE = new PersonRows.Person(
            new SubjectId(UUID.fromString("00000002-0000-4000-8000-000000001202")), new UserId("001202"), null)

    static final Runs.Workflow HANDLING = new Runs.Workflow(new EntryId(WORKFLOW), new EntryName("Handle a claim"), 3, null)

    static final Runs.NumberedRun ABOVE_RUN = new Runs.NumberedRun(new RunId(ABOVE), 7)

    static final String STARTED_WITH = """
            {"claim":"Lost bag","amount":"12.50","urgent":"true","noticed":null,"tags":["late","torn"],
             "sender":{"name":"Ari","since":"2026-09-24T10:00:00+02:00"}}"""

    static final Runs.RunView STOPPED = new Runs.RunView(new RunId(RUN), 7, new RunName("Claim from Ari"), null, null, [],
            HANDLING,
            ADA, STARTED, JSON.readTree(STARTED_WITH), null, RunState.STOPPED, null,
            new Runs.Stop(STARTED.plusSeconds(60), new Runs.Stopper.ByPerson(GRACE)),
            new RunBudget.Spend(9007199254740993L, 20, true, 1, 1),
            new Runs.CeilingHeld.Own(new RunBudget.InForce(new Ceiling(Ceiling.LARGEST), true),
                    new CeilingRaises.Waiting(new CeilingChangeId(RAISE), null, ADA, STARTED.plusSeconds(120), true)),
            EnumSet.of(RunAct.OPEN_AGAIN, RunAct.WITHDRAW_RAISE), [])

    /** Beneath another, its ceiling reached, and held to the ceiling at the top, keeping none of its own. */
    static final Runs.RunView BENEATH = new Runs.RunView(new RunId(RUN), 8, null, new RunId(ABOVE),
            new Runs.From(ABOVE_RUN, new WorkflowStepId(STEP), new StepId("route"), null),
            [new Runs.RunAbove(new RunId(ABOVE), 7, new RunName("Claim from Greno"))], HANDLING, null,
            STARTED, null, null, RunState.STOPPED, null,
            new Runs.Stop(STARTED.plusSeconds(60), new Runs.Stopper.ByCeiling([ABOVE_RUN])),
            new RunBudget.Spend(0, 0, false, 0, 0), new Runs.CeilingHeld.AtTop(ABOVE_RUN), EnumSet.noneOf(RunAct), [])

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

    def "a run publishes measured calls as wholly or partly, and omits the marker when none were measured here"() {
        given:
        holding(EnumSet.of(GroupRole.OPERATOR))
        def view = new Runs.RunView(BENEATH.runId(), BENEATH.number(), BENEATH.name(), BENEATH.above(), BENEATH.from(),
                BENEATH.runsAbove(), BENEATH.workflow(), BENEATH.startedBy(), BENEATH.startedAt(), BENEATH.startedWith(), BENEATH.startedWithFrom(), BENEATH.state(),
                BENEATH.at(), BENEATH.stop(), new RunBudget.Spend(0, 0, false, measured, total), BENEATH.ceiling(),
                BENEATH.stateActs(), BENEATH.conversationEvents())
        given(runs.run(new GroupId(GROUP), new RunId(RUN), READER)).willReturn(view)

        when:
        def answered = reading(ADDRESS)
        def spend = JSON.readTree(answered.response.contentAsString).get("spend")

        then:
        answered.response.status == 200
        spend.get("measuredHere")?.asString() == published
        spend.has("measuredHere") == (published != null)
        spend.get("sent").asString() == "0"
        spend.get("spent").asString() == "0"
        readOnce()

        where:
        measured | total || published
        0        | 0     || null
        0        | 2     || null
        2        | 2     || "wholly"
        1        | 2     || "partly"
    }

    /** This DTO-boundary canary proves MVC serialization, separately from the real store's state facts. */
    def "MVC keeps mandatory nullable run state acts distinct from an empty set"() {
        given:
        holding(EnumSet.of(GroupRole.OPERATOR))
        def view = new Runs.RunView(BENEATH.runId(), BENEATH.number(), BENEATH.name(), BENEATH.above(), BENEATH.from(),
                BENEATH.runsAbove(), BENEATH.workflow(), BENEATH.startedBy(), BENEATH.startedAt(), BENEATH.startedWith(),
                BENEATH.startedWithFrom(), BENEATH.state(), BENEATH.at(), BENEATH.stop(), BENEATH.spend(),
                BENEATH.ceiling(), acts, BENEATH.conversationEvents())
        given(runs.run(new GroupId(GROUP), new RunId(RUN), READER)).willReturn(view)

        when:
        def answered = reading(ADDRESS)
        def document = JSON.readTree(answered.response.contentAsString)

        then:
        answered.response.status == 200
        document.has("stateActs")
        document.get("stateActs") == JSON.readTree(published)
        !document.has("name")
        !document.has("at")
        !document.has("startedBy")
        readOnce()

        where:
        acts                   || published
        null                   || "null"
        EnumSet.noneOf(RunAct) || "[]"
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
                {"runId":"${RUN}","number":7,"name":"Claim from Ari",
                 "workflow":{"entryId":"${WORKFLOW}","name":"Handle a claim","version":3,"versionName":null},
                 "runsAbove":[],
                 "startedBy":{"userId":"001201","displayName":"Ari Mockvale"},"startedAt":"2026-09-25T08:00:00Z",
                 "startedWith":${STARTED_WITH},
                 "state":"stopped","stopped":{"at":"2026-09-25T08:01:00Z","by":{"userId":"001202"}},
                 "spend":{"sent":"9007199254740993","cameBack":"20","spent":"9007199254741013","cameBackUnknown":true,
                          "measuredHere":"wholly"},
                 "ceiling":{"inForce":"9007199254740991","raiseNeedsApproval":true,
                            "waiting":{"changeId":"${RAISE}",
                                       "askedBy":{"userId":"001201","displayName":"Ari Mockvale"},
                                       "askedAt":"2026-09-25T08:02:00Z"}},
                 "stateActs":["open_again","withdraw_raise"],"conversationEvents":[]}
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
                 "from":{"runId":"${ABOVE}","number":7,"stepId":"${STEP}","stepName":"route"},
                 "runsAbove":[{"runId":"${ABOVE}","number":7,"name":"Claim from Greno"}],
                 "workflow":{"entryId":"${WORKFLOW}","name":"Handle a claim","version":3,"versionName":null},
                 "startedAt":"2026-09-25T08:00:00Z","state":"stopped",
                 "stopped":{"at":"2026-09-25T08:01:00Z","ceilingOf":{"runId":"${ABOVE}","number":7},
                            "ceilings":[{"runId":"${ABOVE}","number":7}]},
                 "spend":{"sent":"0","cameBack":"0","spent":"0","cameBackUnknown":false},
                 "ceiling":{"heldBy":{"runId":"${ABOVE}","number":7}},"stateActs":[],"conversationEvents":[]}
                """ as String)
    }

    /** A name is held only at the top, so a run between says its number alone, never a null name. */
    def "answers every run above one two levels beneath from the top down, a name only where the top holds one"() {
        given:
        holding(EnumSet.of(GroupRole.OVERSEER))
        def top = UUID.fromString("00000008-0000-4000-8000-000000001203")
        def view = new Runs.RunView(BENEATH.runId(), BENEATH.number(), BENEATH.name(), BENEATH.above(), BENEATH.from(),
                [new Runs.RunAbove(new RunId(top), 6, new RunName("Claim from Greno")),
                 new Runs.RunAbove(new RunId(ABOVE), 7, null)],
                BENEATH.workflow(), BENEATH.startedBy(), BENEATH.startedAt(), BENEATH.startedWith(), BENEATH.startedWithFrom(), BENEATH.state(),
                BENEATH.at(), BENEATH.stop(), BENEATH.spend(), BENEATH.ceiling(), BENEATH.stateActs(), BENEATH.conversationEvents())

        given(runs.run(new GroupId(GROUP), new RunId(RUN), READER)).willReturn(view)

        when:
        def answered = JSON.readTree(reading(ADDRESS).response.contentAsString)

        then:
        answered.get("runsAbove") == JSON.readTree("""
                [{"runId":"${top}","number":6,"name":"Claim from Greno"},{"runId":"${ABOVE}","number":7}]
                """ as String)
        !answered.get("runsAbove").get(1).has("name")
        !answered.has("name")
        readOnce()
    }

    def "answers a stop by ceilings reached at once with every one from the top down, the first also as the one it names"() {
        given:
        holding(EnumSet.of(GroupRole.OVERSEER))
        def both = new Runs.RunView(new RunId(RUN), 8, null, new RunId(ABOVE),
                new Runs.From(ABOVE_RUN, new WorkflowStepId(STEP), new StepId("route"), null), BENEATH.runsAbove(), HANDLING,
                null,
                STARTED, null, null, RunState.STOPPED, null,
                new Runs.Stop(STARTED.plusSeconds(60),
                        new Runs.Stopper.ByCeiling([ABOVE_RUN, new Runs.NumberedRun(new RunId(RUN), 8)])),
                new RunBudget.Spend(0, 0, false, 0, 0), new Runs.CeilingHeld.AtTop(ABOVE_RUN), EnumSet.noneOf(RunAct), [])
        given(runs.run(new GroupId(GROUP), new RunId(RUN), READER)).willReturn(both)

        when:
        def answered = reading(ADDRESS)

        then:
        JSON.readTree(answered.response.contentAsString).path("stopped") == JSON.readTree("""
                {"at":"2026-09-25T08:01:00Z","ceilingOf":{"runId":"${ABOVE}","number":7},
                 "ceilings":[{"runId":"${ABOVE}","number":7},{"runId":"${RUN}","number":8}]}
                """ as String)
    }

    def "a child start publishes each bound value with its actual source"() {
        given:
        holding(EnumSet.of(GroupRole.OVERSEER))
        def origin = new StepAnswers.FromAnswer("run_input", "ticket", null, null, null, null, null, ABOVE, 7, null)
        def source = new Runs.StartedSource("item", origin, null)
        def view = new Runs.RunView(BENEATH.runId(), BENEATH.number(), BENEATH.name(), BENEATH.above(),
                BENEATH.from(), BENEATH.runsAbove(), BENEATH.workflow(), BENEATH.startedBy(), BENEATH.startedAt(),
                JSON.readTree('{"item":"Demo Sample ticket"}'), [source], BENEATH.state(), BENEATH.at(),
                BENEATH.stop(), BENEATH.spend(), BENEATH.ceiling(), BENEATH.stateActs(),
                BENEATH.conversationEvents())
        given(runs.run(new GroupId(GROUP), new RunId(RUN), READER)).willReturn(view)

        when:
        def response = JSON.readTree(reading(ADDRESS).response.contentAsString)

        then:
        response.get("startedWith") == JSON.readTree('{"item":"Demo Sample ticket"}')
        response.get("startedWithFrom") == JSON.readTree("""
                [{"input":"item","from":{"kind":"run_input","path":"ticket",
                "runId":"${ABOVE}","runNumber":7}}]
                """ as String)
    }

    def "answers a running run with the step it is on, and a failed or done one with none"() {
        given:
        holding(EnumSet.of(GroupRole.OPERATOR))
        given(runs.run(new GroupId(GROUP), new RunId(RUN), READER)).willReturn(new Runs.RunView(new RunId(RUN), 7,
                new RunName("Claim from Ari"), null, null, [], HANDLING, ADA, STARTED, JSON.createObjectNode(), null, state, at,
                null,
                new RunBudget.Spend(0, 0, false, 0, 0), new Runs.CeilingHeld.Own(new RunBudget.InForce(null, false), null),
                EnumSet.of(RunAct.RENAME), []))

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
        RunState.RUNNING | new Runs.At(new WorkflowStepId(STEP), new StepId("summarise"), null)    || "running"
        RunState.FAILED  | null                                                              || "failed"
        RunState.DONE    | null                                                              || "done"
    }

    def "parent and current step display names are independent metadata beside their original machine identities"() {
        given:
        holding(EnumSet.of(GroupRole.OPERATOR))
        def parentLabel = label == null ? null : new com.lilradish.lite.domain.workflow.StepLabel("親の分岐\u202e")
        def currentLabel = label == null ? null : new com.lilradish.lite.domain.workflow.StepLabel(label)
        def view = new Runs.RunView(new RunId(RUN), 8, null, new RunId(ABOVE),
                new Runs.From(ABOVE_RUN, new WorkflowStepId(STEP), new StepId("route"), parentLabel),
                BENEATH.runsAbove(), HANDLING, null, STARTED, JSON.createObjectNode(), null, RunState.RUNNING,
                new Runs.At(new WorkflowStepId(STEP), new StepId("summarise"), currentLabel), null,
                new RunBudget.Spend(0, 0, false, 0, 0), new Runs.CeilingHeld.AtTop(ABOVE_RUN), EnumSet.noneOf(RunAct), [])
        given(runs.run(new GroupId(GROUP), new RunId(RUN), READER)).willReturn(view)

        when:
        def answered = reading(ADDRESS)

        then:
        answered.response.status == 200
        JSON.convertValue(JSON.readTree(answered.response.contentAsString), Map) == [
                runId: RUN.toString(), number: 8, above: ABOVE.toString(),
                from: [runId: ABOVE.toString(), number: 7, stepId: STEP.toString(), stepName: "route"] + parentMetadata,
                runsAbove: [[runId: ABOVE.toString(), number: 7, name: "Claim from Greno"]],
                workflow: [entryId: WORKFLOW.toString(), name: "Handle a claim", version: 3, versionName: null],
                startedAt: "2026-09-25T08:00:00Z", startedWith: [:], state: "running",
                at: [stepId: STEP.toString(), name: "summarise"] + currentMetadata,
                spend: [sent: "0", cameBack: "0", spent: "0", cameBackUnknown: false],
                ceiling: [heldBy: [runId: ABOVE.toString(), number: 7]], stateActs: [], conversationEvents: []]

        where:
        label        || parentMetadata          | currentMetadata
        null         || [:]                     | [:]
        "今の確認\u202e" || [label: "親の分岐\u202e"] | [label: "今の確認\u202e"]
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
