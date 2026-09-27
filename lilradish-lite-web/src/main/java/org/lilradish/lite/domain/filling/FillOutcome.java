package org.lilradish.lite.domain.filling;

/** What a sent value read against a declaration came to: not its shape at all, values that do not fit, or kept. */
public sealed interface FillOutcome permits FillOutcome.Unshaped, FillProblems, FilledFields {

    /**
     * The members or the JSON types are not the declaration's: a field unknown or left out, a value of a kind no
     * field holds, none where many are held, or none or a wrong kind inside many no more than the most; past the
     * most, no element is read and they are too many. No page's controls send it, so nothing is named.
     */
    record Unshaped() implements FillOutcome {}
}
