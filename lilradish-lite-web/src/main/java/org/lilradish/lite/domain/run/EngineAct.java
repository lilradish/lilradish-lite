package org.lilradish.lite.domain.run;

import static java.util.Objects.requireNonNull;

import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.lilradish.lite.domain.registry.EntryVersionId;
import org.lilradish.lite.domain.workflow.Producer;

/** What a run does next by itself, if anything: one act at a time, each worked out afresh. */
public sealed interface EngineAct
        permits EngineAct.StartTry,
                EngineAct.Send,
                EngineAct.Review,
                EngineAct.HoldOnStop,
                EngineAct.HoldNotHeld,
                EngineAct.ReleaseHold,
                EngineAct.StartBeneath,
                EngineAct.FailUnclaimed,
                EngineAct.FailTooDeep,
                EngineAct.Nothing {

    RunId run();

    /** @param number the try asked for, which is the step's next */
    record StartTry(RunId run, PlannedStep step, int number) implements EngineAct {

        public StartTry {
            requireNonNull(run, "EngineAct.StartTry run must not be null");
            requireNonNull(step, "EngineAct.StartTry step must not be null");
            if (number < 1) {
                throw new IllegalArgumentException("EngineAct.StartTry number must be positive: " + number);
            }
        }
    }

    /**
     * The open try a model produces, not yet sent: written down as sent, or held or failed where it cannot be.
     *
     * @param aTry the production sent, which is the step's newest
     */
    record Send(RunId run, PlannedStep step, ProductionId aTry) implements EngineAct {

        public Send {
            requireNonNull(run, "EngineAct.Send run must not be null");
            requireNonNull(step, "EngineAct.Send step must not be null");
            requireNonNull(aTry, "EngineAct.Send aTry must not be null");
        }
    }

    /**
     * The values of a try waiting on the model the step names, nothing yet sent for them: written down as sent to
     * it, or waiting on a person or failed where they cannot be.
     *
     * @param aTry the production reviewed, which is the step's newest
     */
    record Review(RunId run, PlannedStep step, ProductionId aTry) implements EngineAct {

        public Review {
            requireNonNull(run, "EngineAct.Review run must not be null");
            requireNonNull(step, "EngineAct.Review step must not be null");
            requireNonNull(aTry, "EngineAct.Review aTry must not be null");
        }
    }

    /** A stop on what the step runs, or on the workflow, holds the step's next act. */
    record HoldOnStop(RunId run, PlannedStep step) implements EngineAct {

        public HoldOnStop {
            requireNonNull(run, "EngineAct.HoldOnStop run must not be null");
            requireNonNull(step, "EngineAct.HoldOnStop step must not be null");
        }
    }

    /** An absent code step or search way, the step itself or an input's source, holds production with no try spent. */
    record HoldNotHeld(RunId run, PlannedStep step, HoldRecord.@Nullable SourceRecord source) implements EngineAct {

        public HoldNotHeld {
            requireNonNull(run, "EngineAct.HoldNotHeld run must not be null");
            requireNonNull(step, "EngineAct.HoldNotHeld step must not be null");
            if (source == null && !(step.runs() instanceof StepRuns.Code || step.runs() instanceof StepRuns.Search)) {
                throw new IllegalArgumentException("An absent release hold names the step itself or its source");
            }
            if (source != null && !(step.producer() instanceof Producer.Model)) {
                throw new IllegalArgumentException("An absent source code step holds only model production");
            }
        }
    }

    /** What held the step, a stop or a release not holding its code step, is gone, so the step goes on by itself. */
    record ReleaseHold(RunId run, PlannedStep step) implements EngineAct {

        public ReleaseHold {
            requireNonNull(run, "EngineAct.ReleaseHold run must not be null");
            requireNonNull(step, "EngineAct.ReleaseHold step must not be null");
        }
    }

    /** Start the pinned workflow or chosen route case beneath this step. */
    record StartBeneath(
            RunId run,
            PlannedStep step,
            EntryVersionId version,
            @Nullable UUID routeCase) implements EngineAct {

        public StartBeneath {
            requireNonNull(run, "EngineAct.StartBeneath run must not be null");
            requireNonNull(step, "EngineAct.StartBeneath step must not be null");
            requireNonNull(version, "EngineAct.StartBeneath version must not be null");
            if ((step.runs() instanceof StepRuns.Route) != (routeCase != null)) {
                throw new IllegalArgumentException("A route start must name its chosen case");
            }
            if (!(step.runs() instanceof StepRuns.Workflow || step.runs() instanceof StepRuns.Route)) {
                throw new IllegalArgumentException("Only a workflow or route starts a run beneath");
            }
        }
    }

    /** No route case claimed the standing discriminator; no try is spent. */
    record FailUnclaimed(
            RunId run, PlannedStep step, @Nullable String said) implements EngineAct {

        public FailUnclaimed {
            requireNonNull(run, "EngineAct.FailUnclaimed run must not be null");
            requireNonNull(step, "EngineAct.FailUnclaimed step must not be null");
            if (!(step.runs() instanceof StepRuns.Route)) {
                throw new IllegalArgumentException("Only a route can fail without a claimed value");
            }
        }

        @Override
        public String toString() {
            return "FailUnclaimed[run=" + run.value() + ", step=" + step.id().value() + "]";
        }
    }

    /** The run this step would start would go deeper than runs may go, so the step fails instead; no try is spent. */
    record FailTooDeep(RunId run, PlannedStep step) implements EngineAct {

        public FailTooDeep {
            requireNonNull(run, "EngineAct.FailTooDeep run must not be null");
            requireNonNull(step, "EngineAct.FailTooDeep step must not be null");
            if (!(step.runs() instanceof StepRuns.Workflow || step.runs() instanceof StepRuns.Route)) {
                throw new IllegalArgumentException("Only a workflow or route starts a run beneath to go too deep");
            }
        }
    }

    /** A person, a stop, something already out, or the end owns the next move. */
    record Nothing(RunId run) implements EngineAct {

        public Nothing {
            requireNonNull(run, "EngineAct.Nothing run must not be null");
        }
    }
}
