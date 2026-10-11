import { buttonClasses } from "@mui/material/Button";
import { ThemeProvider } from "@mui/material/styles";
import { act, render, screen, waitFor, within } from "@testing-library/react";
import { userEvent } from "@testing-library/user-event";
import { useState, type ReactNode } from "react";
import { describe, expect, it, vi } from "vitest";

import { useAction } from "../../lib/request/useAction";
import { theme } from "../../lib/theme/theme";
import { RoleLine } from "./RoleLine";

function themed({ children }: { readonly children: ReactNode }) {
  return <ThemeProvider theme={theme}>{children}</ThemeProvider>;
}

/**
 * One line over a real action whose answer is whether the role is held now,
 * held as a panel holds it: the line is drawn again as the answer says.
 */
function reading({
  holds,
  offered = true,
}: {
  readonly holds: boolean;
  readonly offered?: boolean;
}) {
  const asked = vi.fn((now: boolean) => Promise.resolve(!now));
  function Holder() {
    const [held, setHeld] = useState(holds);
    const changing = useAction<boolean>(setHeld);
    return (
      <ul>
        <RoleLine
          said="Operator"
          holds={held}
          change={
            offered
              ? {
                  action: changing,
                  waiting: false,
                  act: () => asked(held),
                }
              : null
          }
        />
      </ul>
    );
  }
  render(<Holder />, { wrapper: themed });
  return { asked };
}

function line(): HTMLElement {
  return screen.getByRole("listitem");
}

describe("RoleLine", () => {
  /** Withdrawing warns; granting undoes nothing and is drawn as any other. */
  it.each([
    [
      true,
      "Held",
      "Withdraw Operator",
      buttonClasses.colorWarning,
      buttonClasses.colorPrimary,
    ],
    [
      false,
      "Not held",
      "Grant Operator",
      buttonClasses.colorPrimary,
      buttonClasses.colorWarning,
    ],
  ])(
    "says a role held=%s is %s, offering %s outlined in its own colour and in no other",
    (holds, said, control, colour, otherColour) => {
      reading({ holds });

      const button = within(line()).getByRole("button");

      expect(within(line()).getByText(/^(Held|Not held)$/)).toHaveTextContent(
        new RegExp(`^${said}$`),
      );
      expect(button).toHaveAccessibleName(control);
      expect(button).toHaveClass(buttonClasses.outlined, colour);
      expect(button).not.toHaveClass(otherColour);
      expect(within(line()).getAllByRole("button")).toHaveLength(1);
    },
  );

  it.each([true, false])(
    "draws the control held=%s in the small size, at the end of the line after the role's name",
    (holds) => {
      reading({ holds });

      const button = within(line()).getByRole("button");
      const drawn = getComputedStyle(button);

      expect(drawn.minHeight).toBe("32px");
      expect(drawn.fontSize).toBe("13px");
      expect(line().firstElementChild).toHaveTextContent(/^Operator$/);
      expect(line().lastElementChild).toContainElement(button);
    },
  );

  it.each([true, false])(
    "offers no control, and still says whether it is held=%s, where the reader is offered none",
    (holds) => {
      reading({ holds, offered: false });

      expect(within(line()).queryAllByRole("button")).toEqual([]);
      expect(line()).toHaveTextContent(
        holds ? "OperatorHeld" : "OperatorNot held",
      );
    },
  );

  /** Nothing asks again before it is done: the control it turned into is the next press. */
  it.each([
    [true, "Withdraw Operator", "Grant Operator"],
    [false, "Grant Operator", "Withdraw Operator"],
  ])(
    "keeps the keyboard on the control pressed while held=%s, as %s turns into %s",
    async (holds, pressed, becomes) => {
      const { asked } = reading({ holds });
      await act(async () => {
        within(line()).getByRole("button", { name: pressed }).focus();
      });

      await userEvent.keyboard("{Enter}");

      const turned = await screen.findByRole("button", { name: becomes });
      expect(document.activeElement).toBe(turned);
      expect(screen.queryByRole("button", { name: pressed })).toBeNull();
      await waitFor(() => expect(turned).not.toHaveAttribute("aria-disabled"));
      expect(asked).toHaveBeenCalledExactlyOnceWith(holds);
    },
  );
});
