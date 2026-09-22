package org.lilradish.lite.domain.retrieval;

import static java.util.Objects.requireNonNull;

import org.libprunus.core.log.annotation.DoNotLog;
import org.lilradish.lite.domain.wire.Digest;

/**
 * Material that came from outside the trust boundary, held so that the only way to put it in front
 * of a model is as labelled data.
 *
 * <p>The defence is shape rather than inspection: no accessor hands the raw string to a caller who
 * could concatenate it into a template, so the text cannot become an instruction to a later step by
 * being passed along. That is why this is a class with a private constructor rather than a record.
 *
 * <p>The same holds of a log line, where it is {@link DoNotLog} that does the work.
 */
public final class UntrustedText {

    /**
     * 128 bits. The derivation alone does not defend this: an attacker who controls the whole text
     * can grind for one that contains a prefix of its own hash, which at 48 bits is hours on a single
     * card. The length is what puts that fixed point out of reach.
     */
    private static final int FENCE_LENGTH = 32;

    @DoNotLog
    private final String text;

    private UntrustedText(String text) {
        this.text = text;
    }

    /**
     * Being fenceable is a construction invariant rather than something a later call discovers: UTF-8
     * encodes an unpaired surrogate as {@code '?'}, so a text carrying one would fence and fingerprint
     * as a different text does.
     */
    public static UntrustedText of(String text) {
        requireNonNull(text, "UntrustedText text must not be null");
        requireFenceable(text);
        return new UntrustedText(text);
    }

    /**
     * The only way out. The fence token is the head of the text's own hash, so text that could close
     * its own fence and go on writing instructions would have to contain a prefix of that hash, which
     * at {@link #FENCE_LENGTH} hex characters is out of reach rather than merely expensive. Derived
     * also makes it stable across calls, which exact-match keying over an assembled request would
     * depend on.
     *
     * <p>A fence is unguessable only to whoever has not seen the text, so blocks built from one
     * author's material do not fence each other; a nonce per assembly would, losing the stability.
     *
     * <p>The label is interpolated into both markers, so it is a second way to write outside the
     * block and is whitelisted rather than filtered. A blacklist of the characters that close a
     * marker today would be the same mistake one layer down.
     */
    public String asDataBlock(String citationLabel) {
        requireLabel(citationLabel);
        String marker = citationLabel + " fence=" + digest().substring(0, FENCE_LENGTH);
        return "[data " + marker + "]\n" + text + "\n[/data " + marker + "]";
    }

    /** One-way, so handing it out hands nothing back: it is what a fingerprint is checked against. */
    public String digest() {
        return Digest.sha256Hex(text);
    }

    public int length() {
        return text.length();
    }

    /**
     * Declared against the rule that no accessor hands the text out, and admitted under it: this
     * answers a boolean about a value the caller already holds. Without it every record carrying one
     * — a chunk, a retrieved candidate, a source document — compares by identity, and deduplicating
     * by content is what this domain does.
     */
    @Override
    public boolean equals(Object other) {
        return other instanceof UntrustedText that && text.equals(that.text);
    }

    /** The other half of that contract, and it hands out an int. */
    @Override
    public int hashCode() {
        return text.hashCode();
    }

    private static void requireFenceable(String text) {
        int index = 0;
        while (index < text.length()) {
            int codePoint = text.codePointAt(index);
            if (codePoint < Character.MIN_SUPPLEMENTARY_CODE_POINT && Character.isSurrogate((char) codePoint)) {
                throw new IllegalArgumentException("UntrustedText must not contain an unpaired surrogate");
            }
            index += Character.charCount(codePoint);
        }
    }

    /**
     * The offending character is reported by position and code point rather than echoed: the label is
     * the caller's text, and a message carrying it verbatim is the same escape one layer out, where a
     * line break forges a log line instead of closing a marker. The position is an index into the
     * string rather than a count of characters, the two parting company outside the basic plane.
     */
    private static void requireLabel(String citationLabel) {
        requireNonNull(citationLabel, "UntrustedText citationLabel must not be null");
        if (citationLabel.isEmpty()) {
            throw new IllegalArgumentException("UntrustedText citationLabel must not be empty");
        }
        int index = 0;
        while (index < citationLabel.length()) {
            int codePoint = citationLabel.codePointAt(index);
            if (!isAlphanumeric(codePoint)) {
                throw new IllegalArgumentException(String.format(
                        "UntrustedText citationLabel must contain only letters and digits,"
                                + " but the one at index %d is U+%04X",
                        index, codePoint));
            }
            index += Character.charCount(codePoint);
        }
    }

    /**
     * ASCII-only is deliberate: Unicode letters admit homoglyphs, so Character.isLetterOrDigit would
     * let two labels a reader cannot tell apart anchor two different claims.
     */
    private static boolean isAlphanumeric(int codePoint) {
        return (codePoint >= 'a' && codePoint <= 'z')
                || (codePoint >= 'A' && codePoint <= 'Z')
                || (codePoint >= '0' && codePoint <= '9');
    }
}
