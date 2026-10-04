// It sits in the source tree because the specs do; only a spec may import it.

import { act, within } from "@testing-library/react";

/**
 * The control a step's message draws its state as, named by that state's word and described by the message's own
 * title; failing where the message draws its state as no control, rather than finding some other one shut.
 */
export function stateControl(message: HTMLElement, state: string): HTMLElement {
  const title = within(message).getAllByRole("heading", { level: 2 })[0]!;
  const control = within(message).getByRole("button", { name: state });
  if (control.getAttribute("aria-describedby") !== title.id) {
    throw new Error(`"${state}" is no control of this message's state`);
  }
  return control;
}

/** The account of where a message's step is, once the control its state is drawn as is pressed open. */
export function accountOpened(
  message: HTMLElement,
  state: string,
): HTMLElement {
  const control = stateControl(message, state);
  act(() => control.click());
  return document.getElementById(control.getAttribute("aria-controls")!)!;
}
