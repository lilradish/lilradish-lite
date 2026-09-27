/**
 * A promise the test decides the settling of. An ordering a hook has to get
 * right is then stated by the test rather than left to whichever answer the
 * runtime happens to deliver first.
 *
 * It sits in the source tree because the specs do: this project has no
 * separate test root to put it in. Only a spec may import it — it is kept out
 * of the coverage report and no build input names it, and a production module
 * importing it would quietly undo both.
 */
export function deferred<T>() {
  let settle!: (value: T) => void;
  let refuse!: (reason: unknown) => void;
  const promise = new Promise<T>((resolves, rejects) => {
    settle = resolves;
    refuse = rejects;
  });
  return { promise, settle, refuse };
}
