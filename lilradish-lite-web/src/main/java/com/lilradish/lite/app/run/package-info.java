/**
 * A run as the store holds it and as a member reads and acts on it, each answered within the one group owning
 * it; every act on a run takes its tree's lock first, through {@link com.lilradish.lite.app.run.RunTree}.
 */
@NullMarked
package com.lilradish.lite.app.run;

import org.jspecify.annotations.NullMarked;
