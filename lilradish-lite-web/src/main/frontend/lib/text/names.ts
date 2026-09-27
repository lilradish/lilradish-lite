/** Somebody as a reader is shown them: by the name held for them, or by their user number where none is. */
export function nameOrNumber(somebody: {
  readonly displayName?: string;
  readonly userId: string;
}): string {
  return somebody.displayName ?? somebody.userId;
}
