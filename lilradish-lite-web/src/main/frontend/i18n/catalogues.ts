import { LIB_EN } from "./en/lib";

type Catalogue = Readonly<Record<string, string>>;

type IdsOf<Part> = Part extends Catalogue ? keyof Part : never;

type TopicOf<Id> = Id extends `${infer Topic}.${string}` ? Topic : Id;

type SplitTopics<Parts> = Parts extends readonly [infer First, ...infer Rest]
  ? (TopicOf<IdsOf<First>> & TopicOf<IdsOf<Rest[number]>>) | SplitTopics<Rest>
  : never;

/**
 * Merging silently overwrites duplicate ids; split topics cannot be read whole
 * as text. Types reject LIB_EN/part id collisions and topics in multiple parts.
 */
export function joined<const Parts extends readonly Catalogue[]>(
  parts: Parts &
    Record<SplitTopics<Parts>, never> &
    Record<keyof typeof LIB_EN & IdsOf<Parts[number]>, never>,
): { [Id in IdsOf<Parts[number]>]: string } {
  return Object.assign({}, ...parts);
}
