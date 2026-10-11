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

/** Each style rule written for an element styled through `sx`, with the media it is written under, if any. */
export function styleRulesOf(
  element: Element,
): { readonly media: string | null; readonly rule: CSSStyleRule }[] {
  const generated = styledClassOf(element);
  return [...document.styleSheets]
    .flatMap((sheet) => [...sheet.cssRules])
    .flatMap((rule): { media: string | null; rule: CSSRule }[] =>
      rule instanceof CSSMediaRule
        ? [...rule.cssRules].map((inner) => ({
            media: rule.conditionText,
            rule: inner,
          }))
        : [{ media: null, rule }],
    )
    .filter(
      (each): each is { media: string | null; rule: CSSStyleRule } =>
        each.rule instanceof CSSStyleRule &&
        each.rule.selectorText.startsWith(`.${generated}`),
    );
}

/** Whether a list is laid out with a label beside its value at any width. */
export function laysPairsBeside(list: Element): boolean {
  return new RegExp(
    `\\.${styledClassOf(list)}\\{[^}]*grid-template-columns:max-content`,
  ).test(writtenRules());
}
