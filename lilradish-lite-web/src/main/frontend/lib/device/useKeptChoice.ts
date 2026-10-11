import { useState } from "react";

/**
 * Nothing kept, an unknown choice or refused storage yields the first choice.
 * A lost device choice must never prevent a page opening; writes are best effort.
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
      // setItem throws where storage is refused or its quota is full.
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
