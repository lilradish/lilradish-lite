import { prepareProseTrees } from "./prosePreparation";

/** A dedicated read owns one request; terminating it also discards its unfinished layout. */
self.onmessage = (
  event: MessageEvent<{
    readonly texts: readonly string[];
    readonly formattedTexts: readonly string[];
  }>,
) => {
  try {
    self.postMessage({
      prepared: prepareProseTrees(event.data.texts, event.data.formattedTexts),
    });
  } catch {
    self.postMessage({ failed: true });
  }
};
