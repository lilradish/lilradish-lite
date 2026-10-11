package com.lilradish.lite.domain.run

import static com.lilradish.lite.domain.run.fixture.Runs.PERSON
import static com.lilradish.lite.domain.run.fixture.Runs.STARTED
import static com.lilradish.lite.domain.run.fixture.Runs.codeStep
import static com.lilradish.lite.domain.run.fixture.Runs.key
import static com.lilradish.lite.domain.run.fixture.Runs.person
import static com.lilradish.lite.domain.run.fixture.Runs.position
import static com.lilradish.lite.domain.run.fixture.Runs.question
import static com.lilradish.lite.domain.run.fixture.Runs.questionTaking
import static com.lilradish.lite.domain.run.fixture.Runs.run
import static com.lilradish.lite.domain.run.fixture.Runs.standing
import static com.lilradish.lite.domain.run.fixture.Runs.stepId
import static com.lilradish.lite.domain.run.fixture.Runs.stop
import static com.lilradish.lite.domain.run.fixture.Runs.takes
import static com.lilradish.lite.domain.run.fixture.Runs.text
import static com.lilradish.lite.domain.run.fixture.Runs.tidy
import static com.lilradish.lite.domain.run.fixture.Runs.unstarted
import static com.lilradish.lite.domain.run.fixture.Runs.workflowStep

import com.lilradish.lite.domain.codestep.CodeError
import com.lilradish.lite.domain.codestep.CodeErrorReason
import com.lilradish.lite.domain.declaration.FieldName
import com.lilradish.lite.domain.identity.SubjectId
import com.lilradish.lite.domain.workflow.Binding
import com.lilradish.lite.domain.workflow.BindingSource
import com.lilradish.lite.domain.workflow.Pointer
import com.lilradish.lite.domain.workflow.StepProducer
import spock.lang.Specification

class StepGroundSpec extends Specification {

    static final SubjectId SOMEBODY_ELSE = new SubjectId(key(9003))

    def "a writer ground requires the resource state its acts are judged against"() {
        when:
        new StepGround(null, false)

        then:
        def refused = thrown(NullPointerException)
        refused.message == "StepGround state must not be null"
    }

    def "where a step stands is read from its run: the stop on the tree, a stop on its workflow or on what it runs, and its producer"() {
        given:
        def step = unstarted(question(1, person(), 1), pinned ? stop(STARTED) : null)
        def running = run([step], runStopped, workflow ? stop(STARTED) : null)

        when:
        def ground = StepGround.of(running, step, position("owed"), PERSON)

        then:
        ground == new StepGround(new StepGround.State(position("owed"), runStopped, pinned || workflow,
                StepProducer.PERSON, false, null, false), false)
        ground.state().is(ground.state())

        where:
        [runStopped, pinned, workflow] << [[true, false], [true, false], [true, false]].combinations()
    }

    def "a step running what produces nothing of its own names no producer"() {
        given:
        def step = unstarted(workflowStep(1))

        expect:
        StepGround.of(run([step]), step, position("not started"), PERSON).state().producer() == null
    }

    def "code may be asked again exactly where the release holds its code step and lets it run again"() {
        given:
        def step = unstarted(codeStep(1, 2, released))

        expect:
        StepGround.of(run([step]), step, position("owed"), PERSON).state().codeMayRunAgain() == again

        where:
        released                                                   || again
        tidy(true)                                                 || true
        tidy(false)                                                || false
        null                                                       || false
    }

    def "a question's step is never one code may be asked again on, nor one code gives otherwise"() {
        given:
        def step = unstarted(question(1, person(), 1))

        when:
        def ground = StepGround.of(run([step]), step, position("owed"), PERSON)

        then:
        !ground.state().codeMayRunAgain()
        ground.codeGivesOtherwise() == null
    }

    /**
     * Worked out only where a person may make a try, the only place it could refuse anything; carried as the fault
     * found, so the refusal names the step reading it from the one judgement.
     */
    def "code gives otherwise where a later step reads what the release no longer gives back, and only where a try is owed"() {
        given:
        def code = unstarted(codeStep(1, 2, tidy(false, [], [standing(gives)])))
        def reading = unstarted(questionTaking(2, takes([text("answer")]),
                [new Binding(key(800), Pointer.parse("answer"), new BindingSource.StepOutput(stepId(1).value(),
                        Pointer.parse("answer")))]))
        def found = new CodeError.Fault(CodeErrorReason.GIVES_OTHERWISE, [new FieldName("answer")], null,
                new CodeError.StepReads(stepId(2).value()))

        when:
        def ground = StepGround.of(run([code, reading]), code, position(at), PERSON)

        then:
        ground.codeGivesOtherwise() == (otherwise ? found : null)
        ground.state().is(ground.state())
        otherwise ? ground.codeGivesOtherwise().is(ground.state().codeGivesOtherwise()) : ground.state().codeGivesOtherwise() == null

        where:
        gives     | at                            || otherwise
        "answer"  | "owed"                        || false
        "receipt" | "owed"                        || true
        "receipt" | "failed with its tries spent" || true
        "receipt" | "running its next try"        || false
        "receipt" | "done"                        || false
    }

    def "the reader produced what waits on review exactly where the person who produced it is the reader"() {
        given:
        def step = unstarted(question(1, person(), 1))

        expect:
        StepGround.of(run([step]), step, position(at), reader).readerProduced() == produced

        where:
        at                           | reader        || produced
        "awaiting a person's review" | PERSON        || true
        "awaiting a person's review" | SOMEBODY_ELSE || false
        "awaiting a person's review" | null          || false
        "owed"                       | PERSON        || false
    }

    /** As the engine judges it, so that nothing is offered of a code step it would send nothing of. */
    def "a step runs a code step the release does not hold exactly where it runs one and the release holds none of it"() {
        given:
        def step = unstarted(planned)

        expect:
        StepGround.of(run([step]), step, position("awaiting the model's review, turned away"), PERSON).state().unreleased() ==
                unreleased

        where:
        planned                         || unreleased
        codeStep(1, 2, null)            || true
        codeStep(1, 2, tidy(true))      || false
        question(1, person(), 1)        || false
        workflowStep(1)                 || false
    }
}
