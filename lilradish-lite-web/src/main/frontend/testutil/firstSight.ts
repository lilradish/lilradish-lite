import { onTestFinished } from "vitest";

/**
 * Looked for in the microtask each change is reported in, so a state lasting only until a transition's timer
 * fires is still seen; read after an awaited query instead, it is gone whenever that query is slow.
 */
export function firstSight<T>(look: () => T | undefined): () => T | undefined {
  let found: T | undefined;
  const watcher = new MutationObserver(() => {
    found = look();
    if (found !== undefined) {
      watcher.disconnect();
    }
  });
  watcher.observe(document.body, {
    subtree: true,
    childList: true,
    characterData: true,
    attributes: true,
  });
  onTestFinished(() => watcher.disconnect());
  return () => found;
}
