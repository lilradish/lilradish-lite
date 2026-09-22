package org.lilradish.lite.domain.wire;

import static java.util.Objects.requireNonNull;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The one hash used for anything compared across processes or across restarts — a content
 * fingerprint, a cache key, an answer fingerprint. None of those can be {@link Object#hashCode()},
 * and none may differ between two callers computing the same thing.
 */
public final class Digest {

    private Digest() {}

    public static String sha256Hex(String value) {
        requireNonNull(value, "Digest value must not be null");
        requirePaired(value);
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is mandatory in every JRE", impossible);
        }
        return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * {@code getBytes(UTF_8)} encodes an unpaired surrogate as {@code '?'}, so two different inputs
     * would fingerprint identically — fatal for a value compared across processes.
     */
    private static void requirePaired(String value) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isHighSurrogate(character)
                    && index + 1 < value.length()
                    && Character.isLowSurrogate(value.charAt(index + 1))) {
                index++;
            } else if (Character.isSurrogate(character)) {
                throw new IllegalArgumentException("Digest value must not contain an unpaired surrogate");
            }
        }
    }
}
