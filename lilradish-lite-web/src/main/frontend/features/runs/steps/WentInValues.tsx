import type { ReadField } from "../../../api/filling";
import type { WentIn } from "../../../api/groups/{groupId}/runs/{runId}/steps";
import type { Declaring } from "./declared";
import { DrawnInputs, type Heading } from "./DrawnInputs";
import { WentInListed } from "./WentInListed";

export function WentInValues({
  wentIn,
  takes,
  declaring,
  drawing,
  under,
}: {
  readonly wentIn: readonly WentIn[];
  readonly takes: readonly ReadField[] | undefined;
  readonly declaring: Declaring;
} & (
  | { readonly drawing?: undefined; readonly under?: undefined }
  | {
      readonly drawing: string | undefined;
      /** The heading of the section the values are in, which every heading the drawing writes sits below. */
      readonly under: Heading;
    }
)) {
  if (drawing !== undefined && under !== undefined) {
    return (
      <DrawnInputs
        drawing={drawing}
        wentIn={wentIn}
        takes={takes}
        declaring={declaring}
        under={under}
      />
    );
  }
  return <WentInListed wentIn={wentIn} takes={takes} declaring={declaring} />;
}
