package org.lilradish.lite.web;

import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * An identifier this system minted, as a caller sends it back: in its standard 36-character form, in
 * either case. The shorter spellings the runtime's parser also accepts are not that form and name
 * nothing, so no identifier travels under two spellings that differ by more than case.
 */
public final class MintedIdentifiers {

    private static final Pattern STANDARD_FORM =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private MintedIdentifiers() {}

    /** Absent where what was sent is not an identifier in that form. */
    public static Optional<UUID> read(String spelled) {
        return STANDARD_FORM.matcher(spelled).matches() ? Optional.of(UUID.fromString(spelled)) : Optional.empty();
    }
}
