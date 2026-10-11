import {
  isUnchecked,
  optional,
  present,
  textFrom,
} from "../lib/request/document";
import { personFrom, type Person } from "./person";

export interface Who {
  readonly kind: string;
  readonly person?: Person;
  readonly model?: string;
  readonly mode?: string;
}

export function whoFrom(body: unknown): Who | null {
  if (!isUnchecked(body) || typeof body.kind !== "string") {
    return null;
  }
  const person = optional(body, "person", personFrom);
  const model = optional(body, "model", textFrom);
  const mode = optional(body, "mode", textFrom);
  if (person === null || model === null || mode === null) {
    return null;
  }
  return { kind: body.kind, ...present({ person, model, mode }) };
}
