/**
 * The style rules a page has written, read as text: jsdom lays nothing out and
 * answers no container query, so a layout decided by one is asserted from the
 * rule written for it rather than from where anything landed.
 *
 * It sits in the source tree because the specs do: this project has no
 * separate test root to put it in. Only a spec may import it — it is kept out
 * of the coverage report and no build input names it, and a production module
 * importing it would quietly undo both.
 */
export function writtenRules(): string {
  return [...document.querySelectorAll("style")]
    .map((each) => each.textContent ?? "")
    .join("\n");
}

/** The class the styles were written under, for an element styled through `sx`. */
export function styledClassOf(element: Element): string {
  return [...element.classList].find((name) => name.startsWith("css-"))!;
}

/** The width, in pixels, the wide one of two columns keeps where the narrow one stands beside it. */
export function wideKeptBesideNarrow(): number {
  const written = writtenRules();
  const [, frame, narrow] =
    /@container \(min-width: (\d+)px\)\{\.[\w-]+\{grid-template-columns:minmax\(0, 1fr\) (\d+)px;/.exec(
      written,
    )!;
  const [, gap] = /grid-template-columns:minmax\(0, 1fr\);gap:(\d+)px;/.exec(
    written,
  )!;
  return Number(frame) - Number(gap) - Number(narrow);
}

/** Whether a list is laid out with a label beside its value at any width. */
export function laysPairsBeside(list: Element): boolean {
  return new RegExp(
    `\\.${styledClassOf(list)}\\{[^}]*grid-template-columns:max-content`,
  ).test(writtenRules());
}
