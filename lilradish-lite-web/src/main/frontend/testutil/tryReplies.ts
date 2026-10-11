import type { Reply } from "./answering";

export function tryReplies(page: object): {
  readonly summary: readonly [string, number];
  readonly materials: ReadonlyMap<number, Reply>;
} {
  page = publishedState(page);
  if (!("triesMade" in page)) {
    return { summary: [JSON.stringify(page), 200], materials: new Map() };
  }
  if (!Array.isArray(page.triesMade)) {
    throw new Error("UI step fixture triesMade is not an array");
  }
  const materials = new Map<number, Reply>();
  const triesMade = page.triesMade.map((source: unknown) => {
    if (
      !object(source) ||
      !Number.isInteger(source.number) ||
      Number(source.number) < 1 ||
      !object(source.review) ||
      typeof source.review.asked !== "boolean" ||
      !object(source.producedBy) ||
      typeof source.producedBy.kind !== "string"
    ) {
      throw new Error(
        "UI step fixture has an unrecognised numbered try/review/producer",
      );
    }
    const number = Number(source.number);
    if (materials.has(number)) {
      throw new Error(`UI step fixture repeats attempt ${number}`);
    }
    const {
      returned,
      declined,
      unreadableResponse,
      modelReply,
      search,
      review,
      wentWrong,
      ...summary
    } = source;
    const {
      declined: reviewDeclined,
      unreadableResponse: reviewUnreadable,
      modelReply: reviewReply,
      ...reviewSummary
    } = review;
    const code = source.producedBy.kind === "code";
    const material = {
      number,
      ...(code && wentWrong !== undefined ? { wentWrong } : {}),
      ...held("returned", returned),
      ...held("declined", declined),
      ...held("unreadableResponse", unreadableResponse),
      ...held("modelReply", modelReply),
      ...(reviewDeclined === undefined &&
      reviewUnreadable === undefined &&
      reviewReply === undefined
        ? {}
        : {
            review: {
              ...held("declined", reviewDeclined),
              ...held("unreadableResponse", reviewUnreadable),
              ...held("modelReply", reviewReply),
            },
          }),
      ...held("search", search),
    };
    materials.set(number, [JSON.stringify(material), 200]);
    let counts: Record<string, unknown> | undefined;
    if (search !== undefined) {
      if (!object(search))
        throw new Error(
          `UI step fixture attempt ${number} search is not an object`,
        );
      counts = {};
      for (const key of ["uses", "usesCounted", "fee", "noFee"]) {
        if (key in search) counts[key] = search[key];
      }
    }
    return {
      ...summary,
      ...(code ? {} : held("wentWrong", wentWrong)),
      wentWrongPresent: fact(
        source,
        "wentWrongPresent",
        wentWrong !== undefined,
      ),
      returnedPresent: fact(source, "returnedPresent", returned !== undefined),
      declinedPresent: fact(source, "declinedPresent", readable(declined)),
      unreadableResponsePresent: fact(
        source,
        "unreadableResponsePresent",
        unreadableResponse !== undefined,
      ),
      modelReplyPresent: fact(
        source,
        "modelReplyPresent",
        modelReply !== undefined,
      ),
      review: {
        ...reviewSummary,
        declinedPresent: fact(
          review,
          "declinedPresent",
          readable(reviewDeclined),
        ),
        unreadableResponsePresent: fact(
          review,
          "unreadableResponsePresent",
          reviewUnreadable !== undefined,
        ),
        modelReplyPresent: fact(
          review,
          "modelReplyPresent",
          reviewReply !== undefined,
        ),
      },
      ...(counts === undefined ? {} : { search: counts }),
    };
  });
  return { summary: [JSON.stringify({ ...page, triesMade }), 200], materials };
}

/** Typed UI fixtures hold sets; their explicitly supplied HTTP facts are JSON arrays. */
function publishedState(page: object): object {
  const row = (value: unknown): unknown => {
    if (!object(value)) return value;
    return {
      ...value,
      ...(value.stateActs instanceof Set
        ? { stateActs: [...value.stateActs] }
        : {}),
      ...(value.letGoThen instanceof Set
        ? { letGoThen: [...value.letGoThen] }
        : {}),
    };
  };
  return {
    ...page,
    ...("run" in page ? { run: row(page.run) } : {}),
    ...("step" in page ? { step: row(page.step) } : {}),
    ...("steps" in page && Array.isArray(page.steps)
      ? { steps: page.steps.map(row) }
      : {}),
  };
}

function held(key: string, value: unknown): Record<string, unknown> {
  return value === undefined ? {} : { [key]: value };
}

function readable(value: unknown): boolean {
  return value !== undefined && !(object(value) && value.withheld === true);
}

function fact(
  source: Record<string, unknown>,
  key: string,
  derived: boolean,
): boolean {
  if (!(key in source)) return derived;
  if (typeof source[key] !== "boolean")
    throw new Error(`UI step fixture ${key} is not boolean`);
  return source[key];
}

function object(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}
