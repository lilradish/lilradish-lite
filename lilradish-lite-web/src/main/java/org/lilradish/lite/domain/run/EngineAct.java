package org.lilradish.lite.domain.run;

import static java.util.Objects.requireNonNull;

/** What a run does next by itself, if anything: one act at a time, each worked out afresh. */
public sealed interface EngineAct
        permits EngineAct.StartTry,
                EngineAct.Send,
                EngineAct.Review,
                EngineAct.HoldOnStop,
                EngineAct.HoldNotHeld,
                EngineAct.ReleaseHold,
                EngineAct.Nothing {

    /** @param number the try asked for, which is the step's next */
    record StartTry(PlannedStep step, int number) implements EngineAct {

        public StartTry {
            requireNonNull(step, "EngineAct.StartTry step must not be null");
            if (number < 1) {
                throw new IllegalArgumentException("EngineAct.StartTry number must be positive: " + number);
            }
        }
    }

    /**
     * The open try a model produces, not yet sent: written down as sent, or held or failed where it cannot be.
     *
     * @param number the try sent, which is the step's newest
     */
    record Send(PlannedStep step, int number) implements EngineAct {

        public Send {
            requireNonNull(step, "EngineAct.Send step must not be null");
            if (number < 1) {
                throw new IllegalArgumentException("EngineAct.Send number must be positive: " + number);
            }
        }
    }

    /**
     * The values of a try waiting on the model the step names, nothing yet sent for them: written down as sent to
     * it, or waiting on a person or failed where they cannot be.
     *
     * @param number the try reviewed, which is the step's newest
     */
    record Review(PlannedStep step, int number) implements EngineAct {

        public Review {
            requireNonNull(step, "EngineAct.Review step must not be null");
            if (number < 1) {
                throw new IllegalArgumentException("EngineAct.Review number must be positive: " + number);
            }
        }
    }

    /** A stop on what the step runs, or on the workflow, holds the try it would have made. */
    record HoldOnStop(PlannedStep step) implements EngineAct {

        public HoldOnStop {
            requireNonNull(step, "EngineAct.HoldOnStop step must not be null");
        }
    }

    /** A code step the running release does not hold holds the try the step would have made, spending none. */
    record HoldNotHeld(PlannedStep step) implements EngineAct {

        public HoldNotHeld {
            requireNonNull(step, "EngineAct.HoldNotHeld step must not be null");
        }
    }

    /** What held the step, a stop or a release not holding its code step, is gone, so the step goes on by itself. */
    record ReleaseHold(PlannedStep step) implements EngineAct {

        public ReleaseHold {
            requireNonNull(step, "EngineAct.ReleaseHold step must not be null");
        }
    }

    /** A person, a stop, something already out, or the end owns the next move. */
    record Nothing() implements EngineAct {}
}
