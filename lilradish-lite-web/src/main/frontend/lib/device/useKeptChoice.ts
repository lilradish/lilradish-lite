import { useState } from "react";

/**
 * One of `choices` as this device last kept it under `keptAs`, and a way to change it this device keeps. Nothing
 * kept, a word this build does not know, or a storage that refuses, gives the first: a lost choice is never a reason
 * for a page not to open.
 */
export function useKeptChoice<C extends string>(
  keptAs: string,
  choices: readonly [C, ...C[]],
): readonly [C, (next: C) => void] {
  const [chosen, setChosen] = useState(() => keptOf(keptAs, choices));

  const choose = (next: C) => {
    setChosen(next);
    try {
      localStorage.setItem(keptAs, next);
    } catch {
      // setItem throws where storage is refused, as in private browsing, or its quota is full.
    }
  };

  return [chosen, choose];
}

function keptOf<C extends string>(
  keptAs: string,
  choices: readonly [C, ...C[]],
): C {
  try {
    const kept = localStorage.getItem(keptAs);
    return choices.find((each) => each === kept) ?? choices[0];
  } catch {
    return choices[0];
  }
}
