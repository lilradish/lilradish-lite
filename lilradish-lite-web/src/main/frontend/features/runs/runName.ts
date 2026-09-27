import { lineFits } from "../../lib/text/legibility";

export const MOST_IN_A_RUN_NAME = 128;

/** A run's name as the server holds one: a line of at most 128 characters, its spacing left as typed. */
export function runNameFits(typed: string): boolean {
  return lineFits(typed) && [...typed].length <= MOST_IN_A_RUN_NAME;
}
