import type { ReactElement, ReactNode } from "react";

import type { MessageId } from "./app";
import { intl } from "./intl";

export function visible(
  id: MessageId,
  values?: Readonly<Record<string, string | number | ReactElement>>,
): ReactNode {
  return intl.formatMessage({ id }, values);
}
