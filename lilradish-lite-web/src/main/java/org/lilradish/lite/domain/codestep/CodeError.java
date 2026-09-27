package org.lilradish.lite.domain.codestep;

import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.libprunus.core.log.annotation.DoNotLog;
import org.lilradish.lite.domain.declaration.FieldName;
import org.lilradish.lite.domain.inference.ForeignProse;
import org.lilradish.lite.domain.text.CharacterStanding;

/**
 * Why a try of code went wrong: a reason of this system's, with where it found it, or the code's own words where it
 * threw. Never both, so what the code said is kept only where the code said it.
 */
public sealed interface CodeError permits CodeError.Fault, CodeError.Said {

    /**
     * @param path the field of the code step it is about, from the first level down: for a reason found in what came
     *     back, no further than the outermost many holding what is wrong, and for a member nothing declares, the field
     *     holding it, empty at the first level; at the try's start, the field of what it takes or gives back that no
     *     longer matches; empty exactly where the reason is about no field
     * @param member the name of a member nothing declares, as the code wrote it, cleaned for one line and cut past
     *     64 characters, marked so; given exactly where that is the reason
     * @param readBy what reads the field that no longer matches, given exactly where that is the reason
     */
    record Fault(
            CodeErrorReason reason,
            List<FieldName> path,
            @DoNotLog @Nullable String member,
            @Nullable ReadBy readBy) implements CodeError {

        private static final int LONGEST_MEMBER = 64;

        private static final String CUT = "…";

        public Fault {
            requireNonNull(reason, "CodeError.Fault reason must not be null");
            path = List.copyOf(requireNonNull(path, "CodeError.Fault path must not be null"));
            if ((reason == CodeErrorReason.NOT_DECLARED) != (member != null)) {
                throw new IllegalArgumentException("CodeError.Fault names a member exactly where one is not declared");
            }
            if ((reason == CodeErrorReason.GIVES_OTHERWISE) != (readBy != null)) {
                throw new IllegalArgumentException("CodeError.Fault names what reads the field"
                        + " exactly where what it gives back no longer matches");
            }
            boolean placed =
                    switch (reason) {
                        case GAVE_NOTHING, FAILED_ON_THIS_SIDE, SAID_NOTHING -> path.isEmpty();
                        case NOT_DECLARED -> true;
                        case NOTHING_GIVEN,
                                NOT_ITS_KIND,
                                TOO_LONG,
                                TOO_MANY,
                                NOT_A_TERM,
                                UNKEEPABLE,
                                TOO_LONG_TO_KEEP,
                                TAKES_A_LIST_NOT_HERE,
                                GIVES_A_LIST_NOT_HERE,
                                TAKES_OTHERWISE,
                                GIVES_OTHERWISE -> !path.isEmpty();
                    };
            if (!placed) {
                throw new IllegalArgumentException("CodeError.Fault " + reason.published()
                        + (path.isEmpty() ? " names the field it is about" : " is about no field, and names none"));
            }
            member = member == null ? null : oneLine(member);
        }

        /* Read back from the store it is cleaned again, and must come out as it went in: the mark stands past the
         * 64th character, so a name cut once is cut the same way twice. */
        private static String oneLine(String written) {
            StringBuilder kept = new StringBuilder(Math.min(written.length(), 2 * LONGEST_MEMBER));
            int count = 0;
            int index = 0;
            while (index < written.length()) {
                int codePoint = written.codePointAt(index);
                index += Character.charCount(codePoint);
                if (CharacterStanding.of(codePoint).refusedInAVisibleLine()) {
                    continue;
                }
                if (count == LONGEST_MEMBER) {
                    return kept.append(CUT).toString();
                }
                kept.appendCodePoint(codePoint);
                count++;
            }
            return kept.toString();
        }
    }

    /** What the code said went wrong where it threw, cleaned and cut as prose is. */
    record Said(ForeignProse detail) implements CodeError {

        public Said {
            requireNonNull(detail, "CodeError.Said detail must not be null");
        }
    }

    /** What reads a field of a code step's that no longer matches: a later step, or an output of the workflow. */
    sealed interface ReadBy permits StepReads, OutputReads {}

    /** @param step the step of the version that reads it, by its identifier there */
    record StepReads(UUID step) implements ReadBy {

        public StepReads {
            requireNonNull(step, "CodeError.StepReads step must not be null");
        }
    }

    /** @param field the field of what the workflow gives back that it is read into, from the first level down */
    record OutputReads(List<FieldName> field) implements ReadBy {

        public OutputReads {
            field = List.copyOf(requireNonNull(field, "CodeError.OutputReads field must not be null"));
            if (field.isEmpty()) {
                throw new IllegalArgumentException("CodeError.OutputReads names the field it is read into");
            }
        }
    }
}
