package org.lilradish.lite.app.run

import static org.lilradish.lite.app.run.fixture.TicketWorkflow.ANN
import static org.lilradish.lite.app.run.fixture.TicketWorkflow.ANN_USER
import static org.lilradish.lite.app.run.fixture.TicketWorkflow.CAT
import static org.lilradish.lite.app.run.fixture.TicketWorkflow.CAT_USER
import static org.lilradish.lite.app.run.fixture.TicketWorkflow.DAN_USER
import static org.lilradish.lite.app.run.fixture.TicketWorkflow.GROUP
import static org.lilradish.lite.app.run.fixture.TicketWorkflow.RUN
import static org.lilradish.lite.app.run.fixture.TicketWorkflow.SUMMARISE
import static org.lilradish.lite.app.run.fixture.TicketWorkflow.SUMMARISE_QUESTION
import static org.lilradish.lite.app.run.fixture.TicketWorkflow.VERSION
import static org.lilradish.lite.app.run.fixture.TicketWorkflow.WORKFLOW
import static org.lilradish.lite.testutil.library.LibraryStore.entryId
import static org.lilradish.lite.testutil.library.LibraryStore.groupId

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.util.concurrent.CountDownLatch
import org.libprunus.core.error.ApiErrorException
import org.lilradish.lite.app.group.GroupRoles
import org.lilradish.lite.app.run.fixture.StepRows
import org.lilradish.lite.app.run.fixture.TicketWorkflow
import org.lilradish.lite.domain.failure.RefusalCode
import org.lilradish.lite.domain.identity.UserId
import org.lilradish.lite.domain.inference.CallOutcome
import org.lilradish.lite.domain.inference.TurnAway
import org.lilradish.lite.domain.model.DeployedModel
import org.lilradish.lite.domain.model.ModelCatalog
import org.lilradish.lite.domain.model.ModelName
import org.lilradish.lite.domain.run.RunId
import org.lilradish.lite.domain.run.StepAct
import org.lilradish.lite.domain.run.StepGround
import org.lilradish.lite.domain.run.StepPosition
import org.lilradish.lite.domain.run.StepPositions
import org.lilradish.lite.domain.run.WorkflowStepId
import org.lilradish.lite.testutil.codestep.CodeStepsHeld
import org.lilradish.lite.testutil.library.DeployedModels
import org.lilradish.lite.testutil.library.LibraryStore
import org.lilradish.lite.testutil.run.RecordedModelCalls
import org.lilradish.lite.testutil.run.RunRows
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

/**
 * Try sending on a step a model produces, on a real server running the real baseline, the model scripted: the press
 * writes nothing where it is made, and the engine's thread, once it holds the tree, judges it again and writes the
 * attempt as the presser's with its call, measured against the models held then. A second wiring over the same store
 * stands in for a deploy holding other models, or for the process that starts after one was killed.
 */
class TrySendingIntegrationSpec extends Specification {

    static final String SYSTEM = RunRows.WORKFLOW_RUNNER

    static final String FITS = '{"values":{"summary":"A printer fire."},"confidences":{}}'

    /** Four characters a unit: what it cannot cut is about 250 units, and this ticket adds nearly 1,000. */
    static final String LONG_TICKET = '{"ticket": "' + "x" * 3900 + '"}'

    /** Every attempt numbered in the order made, so one naming another is read by that number. */
    static final String NUMBERED = """
            with numbered as (
                select attempt.*, row_number() over (order by attempt.created_at, attempt.run_step_send_attempt_id)
                           as ordinal
                  from run_step_send_attempts attempt)
            """

    @Shared
    @AutoCleanup
    EmbeddedPostgres server = EmbeddedPostgres.builder().start()

    @Shared
    int databasesMade = 0

    LibraryStore store

    /** Every process a feature wires, each shut down after it. */
    List<EngineWiring> wired = []

    /** Gates a feature holds shut, opened after every process it wired has begun to stop. */
    List<CountDownLatch> gates = []

    def setupSpec() {
        LibraryStore.template(server)
    }

    def setup() {
        store = LibraryStore.copied(server, "try_sending_" + (++databasesMade))
        TicketWorkflow.seed(store)
        producedBy("general")
    }

    def cleanup() {
        wired*.closing()
        gates*.countDown()
        wired*.executor*.destroy()
    }

    private void producedBy(String model) {
        store.session.sql("""
                update workflow_steps set producer = 'model', producer_model = ?, producer_mode = 'ordinary'
                 where workflow_step_id = ?::uuid""").params(model, SUMMARISE).update()
    }

    private EngineWiring wiring(RecordedModelCalls model, ModelCatalog catalog = DeployedModels.HELD) {
        def wiring = new EngineWiring(store, model, CodeStepsHeld.NONE, catalog)
        wired << wiring
        wiring
    }

    private static ModelCatalog taking(String model, long sentPerCall) {
        new ModelCatalog([new DeployedModel(new ModelName(model), [], sentPerCall, 4.0G, 4_000, [])])
    }

    private static RecordedModelCalls answering(CallOutcome... outcomes) {
        def model = new RecordedModelCalls()
        outcomes.each { model.scripted.answering(it) }
        model
    }

    private static CallOutcome turnedAway() {
        new CallOutcome.TurnedAway(new TurnAway("Too busy.", false))
    }

    private static CallOutcome cameBack() {
        new CallOutcome.CameBack(FITS, 321, 12, true, false)
    }

    private static RunId runId() {
        new RunId(UUID.fromString(RUN))
    }

    private static WorkflowStepId summarise() {
        new WorkflowStepId(UUID.fromString(SUMMARISE))
    }

    /** As a start does, Cat starting it, then everything handed over done. */
    private void started(EngineWiring wiring, String startedWith = TicketWorkflow.STARTED_WITH) {
        store.transactions().executeWithoutResult {
            StepRows.run(store, RUN, GROUP, 1, WORKFLOW, VERSION, CAT, startedWith)
            wiring.engine.planStarted(wiring.tree.lock(groupId(GROUP), runId()).orElseThrow())
        }
        wiring.drained()
    }

    private void pressed(EngineWiring wiring, UserId presser = CAT_USER) {
        wiring.acts.trySending(groupId(GROUP), runId(), summarise(), presser)
    }

    private CountDownLatch occupied(EngineWiring wiring) {
        def gate = wiring.occupied()
        gates << gate
        gate
    }

    /** The stop on what summarise runs let go, as a steward letting it go does, and every run it held gone on. */
    private void letGo(EngineWiring wiring) {
        store.session.sql("update entry_stops set let_go_at = now(), let_go_by = ?::uuid where entry_id = ?::uuid")
                .params(ANN, SUMMARISE_QUESTION).update()
        wiring.engine.goesOn(entryId(SUMMARISE_QUESTION))
        wiring.drained()
    }

    /** Cat no longer a member of the group, as a steward removing her does. */
    private void catRemoved() {
        store.session.sql("""
                update group_members set removed_at = now(), removed_by = ?::uuid, removal = 'removed_from_group'
                 where group_id = ?::uuid and subject_id = ?::uuid and removed_at is null""").params(ANN, GROUP, CAT)
                .update()
    }

    /** What pressing it now would be refused with, none where the step offers it. */
    private RefusalCode sendingRefused(EngineWiring wiring) {
        def read = wiring.snapshots.asRead(groupId(GROUP), runId())
        def step = read.step(summarise()).orElseThrow()
        StepAct.TRY_SENDING.refusal(StepGround.of(read, step, StepPositions.of(read, step), null))
    }

    private StepPosition positionOf(EngineWiring wiring) {
        def read = wiring.snapshots.asRead(groupId(GROUP), runId())
        StepPositions.of(read, read.step(summarise()).orElseThrow())
    }

    /** Each attempt as its number, whether it was too long, who made it, and what it holds or repeats and answers. */
    private List<String> attempts() {
        store.texts(NUMBERED + """
                select numbered.ordinal || ' ' || case when numbered.too_long then 'too_long' else 'sent' end
                       || ' by ' || numbered.created_by_kind || ' ' || numbered.created_by || ' '
                       || coalesce('repeating ' || repeated.ordinal, 'holding its payload')
                       || coalesce(' answering ' || failure.reason, '')
                  from numbered
                  left join numbered repeated on repeated.run_step_send_attempt_id = numbered.repeats_attempt_id
                  left join run_step_failures failure on failure.run_step_failure_id = numbered.answers_failure_id
                 order by numbered.ordinal""")
    }

    private List<String> holds() {
        store.texts(NUMBERED + """
                select hold.reason || ' on ' || coalesce(numbered.ordinal::text, '-') || ' '
                       || coalesce('released by ' || hold.released_by_kind || ' ' || hold.released_by, 'held')
                  from run_step_holds hold
                  left join numbered on numbered.run_step_send_attempt_id = hold.run_step_send_attempt_id
                 order by hold.created_at, hold.run_step_hold_id""")
    }

    private List<String> failures() {
        store.texts(NUMBERED + """
                select failure.reason || coalesce(' answered by ' || numbered.ordinal, '')
                  from run_step_failures failure
                  left join numbered on numbered.answers_failure_id = failure.run_step_failure_id
                 order by failure.created_at, failure.run_step_failure_id""")
    }

    /** Each call an attempt sent, as that attempt's number, how it ended, and the envelope it was sent under. */
    private List<String> calls() {
        store.texts(NUMBERED + """
                select numbered.ordinal || ' ' || coalesce(call.outcome::text, 'out') || ' ' || call.envelope_version
                  from model_calls call
                  join numbered on numbered.run_step_send_attempt_id = call.run_step_send_attempt_id
                 order by numbered.ordinal""")
    }

    private List<String> triesOf(String step) {
        store.texts("""
                select try.try_number || ' ' || case when try.ended_at is null then 'open' else 'ended' end
                  from productions try join run_steps held on held.run_step_id = try.run_step_id
                 where held.workflow_step_id = ?::uuid order by try.try_number
                """, step)
    }

    private List<String> payloads() {
        store.texts("select payload from run_step_send_attempts where payload is not null order by created_at")
    }

    def "a press writes nothing where it is made, and only the engine's thread, taking it up, writes it down as sent"() {
        given:
        def model = answering(turnedAway(), cameBack())
        def first = wiring(model)
        started(first)
        def gate = occupied(first)
        def before = store.contents()

        when: "pressed while the engine's threads are busy"
        pressed(first)

        then: "nothing is written, and the step still reads held back, offering it"
        store.contents() == before
        sendingRefused(first) == null
        model.scripted.requests.size() == 1

        when: "the engine's thread takes it up"
        gate.countDown()
        first.drained()

        then: "the hold released, the attempt the presser's and its call sent together"
        holds() == ["turned_away on 1 released by system ${SYSTEM}" as String]
        attempts() == ["1 sent by system ${SYSTEM} holding its payload" as String,
                       "2 sent by person ${CAT} repeating 1" as String]
        calls() == ["1 turned_away 2", "2 came_back 2"]
        model.scripted.requests.size() == 2
        model.madeOn == [[true, false], [true, false]]
    }

    /** The same try is sent again as it was stored, so nothing it took is read afresh and no try is spent. */
    def "a step held on a turnaway is sent again word for word, repeating the attempt holding what it sent"() {
        given:
        def model = answering(turnedAway(), cameBack())
        def first = wiring(model)
        started(first)

        when:
        pressed(first)
        first.drained()

        then: "what is sent is what the first attempt holds, under the same envelope"
        model.scripted.requests[1].sent().user() == model.scripted.requests[0].sent().user()
        model.scripted.requests[1].sent().system() == model.scripted.requests[0].sent().system()
        payloads() == [model.scripted.requests[0].sent().user()]

        and: "the second attempt the presser's, holding nothing of its own"
        attempts() == ["1 sent by system ${SYSTEM} holding its payload" as String,
                       "2 sent by person ${CAT} repeating 1" as String]
        holds() == ["turned_away on 1 released by system ${SYSTEM}" as String]
        calls() == ["1 turned_away 2", "2 came_back 2"]

        and: "the one try, now ended, and nothing failed or held again"
        triesOf(SUMMARISE) == ["1 ended"]
        failures() == []
    }

    def "a repeat turned away and sent again names the attempt holding what was sent, never the repeat before it"() {
        given:
        def model = answering(turnedAway(), turnedAway(), cameBack())
        def first = wiring(model)
        started(first)
        pressed(first)
        first.drained()

        when:
        pressed(first)
        first.drained()

        then:
        attempts() == ["1 sent by system ${SYSTEM} holding its payload" as String,
                       "2 sent by person ${CAT} repeating 1" as String,
                       "3 sent by person ${CAT} repeating 1" as String]
        holds() == ["turned_away on 1 released by system ${SYSTEM}" as String,
                    "turned_away on 2 released by system ${SYSTEM}" as String]
        calls() == ["1 turned_away 2", "2 turned_away 2", "3 came_back 2"]
        model.scripted.requests*.sent()*.user().toSet().size() == 1
    }

    /** Held again on the repeat's turnaway, the row reads every turnaway of the try, not only the one holding it. */
    def "a step held on a turnaway reads every time its try was turned away, oldest first, a pressed repeat's among them"() {
        given:
        def model = answering(turnedAway(), new CallOutcome.TurnedAway(new TurnAway("Still busy.", false)))
        def first = wiring(model)
        started(first)
        presses.times {
            pressed(first)
            first.drained()
        }

        when:
        def row = new RunSteps(store.session, new GroupRoles(store.session), first.snapshots,
                store.transactionManager()).steps(groupId(GROUP), runId(), ANN_USER).steps()[0]

        then:
        row.where().kind() == "held_back"
        row.where().reason() == "turned_away"
        row.where().turnedAway()*.said() == said

        and: "held on the newest attempt alone, nothing sent beyond the turnaways read"
        holds().findAll { it.endsWith(" held") } == ["turned_away on ${presses + 1} held" as String]
        model.scripted.requests.size() == presses + 1

        where:
        presses || said
        0       || ["Too busy."]
        1       || ["Too busy.", "Still busy."]
    }

    /**
     * Ruled: what was sent under an envelope this release no longer holds is never sent again as it was. It is built
     * afresh from what the try took, as a new attempt holding its own payload, and measured as any fresh build is.
     */
    def "a turnaway sent under an envelope no longer held is built afresh, sent where it fits and held on length where not, the old attempt left as it was"() {
        given:
        started(wiring(answering(turnedAway())), startedWith)
        store.session.sql("update model_calls set envelope_version = 1").update()
        def model = answering(cameBack())
        def redeployed = wiring(model, taking("general", limit))

        when:
        pressed(redeployed)
        redeployed.drained()

        then:
        attempts() == attemptsAfter*.toString()
        holds() == holdsAfter*.toString()
        calls() == callsAfter
        failures() == []

        and: "two payloads held, neither attempt repeating the other, and only what fits sent"
        payloads().size() == 2
        model.scripted.requests.size() == requestsMade
        model.scripted.requests*.sent()*.user().every { it == payloads().last() }

        where:
        startedWith                 | limit   || attemptsAfter                                                                                                  | holdsAfter                                                                  | callsAfter                           | requestsMade
        TicketWorkflow.STARTED_WITH | 100_000 || ["1 sent by system ${SYSTEM} holding its payload", "2 sent by person ${CAT} holding its payload"]     | ["turned_away on 1 released by system ${SYSTEM}"]                           | ["1 turned_away 1", "2 came_back 2"] | 1
        LONG_TICKET                 | 600     || ["1 sent by system ${SYSTEM} holding its payload", "2 too_long by person ${CAT} holding its payload"] | ["turned_away on 1 released by system ${SYSTEM}", "too_long on 2 held"]     | ["1 turned_away 1"]                  | 0
    }

    /** A deploy since may take less: what was turned away is measured again against the model as held now. */
    def "a turned-away try longer than the model now held takes is held on length, repeating what it would send, and not sent"() {
        given:
        def model = answering(turnedAway())
        started(wiring(model), LONG_TICKET)
        def redeployed = wiring(new RecordedModelCalls(), taking("general", 600))

        when:
        pressed(redeployed)
        redeployed.drained()

        then:
        attempts() == ["1 sent by system ${SYSTEM} holding its payload" as String,
                       "2 too_long by person ${CAT} repeating 1" as String]
        holds() == ["turned_away on 1 released by system ${SYSTEM}" as String, "too_long on 2 held"]

        and: "nothing sent, and nothing failed"
        calls() == ["1 turned_away 2"]
        failures() == []
        model.scripted.requests.size() == 1
        sendingRefused(redeployed) == null
    }

    /** The only way a released turnaway ends in a failure: the model it was sent to is one this system no longer has. */
    def "a turned-away try whose model a deploy since no longer holds fails on that, its hold released and nothing sent"() {
        given:
        def model = answering(turnedAway())
        started(wiring(model))
        def redeployed = wiring(new RecordedModelCalls(), taking("other", 100_000))

        when:
        pressed(redeployed)
        redeployed.drained()

        then:
        holds() == ["turned_away on 1 released by system ${SYSTEM}" as String]
        failures() == ["model_not_deployed"]
        attempts() == ["1 sent by system ${SYSTEM} holding its payload" as String]
        calls() == ["1 turned_away 2"]
        model.scripted.requests.size() == 1

        and: "the try still open, the step failed on it and offering it again"
        triesOf(SUMMARISE) == ["1 open"]
        sendingRefused(redeployed) == null
    }

    /** Held on length, a press measures what the try took afresh against the model as it is held now. */
    def "a step held on length is measured again: sent where it now fits, held again where not, failed where what cannot be cut does not"() {
        given:
        started(wiring(new RecordedModelCalls(), taking("general", 600)), LONG_TICKET)
        def model = answering(cameBack())
        def redeployed = wiring(model, taking("general", limit))

        when:
        pressed(redeployed)
        redeployed.drained()

        then:
        attempts() == attemptsAfter*.toString()
        holds() == holdsAfter*.toString()
        failures() == failuresAfter
        calls() == callsAfter

        and: "every attempt holds what the try took, the same each time, and only what fits is sent"
        payloads().toSet().size() == 1
        model.scripted.requests.size() == requestsMade
        model.scripted.requests*.sent()*.user().every { it == payloads().last() }

        where:
        limit   || attemptsAfter                                                                                  | holdsAfter                                                                        | failuresAfter         | callsAfter        | requestsMade
        100_000 || ["1 too_long by system ${SYSTEM} holding its payload", "2 sent by person ${CAT} holding its payload"]     | ["too_long on 1 released by system ${SYSTEM}"]                                    | []                    | ["2 came_back 2"] | 1
        700     || ["1 too_long by system ${SYSTEM} holding its payload", "2 too_long by person ${CAT} holding its payload"] | ["too_long on 1 released by system ${SYSTEM}", "too_long on 2 held"]              | []                    | []                | 0
        50      || ["1 too_long by system ${SYSTEM} holding its payload"]                                              | ["too_long on 1 released by system ${SYSTEM}"]                                    | ["uncuttable_length"] | []                | 0
    }

    def "a step failed on its model not held is sent afresh once a deploy holds it, the attempt answering that failure"() {
        given:
        producedBy("absent")
        started(wiring(new RecordedModelCalls()))
        def model = answering(cameBack())
        def redeployed = wiring(model, taking("absent", 100_000))

        when:
        pressed(redeployed)
        redeployed.drained()

        then:
        failures() == ["model_not_deployed answered by 1"]
        attempts() == ["1 sent by person ${CAT} holding its payload answering model_not_deployed" as String]
        calls() == ["1 came_back 2"]
        model.scripted.requests*.sent()*.user() == payloads()

        and: "the try it was on is the one sent, and nothing held"
        triesOf(SUMMARISE) == ["1 ended"]
        holds() == []
    }

    def "a step failed on its model not held and pressed while it still is not fails again, leaving nothing sent"() {
        given:
        producedBy("absent")
        def first = wiring(new RecordedModelCalls())
        started(first)

        when:
        pressed(first)
        first.drained()

        then: "a second failure, the first answered by nothing, and no attempt"
        failures() == ["model_not_deployed", "model_not_deployed"]
        attempts() == []
        calls() == []

        and: "still offered, on the same try"
        sendingRefused(first) == null
        triesOf(SUMMARISE) == ["1 open"]
    }

    /** The later failure supersedes the earlier, so the attempt answering it ends both and the try goes on to its end. */
    def "a failure written again by a press and answered by a later one ends every failure on the try, so the step moves on"() {
        given:
        producedBy("absent")
        def first = wiring(new RecordedModelCalls())
        started(first)
        pressed(first)
        first.drained()
        def model = answering(cameBack())
        def redeployed = wiring(model, taking("absent", 100_000))

        when:
        pressed(redeployed)
        redeployed.drained()

        then:
        failures() == ["model_not_deployed", "model_not_deployed answered by 1"]
        attempts() == ["1 sent by person ${CAT} holding its payload answering model_not_deployed" as String]
        calls() == ["1 came_back 2"]
        triesOf(SUMMARISE) == ["1 ended"]
        model.scripted.requests.size() == 1

        and: "its summary, which never stands by itself, waiting on a review, and Try sending no longer offered"
        positionOf(redeployed) instanceof StepPosition.AwaitingReview
        sendingRefused(redeployed) == RefusalCode.STEP_MOVED_ON
    }

    def "a step failed on what cannot be cut is sent afresh once a deploy takes it, the attempt answering that failure"() {
        given:
        started(wiring(new RecordedModelCalls(), taking("general", 50)))
        def model = answering(cameBack())
        def redeployed = wiring(model)

        when:
        pressed(redeployed)
        redeployed.drained()

        then:
        failures() == ["uncuttable_length answered by 1"]
        attempts() == ["1 sent by person ${CAT} holding its payload answering uncuttable_length" as String]
        calls() == ["1 came_back 2"]
        triesOf(SUMMARISE) == ["1 ended"]
        holds() == []
    }

    def "a stop made after the press and before the engine's thread takes it up leaves everything as it was, and sends nothing"() {
        given:
        def model = answering(turnedAway())
        def first = wiring(model)
        started(first)
        def gate = occupied(first)
        pressed(first)
        RunRows.stopped(store, RUN, CAT)
        def before = store.contents()

        when:
        gate.countDown()
        first.drained()

        then:
        store.contents() == before
        holds() == ["turned_away on 1 held"]
        model.scripted.requests.size() == 1
    }

    /** Ruled: nothing is sent while what the step runs is stopped, and letting it go sends nothing by itself either. */
    def "a step held or failed on what a send may settle is refused while what it runs is stopped, and let go it stands as it was, still to be pressed"() {
        given:
        producedBy(produced)
        def model = answering(*outcomes)
        def first = wiring(model)
        started(first)
        store.stopped(SUMMARISE_QUESTION, ANN)
        def before = store.contents()

        when:
        pressed(first)

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.ENTRY_STOPPED
        store.contents() == before

        when:
        letGo(first)

        then:
        holds() == holdsAfter
        failures() == failuresAfter
        attempts().size() == requestsMade
        model.scripted.requests.size() == requestsMade
        sendingRefused(first) == null

        where:
        produced  | outcomes       || holdsAfter                | failuresAfter          | requestsMade
        "general" | [turnedAway()] || ["turned_away on 1 held"] | []                     | 1
        "absent"  | []             || []                        | ["model_not_deployed"] | 0
    }

    def "a ceiling reached when the engine's thread takes up a press stops the run instead, writing and sending nothing else"() {
        given:
        def model = answering(turnedAway())
        def first = wiring(model)
        started(first)
        store.session.sql("update workflow_versions set ceiling = 100 where entry_version_id = ?::uuid").params(VERSION)
                .update()
        RunRows.called(store, RUN, "came_back", 100, 40)
        def before = store.contents("run_stops")

        when: "not stopped when pressed, so the press is taken"
        pressed(first)
        first.drained()

        then: "stopped by the system for its own ceiling"
        store.texts("select created_by_kind || ' ' || created_by || ' ' || ceiling_run_id from run_stops") ==
                ["system ${SYSTEM} ${RUN}" as String]

        and: "the hold standing, nothing attempted, and nothing sent"
        store.contents("run_stops") == before
        holds() == ["turned_away on 1 held"]
        model.scripted.requests.size() == 1
    }

    /** Ruled: the engine's thread asks again whether the presser may, and one who no longer may has pressed nothing. */
    def "a press whose presser is removed from the group before the engine's thread takes it up writes and sends nothing, and the step still offers it"() {
        given:
        def model = answering(turnedAway(), cameBack())
        def first = wiring(model)
        started(first)
        def gate = occupied(first)
        pressed(first)
        catRemoved()
        def before = store.contents()

        when:
        gate.countDown()
        first.drained()

        then:
        store.contents() == before
        holds() == ["turned_away on 1 held"]
        model.scripted.requests.size() == 1
        sendingRefused(first) == null
    }

    /** The press was only ever handed to the lost process's threads, so nothing of it outlives that process. */
    def "a press lost with its process leaves the step held and offering it, and the process after it sends a press made there"() {
        given:
        def killed = wiring(answering(turnedAway()))
        started(killed)
        occupied(killed)
        pressed(killed)
        def model = answering(cameBack())
        def after = wiring(model)

        when:
        after.started()
        after.drained()

        then: "nothing of the lost press written or sent"
        holds() == ["turned_away on 1 held"]
        attempts() == ["1 sent by system ${SYSTEM} holding its payload" as String]
        model.scripted.requests == []
        sendingRefused(after) == null

        when: "pressed again where it now runs"
        pressed(after)
        after.drained()

        then:
        attempts() == ["1 sent by system ${SYSTEM} holding its payload" as String,
                       "2 sent by person ${CAT} repeating 1" as String]
        calls() == ["1 turned_away 2", "2 came_back 2"]
        model.scripted.requests.size() == 1
    }

    def "a second press once the first was sent finds the step moved on, and sends nothing more"() {
        given:
        def model = answering(turnedAway(), cameBack())
        def first = wiring(model)
        started(first)
        pressed(first)
        first.drained()
        def before = store.contents()

        when:
        pressed(first)

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.STEP_MOVED_ON

        and:
        store.contents() == before
        first.drained()
        model.scripted.requests.size() == 2
    }

    /** Neither press wrote anything, so both are taken; the engine's thread, judging each under the lock, sends once. */
    def "two presses taken before the engine's thread comes to either make one attempt and one call"() {
        given:
        def model = answering(turnedAway(), cameBack())
        def first = wiring(model)
        started(first)
        def gate = occupied(first)
        pressed(first)
        pressed(first, TicketWorkflow.ANN_USER)

        when:
        gate.countDown()
        first.drained()

        then:
        attempts().size() == 2
        calls() == ["1 turned_away 2", "2 came_back 2"]
        holds() == ["turned_away on 1 released by system ${SYSTEM}" as String]
        failures() == []

        and: "made once, by whichever press the engine's thread came to first"
        model.scripted.requests.size() == 2
        attempts()[1] in ["2 sent by person ${CAT} repeating 1" as String, "2 sent by person ${ANN} repeating 1" as String]
    }

    /** Each press names the failure it was made on; the first answers it with another, so the second finds it gone. */
    def "two presses taken on a model still not held write one failure between them, and nothing is sent"() {
        given:
        producedBy("absent")
        def first = wiring(new RecordedModelCalls())
        started(first)
        def gate = occupied(first)
        pressed(first)
        pressed(first, TicketWorkflow.ANN_USER)

        when:
        gate.countDown()
        first.drained()

        then:
        failures() == ["model_not_deployed", "model_not_deployed"]
        attempts() == []
        calls() == []
        holds() == []
        sendingRefused(first) == null
    }

    /** Refused before anything is handed over, so nothing reaches the engine's thread at all. */
    def "a press is refused, writing and handing over nothing, where #why"() {
        given:
        def model = answering(turnedAway())
        def first = wiring(model)
        arranged(why, first)
        def before = store.contents()

        when:
        pressed(first, presser)

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == code

        and:
        first.drained()
        store.contents() == before
        model.scripted.requests.size() == requestsMade

        where:
        why                                           | presser  || code                                     | requestsMade
        "the run is stopped"                          | CAT_USER || RefusalCode.RUN_STOPPED                  | 1
        "what the step runs is stopped"               | CAT_USER || RefusalCode.ENTRY_STOPPED                | 1
        "it was held on a stop before it was asked"   | CAT_USER || RefusalCode.TRY_SENDING_NOT_OFFERED      | 0
        "the presser holds no role in the group"      | DAN_USER || RefusalCode.GROUP_NOT_IN_VIEW            | 1
    }

    private void arranged(String why, EngineWiring first) {
        switch (why) {
            case "the run is stopped":
                started(first)
                RunRows.stopped(store, RUN, CAT)
                break
            case "what the step runs is stopped":
                started(first)
                store.stopped(SUMMARISE_QUESTION, ANN)
                break
            case "it was held on a stop before it was asked":
                store.stopped(SUMMARISE_QUESTION, ANN)
                started(first)
                break
            case "the presser holds no role in the group":
                started(first)
                break
            default:
                throw new IllegalArgumentException(why)
        }
    }
}
