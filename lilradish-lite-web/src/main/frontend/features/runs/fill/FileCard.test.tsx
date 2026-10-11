import { ThemeProvider } from "@mui/material/styles";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { createIntl } from "react-intl";
import { afterEach, describe, expect, it, vi } from "vitest";

import type { ReadField } from "../../../api/filling";
import { theme } from "../../../lib/theme/theme";
import { CurrentFileRunContext } from "./CurrentFileRun";
import { FileCard, fileEndingOf } from "./FileCard";

const reader = vi.hoisted(() => ({ language: "en" }));
vi.mock(import("../../../i18n/intl"), async (original) => ({
  ...(await original()),
  get readersIntl() {
    return createIntl({ locale: reader.language, defaultLocale: "en" });
  },
}));
afterEach(() => {
  reader.language = "en";
  vi.restoreAllMocks();
});

const FILE = {
  id: "0000000f-0000-4000-8000-000000000001",
  name: "Imaginary claim.pdf",
  size: 5,
};
const FIELD: ReadField = {
  name: "attachment",
  kind: "file",
  mustBeGiven: true,
  types: [".pdf"],
  maximumBytes: 1_000,
};
const apart = (text: string) =>
  `${String.fromCodePoint(0x2068)}${text}${String.fromCodePoint(0x2069)}`;

function drawing(
  name = FILE.name,
  size = FILE.size,
  field?: ReadField,
  run = true,
) {
  return render(
    <ThemeProvider theme={theme}>
      <CurrentFileRunContext.Provider
        value={
          run ? { groupId: "imaginary group", runId: "imaginary/run" } : null
        }
      >
        <FileCard file={{ ...FILE, name, size }} field={field} />
      </CurrentFileRunContext.Provider>
    </ThemeProvider>,
  ).container;
}

describe("FileCard", () => {
  it("keeps the complete declared ending beside the ellipsised name and reveals the full marked filename on keyboard focus", async () => {
    const user = userEvent.setup();
    const name = "Imaginary unusually long dispatch attachment.TAR.GZ";
    const drawn = drawing(name, 1024, { ...FIELD, types: [".gz", ".tar.gz"] });
    const link = screen.getByRole("link", {
      name: `Download ${apart(name)}, 1 KB`,
    });
    const spans = [
      ...link.querySelectorAll("bdi:not([hidden] bdi) > span > span"),
    ];
    expect(spans.map((part) => part.textContent)).toEqual([
      "Imaginary unusually long dispatch attachment",
      ".TAR.GZ",
    ]);
    expect(spans[0]).toHaveStyle({
      textOverflow: "ellipsis",
      whiteSpace: "nowrap",
    });
    expect(spans[1]).toHaveStyle({ flexShrink: "0" });
    expect(drawn.firstElementChild).toHaveStyle({
      width: "240px",
      maxWidth: "100%",
      padding: "10px 12px",
      borderRadius: "10px",
      borderColor: "#d6e0e4",
    });
    await user.tab();
    expect(link).toHaveFocus();
    expect(await screen.findByRole("tooltip")).toHaveTextContent(name);
    expect(link).toHaveAttribute("download");
  });
  it("makes the whole metadata card one native keyboard-reachable download without fetching or embedding the file", async () => {
    const fetch = vi.spyOn(globalThis, "fetch");
    const drawn = drawing();
    const link = screen.getByRole("link", {
      name: `Download ${apart(FILE.name)}, 5 B`,
    });

    expect(link.tagName).toBe("A");
    expect(link).toHaveAttribute(
      "href",
      "/api/groups/imaginary%20group/runs/imaginary%2Frun/files/0000000f-0000-4000-8000-000000000001",
    );
    expect(link).toHaveAttribute("download");
    expect(link).toHaveStyle({ display: "block" });
    expect(link.querySelector("bdi:not([hidden] bdi)")?.textContent).toBe(
      FILE.name,
    );
    expect(link).not.toHaveTextContent("PDF");
    expect(drawn.querySelectorAll("a")).toHaveLength(1);
    expect(
      drawn.querySelector("button, iframe, img, embed, object"),
    ).toBeNull();
    expect(fetch).not.toHaveBeenCalled();
    await userEvent.setup().tab();
    expect(link).toHaveFocus();
  });

  it.each([
    ["archive.TAR.GZ", [".gz", ".tar.gz"], ".tar.gz"],
    ["ARCHIVE.TaR.Gz", [".tar.gz", ".gz"], ".tar.gz"],
    ["archive.tar.gz", [".gz"], ".gz"],
    ["archive.tar.gz", [".pdf"], ".gz"],
    ["claim.PDF", [".pdf"], ".pdf"],
    ["claim.pdf.backup", [".pdf"], ".backup"],
    ["claim.\u212A", [".k"], ".\u212A"],
    ["README", [".pdf"], undefined],
    ["claim.", [".pdf"], "."],
    [".pdf", [".pdf"], ".pdf"],
  ] as const)(
    "draws %s by its name and size without a type label, always downloading",
    (name, types, ending) => {
      const drawn = drawing(name, 5, { ...FIELD, types });
      const link = screen.getByRole("link", {
        name: `Download ${apart(name)}, 5 B`,
      });

      const visibleNames = [...link.querySelectorAll("bdi")].filter(
        (each) => each.closest("[hidden]") === null,
      );
      expect(visibleNames.map((each) => each.textContent)).toEqual([name]);
      expect(fileEndingOf(name, { ...FIELD, types })).toEqual({
        ending,
      });
      expect(link).toHaveAttribute("download");
      expect(link).not.toHaveAttribute("target");
      expect(drawn.querySelector("iframe, embed, object, img")).toBeNull();
    },
  );

  it.each([
    undefined,
    { name: "attachment", kind: "text", mustBeGiven: false, longest: 20 },
  ] as const)(
    "uses only the filename ending without a current file declaration %j",
    (field) => {
      drawing("archive.tar.gz", 5, field);
      const link = screen.getByRole("link");
      expect(
        [...link.querySelectorAll("bdi")]
          .filter((each) => each.closest("[hidden]") === null)
          .map((each) => each.textContent),
      ).toEqual(["archive.tar.gz"]);
      expect(link).toHaveAttribute("download");
      expect(link).not.toHaveTextContent("TAR.GZ");
    },
  );

  it("uses each current declaration when a standalone card is rerendered with the same file", () => {
    const file = { ...FILE, name: "Imaginary receipt.TAR.GZ" };
    const view = (types: readonly string[]) => (
      <ThemeProvider theme={theme}>
        <CurrentFileRunContext.Provider
          value={{ groupId: "group-now", runId: "run-now" }}
        >
          <FileCard file={file} field={{ ...FIELD, types }} />
        </CurrentFileRunContext.Provider>
      </ThemeProvider>
    );
    const { container, rerender } = render(view([".gz"]));
    for (const [types, ending] of [
      [[".gz"], ".gz"],
      [[".gz", ".tar.gz"], ".tar.gz"],
      [[".pdf"], ".GZ"],
    ] as const) {
      rerender(view(types));
      const link = screen.getByRole("link", {
        name: `Download ${apart(file.name)}, 5 B`,
      });
      expect(
        [...link.querySelectorAll("bdi")]
          .filter((each) => each.closest("[hidden]") === null)
          .map((each) => each.textContent),
      ).toEqual([file.name]);
      expect(fileEndingOf(file.name, { ...FIELD, types })).toEqual({
        ending,
      });
      expect(link).toHaveAttribute(
        "href",
        "/api/groups/group-now/runs/run-now/files/0000000f-0000-4000-8000-000000000001",
      );
      expect(link).toHaveAttribute("download");
      expect(screen.getAllByRole("link")).toEqual([link]);
      expect(container.querySelector("iframe, img, embed, object")).toBeNull();
    }
  });

  it.each([
    [0, "0 B"],
    [1, "1 B"],
    [1_023, "1,023 B"],
    [1_024, "1 KB"],
    [1_280, "1.3 KB"],
    [1_535, "1.5 KB"],
    [1_048_575, "1,024 KB"],
    [1_048_576, "1 MB"],
    [1_342_177, "1.3 MB"],
    [1_073_741_824, "1 GB"],
    [1_342_177_280, "1.3 GB"],
    [1_099_511_627_776, "1,024 GB"],
    [9_007_199_254_740_991, "8,388,608 GB"],
  ])(
    "formats %i bytes in the greatest eligible unit, capped at GB, identically for the visible size and download name",
    (bytes, size) => {
      drawing(FILE.name, bytes);
      const link = screen.getByRole("link", {
        name: `Download ${apart(FILE.name)}, ${size}`,
      });
      expect(link.querySelector("small")?.textContent).toBe(size);
      expect(link).not.toHaveTextContent(`${size}.0`);
      expect(link).toHaveAttribute("download");
    },
  );

  it("uses the reader's decimal and grouping marks rather than the sentence's English language", () => {
    reader.language = "de";
    drawing(FILE.name, 1_342_177_280);
    const link = screen.getByRole("link", {
      name: `Download ${apart(FILE.name)}, 1,3 GB`,
    });
    expect(link.querySelector("small")?.textContent).toBe("1,3 GB");
    expect(link).not.toHaveTextContent("1.3 GB");
  });

  it("keeps readable metadata in a separate card without a guessed download or request when no readable run is supplied", () => {
    const fetch = vi.spyOn(globalThis, "fetch");
    const drawn = drawing(FILE.name, 1_024, FIELD, false);
    expect(
      [...drawn.querySelectorAll("bdi")].map((each) => each.textContent),
    ).toEqual([FILE.name]);
    expect(drawn).toHaveTextContent("1 KB");
    expect(drawn.firstElementChild).toHaveStyle({ display: "inline-flex" });
    expect(
      drawn.querySelector("a, button, iframe, img, embed, object"),
    ).toBeNull();
    expect(fetch).not.toHaveBeenCalled();
  });

  it("marks invisible and direction characters in the filename, including its accessible name, without a second ending tag", () => {
    const hidden = String.fromCodePoint(0x200b);
    const direction = String.fromCodePoint(0x202e);
    const name = `Imaginary${direction} claim.a${hidden}b`;
    const drawn = drawing(name);
    const link = screen.getByRole("link", {
      name: `Download ${apart("Imaginary U+202E claim.a U+200B b")}, 5 B`,
    });
    expect(
      [...link.querySelectorAll("bdi")]
        .filter((each) => each.closest("[hidden]") === null)
        .map((each) => each.textContent),
    ).toEqual(["ImaginaryU+202E claim.aU+200Bb"]);
    expect(drawn.innerHTML).not.toContain(hidden);
    expect(drawn.innerHTML).not.toContain(direction);
    expect(link).toHaveAttribute("download");
  });
});
