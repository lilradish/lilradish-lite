import { createContext } from "react";

export interface CurrentFileRun {
  readonly groupId: string;
  readonly runId: string;
}

export const CurrentFileRunContext = createContext<CurrentFileRun | null>(null);

export function fileDownloadHref(run: CurrentFileRun, fileId: string): string {
  return `/api/groups/${encodeURIComponent(run.groupId)}/runs/${encodeURIComponent(run.runId)}/files/${encodeURIComponent(fileId)}`;
}
