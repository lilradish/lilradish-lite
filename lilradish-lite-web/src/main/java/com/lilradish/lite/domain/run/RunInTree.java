package com.lilradish.lite.domain.run;

import static java.util.Objects.requireNonNull;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.libprunus.core.log.annotation.DoNotLog;

/** A run and the path by which its root reaches it, kept outside immutable snapshots. */
public record RunInTree(
        @DoNotLog RunSnapshot run,
        @DoNotLog @Nullable RunInTree above,
        @DoNotLog @Nullable StepSnapshot from) {

    public RunInTree {
        requireNonNull(run, "RunInTree run must not be null");
        if ((above == null) != (from == null)) {
            throw new IllegalArgumentException("A run beneath must name its parent step");
        }
        if (above == null && !run.run().equals(run.root())) {
            throw new IllegalArgumentException("A tree context starts at its root");
        }
        if (above != null) {
            if (!run.root().equals(above.run().root())
                    || from.beneath() == null
                    || !from.beneath().run().run().equals(run.run())
                    || !above.run().steps().contains(from)) {
                throw new IllegalArgumentException("A run beneath must follow its parent step");
            }
        }
    }

    public static RunInTree root(RunSnapshot run) {
        return new RunInTree(run, null, null);
    }

    public RunInTree beneath(StepSnapshot step) {
        requireNonNull(step, "RunInTree step must not be null");
        if (!run.steps().contains(step) || step.beneath() == null) {
            throw new IllegalArgumentException("The step does not start a run beneath this run");
        }
        return new RunInTree(step.beneath().run(), this, step);
    }

    public Optional<RunInTree> find(RunId wanted) {
        requireNonNull(wanted, "RunInTree wanted must not be null");
        Deque<SearchNode> pending = new ArrayDeque<>();
        pending.push(new SearchNode(run, null, null));
        while (!pending.isEmpty()) {
            SearchNode current = pending.pop();
            if (current.run.run().equals(wanted)) {
                Deque<StepSnapshot> path = new ArrayDeque<>();
                for (SearchNode at = current; at.above != null; at = at.above) {
                    path.push(requireNonNull(at.from));
                }
                RunInTree found = this;
                while (!path.isEmpty()) {
                    found = found.beneath(path.pop());
                }
                return Optional.of(found);
            }
            for (StepSnapshot step : current.run.steps()) {
                if (step.beneath() != null) {
                    pending.push(new SearchNode(step.beneath().run(), current, step));
                }
            }
        }
        return Optional.empty();
    }

    @Override
    public String toString() {
        return "RunInTree[run=" + run.run().value() + "]";
    }

    private static final class SearchNode {

        private final RunSnapshot run;
        private final @Nullable SearchNode above;
        private final @Nullable StepSnapshot from;

        private SearchNode(RunSnapshot run, @Nullable SearchNode above, @Nullable StepSnapshot from) {
            this.run = run;
            this.above = above;
            this.from = from;
        }
    }
}
