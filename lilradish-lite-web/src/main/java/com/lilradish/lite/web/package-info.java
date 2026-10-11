/**
 * The edge this application is reached through, and what every request crossing it passes. Whatever
 * turns on this application's prefix is this package's: the prefix is one word, and a second copy of
 * it anywhere is a way for two readings to disagree about which addresses are guarded.
 *
 * <p>No handler under the prefix lives here. Each belongs to the feature that owns what it answers, and
 * imports what it needs from here rather than being moved in beside it.
 */
@NullMarked
package com.lilradish.lite.web;

import org.jspecify.annotations.NullMarked;
