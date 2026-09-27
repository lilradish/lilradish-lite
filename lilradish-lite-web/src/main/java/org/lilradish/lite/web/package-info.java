/**
 * The edge this application is reached through, and what every request crossing it passes. Whatever
 * turns on this application's prefix is this package's: the prefix is one word, and a second copy of
 * it anywhere is a way for two readings to disagree about which addresses are guarded.
 *
 * <p>That is the door asking who is calling and the gate asking what they may do, the declarations a
 * handler says what it asks with and the checks refusing to start while one says it wrongly, the one
 * strict reading of a query string, a body and an identifier sent back, the headers and the trace every
 * answer carries, the outlet every refusal leaves by, and the document answering the addresses the
 * reader's side routes for itself.
 *
 * <p>No handler under the prefix lives here. Each belongs to the feature that owns what it answers, and
 * imports what it needs from here rather than being moved in beside it.
 */
@NullMarked
package org.lilradish.lite.web;

import org.jspecify.annotations.NullMarked;
