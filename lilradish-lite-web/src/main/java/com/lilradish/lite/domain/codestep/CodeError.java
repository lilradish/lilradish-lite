package com.lilradish.lite.domain.codestep;

import static java.util.Objects.requireNonNull;

import com.lilradish.lite.domain.declaration.FieldName;
import com.lilradish.lite.domain.inference.ForeignProse;
import com.lilradish.lite.domain.text.CharacterStanding;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.libprunus.core.log.annotation.DoNotLog;

/** Code's own words are kept only when it threw; a system-owned fault never stands in for those words. */
public sealed interface CodeError permits CodeError.Fault, CodeError.Said {

    /**
     * @param path the field of the code step it is about, from the first level down: for a reason found in what came
     *     back, no further than the outermost many holding what is wrong, and for a member nothing declares, the field
     *     holding it, empty at the first level; at the try's start, the field of what it takes or gives back that no
     *     longer matches; empty exactly where the reason is about no field
     * @param member the member nothing declares, given exactly where that is the reason
     * @param readBy what reads the field that no longer matches, given exactly where that is the reason
     */
    record Fault(
            CodeErrorReason reason,
            List<FieldName> path,
            @DoNotLog @Nullable UndeclaredMember member,
            @Nullable ReadBy readBy)
            implements CodeError {

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
                        case GAVE_NOTHING, FAILED_ON_THIS_SIDE, SAID_NOTHING, FILE_TOTAL_TOO_LARGE -> path.isEmpty();
                        case NOT_DECLARED -> true;
                        case NOTHING_GIVEN,
                                NOT_ITS_KIND,
                                LINE_BREAK,
                                TOO_LONG,
                                TOO_MANY,
                                NOT_A_TERM,
                                UNKEEPABLE,
                                TOO_LONG_TO_KEEP,
                                FILE_TYPE_UNUSABLE,
                                FILE_NAME_UNUSABLE,
                                FILE_TOO_LARGE,
                                TAKES_A_LIST_NOT_HERE,
                                GIVES_A_LIST_NOT_HERE,
                                TAKES_OTHERWISE,
                                GIVES_OTHERWISE -> !path.isEmpty();
                    };
            if (!placed) {
                throw new IllegalArgumentException("CodeError.Fault " + reason.published()
                        + (path.isEmpty() ? " names the field it is about" : " is about no field, and names none"));
            }
        }
    }

    /**
     * @param cut whether the name was cut already, retained when reconstructing a stored name
     */
    record UndeclaredMember(String name, boolean cut) {

        private static final int LONGEST = 64;

        /* Also built from the store's name, already cleaned and cut, which cleaning leaves as it is; a cut carried in
         * must stay, as a name cut to 64 characters no longer shows it. */
        public UndeclaredMember {
            requireNonNull(name, "CodeError.UndeclaredMember name must not be null");
            StringBuilder kept = new StringBuilder(Math.min(name.length(), 2 * LONGEST));
            int count = 0;
            int index = 0;
            while (index < name.length()) {
                int codePoint = name.codePointAt(index);
                index += Character.charCount(codePoint);
                if (CharacterStanding.of(codePoint).refusedInAVisibleLine()) {
                    continue;
                }
                if (count == LONGEST) {
                    cut = true;
                    break;
                }
                kept.appendCodePoint(codePoint);
                count++;
            }
            if (cut && count != LONGEST) {
                throw new IllegalArgumentException("CodeError.UndeclaredMember is cut only at 64 characters");
            }
            name = kept.toString();
        }
    }

    /** The detail is cleaned and cut as prose is. */
    record Said(ForeignProse detail) implements CodeError {

        public Said {
            requireNonNull(detail, "CodeError.Said detail must not be null");
        }
    }

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
