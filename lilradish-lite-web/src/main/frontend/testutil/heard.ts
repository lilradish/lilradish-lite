import { onTestFinished } from "vitest";

/**
 * Watches for `words` being put into the document, and reports each time they
 * were — each text node that came to hold them — with whether anything above
 * them was aria-hidden at that moment.
 *
 * The watcher hears of a change after the task that made it, by which time
 * an ancestor's aria-hidden may have changed again; every such change heard
 * in the same batch after the words went in is taken back before judging.
 *
 * It sits in the source tree because the specs do: this project has no
 * separate test root to put it in. Only a spec may import it.
 */
export function appearancesOf(
  words: string,
): () => { readonly hidden: boolean }[] {
  const seen: { readonly hidden: boolean }[] = [];
  const counted = new WeakSet<Text>();
  const take = (records: MutationRecord[]) => {
    records.forEach((record, at) => {
      if (record.type === "attributes") {
        return;
      }
      const put =
        record.type === "characterData"
          ? [record.target]
          : [...record.addedNodes];
      for (const text of put.flatMap(textsIn)) {
        if (!counted.has(text) && text.data.includes(words)) {
          counted.add(text);
          seen.push({ hidden: hiddenAt(text, records.slice(at + 1)) });
        }
      }
    });
  };
  const watcher = new MutationObserver(take);
  watcher.observe(document.body, {
    subtree: true,
    childList: true,
    characterData: true,
    attributeFilter: ["aria-hidden"],
    attributeOldValue: true,
  });
  onTestFinished(() => watcher.disconnect());
  return () => {
    take(watcher.takeRecords());
    return [...seen];
  };
}

/** Whether an ancestor was aria-hidden before the changes `later` records. */
function hiddenAt(text: Text, later: readonly MutationRecord[]): boolean {
  for (
    let element = text.parentElement;
    element !== null;
    element = element.parentElement
  ) {
    const changed = later.find(
      (record) => record.type === "attributes" && record.target === element,
    );
    const then =
      changed === undefined
        ? element.getAttribute("aria-hidden")
        : changed.oldValue;
    if (then === "true") {
      return true;
    }
  }
  return false;
}

function textsIn(node: Node): Text[] {
  if (node instanceof Text) {
    return [node];
  }
  const texts: Text[] = [];
  const walker = document.createTreeWalker(node, NodeFilter.SHOW_TEXT);
  for (let at = walker.nextNode(); at !== null; at = walker.nextNode()) {
    texts.push(at as Text);
  }
  return texts;
}
