package org.lilradish.lite.domain.declaration;

import static java.util.Objects.requireNonNull;

import org.jspecify.annotations.Nullable;

/**
 * What a half's fields ask at each depth, which is its host's rule and the same for every field at one
 * depth: the first level, and every level below it.
 */
public record Demands(Kind first, Kind below) {

    public enum Kind {
        GIVEN,
        STANDS
    }

    public Demands {
        requireNonNull(first, "Demands first must not be null");
        requireNonNull(below, "Demands below must not be null");
        if (below == Kind.STANDS) {
            throw new IllegalArgumentException("Demands let only a field no other holds stand");
        }
    }

    /** A question's: every field says whether it must be given, and what it gives back stands as well. */
    public static Demands ofQuestion(DeclarationSide side) {
        requireNonNull(side, "Demands side must not be null");
        return side == DeclarationSide.TAKES
                ? new Demands(Kind.GIVEN, Kind.GIVEN)
                : new Demands(Kind.STANDS, Kind.GIVEN);
    }

    /**
     * A workflow's, and a route's: every field says whether it must be given, and nothing it gives back stands
     * here, having stood already where it was made.
     */
    public static Demands ofWorkflow(DeclarationSide side) {
        requireNonNull(side, "Demands side must not be null");
        return new Demands(Kind.GIVEN, Kind.GIVEN);
    }

    public Kind at(boolean firstLevel) {
        return firstLevel ? first : below;
    }

    public boolean admits(Demand demand, boolean firstLevel) {
        return kindOf(demand) == at(firstLevel);
    }

    /**
     * The demand a field at this depth asks, built from what was stored or sent for it: whether it must be
     * given, and how it stands where that is asked. What the depth does not ask is not read.
     */
    public Demand demandAt(
            boolean firstLevel, @Nullable Boolean mustBe, @Nullable FieldStanding standing, @Nullable Integer floor) {
        boolean given = requireNonNull(mustBe, "Demands a field says whether it must be given");
        return switch (at(firstLevel)) {
            case GIVEN -> new Demand.Given(given);
            case STANDS -> new Demand.Stands(given, standing, floor);
        };
    }

    public static Kind kindOf(Demand demand) {
        return switch (demand) {
            case Demand.Given ignored -> Kind.GIVEN;
            case Demand.Stands ignored -> Kind.STANDS;
        };
    }
}
