// It sits in the source tree because the specs do; only a spec may import it.

/** A theme colour as a resolved style reports it back. */
export function resolved(hex: string): string {
  const [red, green, blue] = hex
    .slice(1)
    .match(/../g)!
    .map((pair) => parseInt(pair, 16));
  return `rgb(${red}, ${green}, ${blue})`;
}

/** Where nothing lays a ground, as a resolved style reports it. */
export const NO_GROUND = "rgba(0, 0, 0, 0)";
