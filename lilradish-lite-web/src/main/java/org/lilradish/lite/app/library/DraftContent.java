package org.lilradish.lite.app.library;

/** What one kind of entry writes into a draft, inside the change {@link Drafts} has made on it. */
@FunctionalInterface
interface DraftContent<T> {

    T write(OpenDraft draft);
}
