import { act, renderHook, waitFor } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import type { PoolPersonPanel } from "../../api/pool/people";
import { requestsTo, serving, type Reply } from "../../testutil/answering";
import { usePickedPerson } from "./usePickedPerson";

const ADA = "00000002-0000-4000-8000-000000000130";
const GRACE = "00000002-0000-4000-8000-000000000140";

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
    const { sent, result } = picking(GRACE, {
      [`GET /api/pool/people/${GRACE}`]: [replyOf(panelOf(GRACE))],
    });

    await landed(result);

    expect(result.current.read.value).toEqual(panelOf(GRACE));
    expect(requestsTo(sent)).toEqual([`GET /api/pool/people/${GRACE}`]);
  });

  /** The panel on its way out keeps what it showed rather than emptying first. */
  it("keeps the last person, settled, once nobody is picked, and reads nothing", async () => {
    const { sent, result, pick } = picking(GRACE, {
      [`GET /api/pool/people/${GRACE}`]: [replyOf(panelOf(GRACE))],
    });
    await landed(result);

    pick(undefined);
    await act(async () => {});

    expect(result.current.read).toMatchObject({
      value: panelOf(GRACE),
      loading: false,
      problem: null,
    });
    expect(requestsTo(sent)).toEqual([`GET /api/pool/people/${GRACE}`]);
  });

  it("shows what a change to them answered until they are read again", async () => {
    const { sent, result } = picking(GRACE, {
      [`GET /api/pool/people/${GRACE}`]: [
        replyOf(panelOf(GRACE)),
        replyOf(panelOf(GRACE, ["watcher"])),
      ],
    });
    await landed(result);

    act(() => result.current.answered(panelOf(GRACE, ["steward"])));
    const answered = result.current.read.value;
    act(() => result.current.reread());
    await landed(result);

    expect(answered).toEqual(panelOf(GRACE, ["steward"]));
    expect(result.current.read.value).toEqual(panelOf(GRACE, ["watcher"]));
    expect(requestsTo(sent)).toEqual([
      `GET /api/pool/people/${GRACE}`,
      `GET /api/pool/people/${GRACE}`,
    ]);
  });

  it("takes a seed for the next pick in place of reading them, once", async () => {
    const { sent, result, pick } = picking(undefined, {
      [`GET /api/pool/people/${ADA}`]: [replyOf(panelOf(ADA))],
      [`GET /api/pool/people/${GRACE}`]: [replyOf(panelOf(GRACE, ["watcher"]))],
    });

    act(() => result.current.seed(panelOf(GRACE)));
    pick(GRACE);
    await landed(result);
    const seeded = result.current.read.value;
    pick(ADA);
    await landed(result);
    pick(GRACE);
    await landed(result);

    expect(seeded).toEqual(panelOf(GRACE));
    expect(result.current.read.value).toEqual(panelOf(GRACE, ["watcher"]));
    expect(requestsTo(sent)).toEqual([
      `GET /api/pool/people/${ADA}`,
      `GET /api/pool/people/${GRACE}`,
    ]);
  });

  /** Somebody picked and refused, then brought in: the address does not move, so nothing else would read them. */
  it("takes a seed for the person already picked at once, over a refusal, without reading them", async () => {
    const { sent, result } = picking(GRACE, {
      [`GET /api/pool/people/${GRACE}`]: [
        ['{"code":"PERSON_NOT_IN_VIEW"}', 404],
      ],
    });
    await landed(result);
    const refused = result.current.read.problem?.code;

    act(() => result.current.seed(panelOf(GRACE)));
    await landed(result);

    expect(refused).toBe("PERSON_NOT_IN_VIEW");
    expect(result.current.read.problem).toBeNull();
    expect(result.current.read.value).toEqual(panelOf(GRACE));
    expect(requestsTo(sent)).toEqual([`GET /api/pool/people/${GRACE}`]);
  });

  it("lets a seed for somebody else go at the next pick, reading the one picked", async () => {
    const { sent, result, pick } = picking(undefined, {
      [`GET /api/pool/people/${ADA}`]: [replyOf(panelOf(ADA))],
      [`GET /api/pool/people/${GRACE}`]: [replyOf(panelOf(GRACE, ["watcher"]))],
    });

    act(() => result.current.seed(panelOf(GRACE)));
    pick(ADA);
    await landed(result);
    pick(GRACE);
    await landed(result);

    expect(result.current.read.value).toEqual(panelOf(GRACE, ["watcher"]));
    expect(requestsTo(sent)).toEqual([
      `GET /api/pool/people/${ADA}`,
      `GET /api/pool/people/${GRACE}`,
    ]);
  });
});
