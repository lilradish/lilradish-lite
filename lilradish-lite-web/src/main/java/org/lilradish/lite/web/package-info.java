/**
 * The edge this application is reached through, and what every request crossing it passes: the door
 * that settles who is calling, the gate that reads what the selected handler asks of them, the two
 * declarations a handler says that with, and the handling that answers every address the reader's
 * side routes for itself with the document that routes it. All four are this package's because the
 * prefix they turn on is one word, and a second copy of it anywhere is a way for two of them to
 * disagree about which addresses are guarded.
 *
 * <p>Also what a page asks for, shaped by the page rather than by the model behind it. One request
 * here fills one screen, composing across as many features as that screen shows, so a browser never
 * has to know which feature answered what or how to put the pieces together.
 *
 * <p>A resource-shaped endpoint belongs to the feature that owns it, not here: this package exists
 * for the shape that changes when a screen is redesigned, and nothing else should have to change
 * with it. What such a handler needs from here it imports — the two declarations, and the caller the
 * door let through — rather than being moved in beside them.
 */
@NullMarked
package org.lilradish.lite.web;

import org.jspecify.annotations.NullMarked;
