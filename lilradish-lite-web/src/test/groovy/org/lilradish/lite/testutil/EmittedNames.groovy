package org.lilradish.lite.testutil

import java.util.regex.Pattern

/**
 * The shape the build gives every file it emits beside the document: a name, a hyphen, a hash of
 * eight characters in the url-safe base64 alphabet the build is configured with, and the one
 * extension the bundle emits.
 *
 * <p>Shape and length are all a name can be read for. A hash may be letters alone, so nothing here
 * asks for a digit; and a name shaped like a hash by accident passes, which nothing short of
 * building twice could tell.
 */
final class EmittedNames {

    static final Pattern HASHED = ~/[\w-]+-[A-Za-z0-9_-]{8}\.js/

    private EmittedNames() {}
}
