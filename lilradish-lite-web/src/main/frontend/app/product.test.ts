import indexHtml from "../index.html?raw";
import { describe, expect, it } from "vitest";

import { PRODUCT_NAME } from "./product";

describe("PRODUCT_NAME", () => {
  /**
   * The one copy no import can reach. The document is markup the bundle is
   * injected into, so its title is written out by hand — and a product renamed
   * everywhere a module could see would leave the browser tab, the bookmark and
   * the window title still saying the old name.
   */
  it("is what the document the bundle mounts into is titled", () => {
    expect(indexHtml).toContain(`<title>${PRODUCT_NAME}</title>`);
  });

  /**
   * And it is not a sentence in disguise. A name that found its way into the
   * catalogue is a name a translation would be asked to translate.
   */
  it("is held nowhere a sentence is held", async () => {
    const { APP_EN, LIB_EN } = await import("../i18n/en");

    expect(Object.values({ ...LIB_EN, ...APP_EN })).not.toContain(PRODUCT_NAME);
  });
});
