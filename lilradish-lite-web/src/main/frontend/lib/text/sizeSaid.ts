import { readersIntl } from "../../i18n/intl";

export function sizeSaid(bytes: number): string {
  const units = ["B", "KB", "MB", "GB"] as const;
  let unit = 0;
  let amount = bytes;
  while (amount >= 1_024 && unit < units.length - 1) {
    amount /= 1_024;
    unit += 1;
  }
  return `${readersIntl.formatNumber(amount, {
    maximumFractionDigits: unit === 0 ? 0 : 1,
  })} ${units[unit]}`;
}
