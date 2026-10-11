package com.lilradish.lite.app.run.fixture

import static com.lilradish.lite.domain.run.fixture.Runs.code
import static com.lilradish.lite.domain.run.fixture.Runs.codeStep
import static com.lilradish.lite.domain.run.fixture.Runs.gives
import static com.lilradish.lite.domain.run.fixture.Runs.key
import static com.lilradish.lite.domain.run.fixture.Runs.model
import static com.lilradish.lite.domain.run.fixture.Runs.nested
import static com.lilradish.lite.domain.run.fixture.Runs.person
import static com.lilradish.lite.domain.run.fixture.Runs.pinned
import static com.lilradish.lite.domain.run.fixture.Runs.question
import static com.lilradish.lite.domain.run.fixture.Runs.questionTaking
import static com.lilradish.lite.domain.run.fixture.Runs.run
import static com.lilradish.lite.domain.run.fixture.Runs.started
import static com.lilradish.lite.domain.run.fixture.Runs.stepId
import static com.lilradish.lite.domain.run.fixture.Runs.takes
import static com.lilradish.lite.domain.run.fixture.Runs.text
import static com.lilradish.lite.domain.run.fixture.Runs.value
import static com.lilradish.lite.domain.run.fixture.Runs.yielded

import com.lilradish.lite.domain.declaration.Declaration
import com.lilradish.lite.domain.declaration.DeclarationSide
import com.lilradish.lite.domain.declaration.Demands
import com.lilradish.lite.domain.run.PlannedStep
import com.lilradish.lite.domain.run.RunId
import com.lilradish.lite.domain.run.RunInTree
import com.lilradish.lite.domain.run.RunSnapshot
import com.lilradish.lite.domain.run.RunStepId
import com.lilradish.lite.domain.run.RunnableWorkflow
import com.lilradish.lite.domain.run.StepRuns
import com.lilradish.lite.domain.run.StepSnapshot
import com.lilradish.lite.domain.workflow.Binding
import com.lilradish.lite.domain.workflow.BindingSource
import com.lilradish.lite.domain.workflow.Pointer
import com.lilradish.lite.domain.workflow.StepId
import com.lilradish.lite.domain.workflow.StepProducer

/** A child taking and returning person parts beside a model's secret, with its real bindings and run tree. */
final class ChildComposite {

    private ChildComposite() {}

    static Scenario of(boolean codeNow) {
        def nameSource = started(question(1, model(), 1),
                [yielded(1, StepProducer.MODEL, [value(41, "answer", false, "Ari")])])
        def idSource = started(question(2, person(), 1),
                [yielded(1, StepProducer.PERSON, [value(42, "answer", false, "123")])])
        def childTakes = takes([nested("customer", [text("id"), text("name"), text("type"),
                                                     text("size"), text("secret")])])
        def childBinding = new Binding(key(1241), Pointer.parse("customer"),
                new BindingSource.WorkflowInput(Pointer.parse("customer")))
        def childQuestion = questionTaking(1, childTakes, [childBinding])
        def childStep = started(codeNow
                ? codeStep(1, 1, null, code(), [childBinding])
                : new PlannedStep(childQuestion.id(), childQuestion.order(), childQuestion.name(), childQuestion.label(),
                        childQuestion.runs(), model(), childQuestion.tries(), childQuestion.reviewer(),
                        childQuestion.bindings()), [])
        def template = run([childStep])
        def childGives = new Declaration(DeclarationSide.GIVES, Demands.ofWorkflow(DeclarationSide.GIVES),
                [nested("customer", [text("id"), text("name"), text("type"), text("size"), text("secret")])])
        def childOutputs = ["id", "name", "type", "size", "secret"].collect { name ->
            new Binding(key(1260 + ["id", "name", "type", "size", "secret"].indexOf(name)),
                    Pointer.parse("customer.${name}" as String),
                    new BindingSource.WorkflowInput(Pointer.parse("customer.${name}" as String)))
        }
        def childWorkflow = new RunnableWorkflow(childTakes, childGives, [:], childOutputs, [childStep.planned()])
        def child = new RunSnapshot(new RunId(key(1242)), template.root(), template.group(), template.version(),
                false, null, null, childWorkflow, [childStep])
        def parentBindings = ["id", "name", "type", "size", "secret"].collect { name ->
            new Binding(key(1270 + ["id", "name", "type", "size", "secret"].indexOf(name)),
                    Pointer.parse("customer.${name}" as String),
                    new BindingSource.StepOutput(stepId(name == "secret" ? 1 : 2).value(),
                            Pointer.parse("answer")))
        }
        def parentPlan = new PlannedStep(stepId(3), 3, new StepId("step_3"), null,
                new StepRuns.Workflow(pinned(3), child.workflow().gives()), null, null, null, parentBindings)
        def parentStep = new StepSnapshot(parentPlan, null, new RunStepId(key(1245)), null, [], [],
                new StepSnapshot.Beneath(child, null), [:])
        def context = RunInTree.root(run([nameSource, idSource, parentStep])).beneath(parentStep)
        new Scenario(context, parentStep, nameSource, idSource)
    }

    record Scenario(RunInTree context, StepSnapshot parentStep, StepSnapshot nameSource, StepSnapshot idSource) {}
}
