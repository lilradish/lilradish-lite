/** A document as it arrived, which nothing here has checked yet. */
export type Unchecked = Readonly<Record<string, unknown>>;

/**
 * A member that may be left out: undefined where it was, null where it
 * arrived and could not be read. An explicit null is not leaving it out.
 */
export function optional<T>(
  body: Unchecked,
  member: string,
  read: (value: unknown) => T | null,
): T | undefined | null {
  return member in body ? read(body[member]) : undefined;
}

/**
 * Every item read, or null where no list arrived or any item in it could not
 * be read: a list with a hole in it is not the list.
 */
export function listOf<T>(
  listed: unknown,
  itemFrom: (item: unknown) => T | null,
): T[] | null {
  if (!Array.isArray(listed)) {
    return null;
  }
  const items = listed.map(itemFrom);
  for (let at = 0; at < items.length; at++) {
    if (!Object.hasOwn(items, at)) {
      return null;
    }
  }
  return items.every((item): item is T => item !== null) ? items : null;
}

export function isUnchecked(value: unknown): value is Unchecked {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

export function textFrom(said: unknown): string | null {
  return typeof said === "string" ? said : null;
}

/** A whole number a double holds exactly, which is every count an answer carries. */
export function countFrom(said: unknown): number | null {
  return Number.isSafeInteger(said) ? (said as number) : null;
}

/** Only the members that arrived, so one left out stays out rather than arriving as undefined. */
export function present<T extends Record<string, unknown>>(
  members: T,
): Partial<{ [K in keyof T]: Exclude<T[K], undefined> }> {
  return Object.fromEntries(
    Object.entries(members).filter(([, value]) => value !== undefined),
  ) as Partial<{ [K in keyof T]: Exclude<T[K], undefined> }>;
}

/** An optional member that arrived unreadable refuses its whole document. */
export function allMembersRead<T extends Record<string, unknown>>(
  members: T,
): members is T & { [K in keyof T]: Exclude<T[K], null> } {
  return Object.values(members).every((value) => value !== null);
}

/**
 * Every word listed, kept as it came, and null where no list arrived: a word
 * never heard of is kept, and what is no word is dropped rather than refusing.
 */
export function wordsIn(listed: unknown): string[] | null {
  return Array.isArray(listed)
    ? listed.filter((word): word is string => typeof word === "string")
    : null;
}

/** Present only as true; one sent as false is no member the server sends. */
export function trueFrom(said: unknown): true | null {
  return said === true ? true : null;
}

export function flagFrom(said: unknown): boolean | null {
  return typeof said === "boolean" ? said : null;
}

export function severalFrom(said: unknown): "several" | null {
  return said === "several" ? said : null;
}
