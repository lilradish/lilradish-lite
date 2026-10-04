import { act, renderHook, waitFor } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import type { PoolPersonPanel } from "../../api/pool/people";
import { requestsTo, serving, type Reply } from "../../testutil/answering";
import { usePickedPerson } from "./usePickedPerson";

const AVO = "00000002-0000-4000-8000-000000000130";
const GLINN = "00000002-0000-4000-8000-000000000140";

function panelOf(
  subjectId: string,
  estateRoles: string[] = [],
): PoolPersonPanel {
  return {
    subjectId,
    userId: subjectId.slice(-6),
    estateRoles: new Set(estateRoles),
    lastGrantingRoles: new Set(),
    groups: [],
    seeded: false,
  };
}

/** As the server writes a person, which is not the shape this side holds one in. */
function replyOf(person: PoolPersonPanel): Reply {
  return [
    JSON.stringify({
      ...person,
      estateRoles: [...person.estateRoles],
      lastGrantingRoles: [...person.lastGrantingRoles],
    }),
    200,
  ];
}

function picking(
  first: string | undefined,
  routes: Readonly<Record<string, readonly Reply[]>>,
) {
  const sent = serving(routes);
  const view = renderHook(
    ({ subjectId }: { subjectId: string | undefined }) =>
      usePickedPerson(subjectId),
    { initialProps: { subjectId: first } },
  );
  return {
    sent,
    result: view.result,
    pick(subjectId: string | undefined) {
      view.rerender({ subjectId });
    },
  };
}

async function landed(result: { current: ReturnType<typeof usePickedPerson> }) {
  await waitFor(() => expect(result.current.read.loading).toBe(false));
}

describe("usePickedPerson", () => {
  it("reads the person picked, once", async () => {
    const { sent, result } = picking(GLINN, {
      [`GET /api/pool/people/${GLINN}`]: [replyOf(panelOf(GLINN))],
    });

    await landed(result);

    expect(result.current.read.value).toEqual(panelOf(GLINN));
    expect(requestsTo(sent)).toEqual([`GET /api/pool/people/${GLINN}`]);
  });

  /** The panel on its way out keeps what it showed rather than emptying first. */
  it("keeps the last person, settled, once nobody is picked, and reads nothing", async () => {
    const { sent, result, pick } = picking(GLINN, {
      [`GET /api/pool/people/${GLINN}`]: [replyOf(panelOf(GLINN))],
    });
    await landed(result);

    pick(undefined);
    await act(async () => {});

    expect(result.current.read).toMatchObject({
      value: panelOf(GLINN),
      loading: false,
      problem: null,
    });
    expect(requestsTo(sent)).toEqual([`GET /api/pool/people/${GLINN}`]);
  });

  it("shows what a change to them answered until they are read again", async () => {
    const { sent, result } = picking(GLINN, {
      [`GET /api/pool/people/${GLINN}`]: [
        replyOf(panelOf(GLINN)),
        replyOf(panelOf(GLINN, ["watcher"])),
      ],
    });
    await landed(result);

    act(() => result.current.answered(panelOf(GLINN, ["steward"])));
    const answered = result.current.read.value;
    act(() => result.current.reread());
    await landed(result);

    expect(answered).toEqual(panelOf(GLINN, ["steward"]));
    expect(result.current.read.value).toEqual(panelOf(GLINN, ["watcher"]));
    expect(requestsTo(sent)).toEqual([
      `GET /api/pool/people/${GLINN}`,
      `GET /api/pool/people/${GLINN}`,
    ]);
  });

  it("takes a seed for the next pick in place of reading them, once", async () => {
    const { sent, result, pick } = picking(undefined, {
      [`GET /api/pool/people/${AVO}`]: [replyOf(panelOf(AVO))],
      [`GET /api/pool/people/${GLINN}`]: [replyOf(panelOf(GLINN, ["watcher"]))],
    });

    act(() => result.current.seed(panelOf(GLINN)));
    pick(GLINN);
    await landed(result);
    const seeded = result.current.read.value;
    pick(AVO);
    await landed(result);
    pick(GLINN);
    await landed(result);

    expect(seeded).toEqual(panelOf(GLINN));
    expect(result.current.read.value).toEqual(panelOf(GLINN, ["watcher"]));
    expect(requestsTo(sent)).toEqual([
      `GET /api/pool/people/${AVO}`,
      `GET /api/pool/people/${GLINN}`,
    ]);
  });

  /** Somebody picked and refused, then brought in: the address does not move, so nothing else would read them. */
  it("takes a seed for the person already picked at once, over a refusal, without reading them", async () => {
    const { sent, result } = picking(GLINN, {
      [`GET /api/pool/people/${GLINN}`]: [
        ['{"code":"PERSON_NOT_IN_VIEW"}', 404],
      ],
    });
    await landed(result);
    const refused = result.current.read.problem?.code;

    act(() => result.current.seed(panelOf(GLINN)));
    await landed(result);

    expect(refused).toBe("PERSON_NOT_IN_VIEW");
    expect(result.current.read.problem).toBeNull();
    expect(result.current.read.value).toEqual(panelOf(GLINN));
    expect(requestsTo(sent)).toEqual([`GET /api/pool/people/${GLINN}`]);
  });

  it("lets a seed for somebody else go at the next pick, reading the one picked", async () => {
    const { sent, result, pick } = picking(undefined, {
      [`GET /api/pool/people/${AVO}`]: [replyOf(panelOf(AVO))],
      [`GET /api/pool/people/${GLINN}`]: [replyOf(panelOf(GLINN, ["watcher"]))],
    });

    act(() => result.current.seed(panelOf(GLINN)));
    pick(AVO);
    await landed(result);
    pick(GLINN);
    await landed(result);

    expect(result.current.read.value).toEqual(panelOf(GLINN, ["watcher"]));
    expect(requestsTo(sent)).toEqual([
      `GET /api/pool/people/${AVO}`,
      `GET /api/pool/people/${GLINN}`,
    ]);
  });
});
