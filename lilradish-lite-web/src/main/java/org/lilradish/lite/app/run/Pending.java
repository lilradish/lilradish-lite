package org.lilradish.lite.app.run;

/**
 * What a transaction planning a run left to be done outside it, handed to the engine's threads once it commits
 * and never done where it was planned: nothing is handed over by one that rolls back.
 */
sealed interface Pending permits PendingSend, PendingCode {}
