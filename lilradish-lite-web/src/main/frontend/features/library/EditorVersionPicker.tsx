import Autocomplete from "@mui/material/Autocomplete";
import Box from "@mui/material/Box";
import Typography from "@mui/material/Typography";
import Tooltip from "@mui/material/Tooltip";
import { useCallback, useLayoutEffect, useMemo, useRef, useState } from "react";

import type { ListVersion } from "../../api/declaration";
import type { EntryKind } from "../../api/groups/{groupId}/{kind}";
import {
  findVersions,
  type VersionCandidates,
  type VersionCandidate,
} from "../../api/groups/{groupId}/{kind}/versions";
import {
  readVersionDetail,
  type VersionDetail,
} from "../../api/groups/{groupId}/versions/{versionId}";
import {
  asProblem,
  RequestFailed,
  NOT_A_PROBLEM_DOCUMENT,
  type Problem,
} from "../../api/problem";
import { ProblemView } from "../../app/ProblemView";
import { useGroup } from "../../app/standing/RequireGroup";
import { useStandingRead } from "../../app/standing/StandingContext";
import { say } from "../../i18n/app";
import { visible } from "../../i18n/visible";
import { useResource } from "../../lib/request/useResource";
import { LabelledTextField } from "../../lib/form/LabelledTextField";
import { VersionLabel, versionLabelText } from "../../lib/text/VersionLabel";

const EMPTY: VersionCandidates = { items: [], more: false };

/** The ordinary picker and Take for one step claim the same latest request. */
export interface VersionDetailOwner {
  replace(request: AbortController | null): void;
  owns(request: AbortController): boolean;
  release(request: AbortController | null): void;
}

export function versionDetailOwner(): VersionDetailOwner {
  let pending: AbortController | null = null;
  return {
    replace(request) {
      pending?.abort();
      pending = request;
    },
    owns(request) {
      return pending === request;
    },
    release(request) {
      if (pending === request) pending = null;
    },
  };
}

/** Each detail belongs to this mounted owner and authority epoch, including permission and identity round trips. */
export function useVersionDetail({
  kind,
  scope,
  disabled,
  searchWay,
  onRead,
  owner,
}: {
  readonly kind: EntryKind;
  readonly scope: unknown;
  readonly disabled: boolean;
  readonly searchWay?: string;
  readonly onRead: (detail: VersionDetail | null) => void;
  readonly owner?: VersionDetailOwner;
}) {
  const group = useGroup();
  const standing = useStandingRead();
  const [problem, setProblem] = useState<Problem | null>(null);
  const [running, setRunning] = useState(false);
  const admission = useMemo(
    () => ({
      group: group.groupId,
      standing: standing.value,
      loading: standing.loading,
      problem: standing.problem,
      kind,
      scope,
      disabled,
      searchWay,
      owner,
    }),
    [
      group.groupId,
      standing.value,
      standing.loading,
      standing.problem,
      kind,
      scope,
      disabled,
      searchWay,
      owner,
    ],
  );
  const [readAt, setReadAt] = useState(admission);
  if (readAt !== admission) {
    setReadAt(admission);
    setRunning(false);
    setProblem(null);
  }
  const pending = useRef<AbortController | null>(null);
  const latest = useRef(onRead);
  useLayoutEffect(() => {
    latest.current = onRead;
  });
  useLayoutEffect(() => {
    const abandon = () => {
      const request = pending.current;
      request?.abort();
      admission.owner?.release(request);
      pending.current = null;
    };
    abandon();
    return abandon;
  }, [admission]);
  const read = useCallback(
    (versionId: string) => {
      pending.current?.abort();
      pending.current = null;
      if (admission.disabled || admission.loading || admission.problem !== null)
        return;
      admission.owner?.replace(null);
      setProblem(null);
      if (versionId === "") {
        setRunning(false);
        latest.current(null);
        return;
      }
      const request = new AbortController();
      pending.current = request;
      admission.owner?.replace(request);
      setRunning(true);
      const admitted = () => {
        if (pending.current !== request) return false;
        if (
          request.signal.aborted ||
          (admission.owner !== undefined && !admission.owner.owns(request))
        ) {
          pending.current = null;
          setRunning(false);
          return false;
        }
        return true;
      };
      readVersionDetail(admission.group, versionId, request.signal)
        .then((detail) => {
          if (!admitted()) return;
          if (
            detail.version.versionId !== versionId ||
            detail.kind !== admission.kind ||
            detail.version.standing !== "in_service" ||
            (admission.searchWay !== undefined &&
              (detail.kind !== "question" ||
                detail.searchWay !== admission.searchWay ||
                !detail.searchMatches))
          ) {
            throw new RequestFailed({ code: NOT_A_PROBLEM_DOCUMENT });
          }
          pending.current = null;
          admission.owner?.release(request);
          setRunning(false);
          latest.current(detail);
        })
        .catch((failure: unknown) => {
          if (!admitted()) return;
          pending.current = null;
          admission.owner?.release(request);
          setRunning(false);
          setProblem(asProblem(failure, request.signal));
        });
    },
    [admission],
  );
  return { read, running, problem };
}

/** A typed bounded search supplies metadata; selection waits for one complete independent detail. */
export function EditorVersionPicker({
  kind,
  selected,
  value,
  label,
  hint,
  noneLabel,
  disabled = false,
  readOnly = false,
  scope,
  searchWay,
  onPick,
  detailOwner,
}: {
  readonly kind: EntryKind;
  readonly selected?: ListVersion;
  readonly value: string;
  readonly label: string;
  readonly hint?: string;
  readonly noneLabel?: string;
  readonly disabled?: boolean;
  readonly readOnly?: boolean;
  readonly scope: unknown;
  readonly searchWay?: string;
  readonly onPick: (detail: VersionDetail | null) => void;
  readonly detailOwner?: VersionDetailOwner;
}) {
  const group = useGroup();
  const standing = useStandingRead();
  const [search, setSearch] = useState<{ value: string; typed: string | null }>(
    {
      value,
      typed: null,
    },
  );
  if (search.value !== value) setSearch({ value, typed: null });
  const typed = search.value === value ? search.typed : null;
  const collectionOwner = useMemo(
    () => ({ groupId: group.groupId, scope, standing: standing.value }),
    [group.groupId, scope, standing.value],
  );
  const load = useCallback(
    (signal: AbortSignal) => {
      if (
        !typed ||
        disabled ||
        readOnly ||
        standing.loading ||
        standing.problem !== null
      )
        return Promise.resolve(EMPTY);
      return new Promise<VersionCandidates>((resolve, reject) => {
        const abandon = () => {
          clearTimeout(delay);
          reject(new DOMException("Aborted", "AbortError"));
        };
        const delay = setTimeout(() => {
          signal.removeEventListener("abort", abandon);
          findVersions(
            collectionOwner.groupId,
            kind,
            typed,
            searchWay,
            signal,
          ).then(resolve, reject);
        }, 150);
        signal.addEventListener("abort", abandon, { once: true });
      });
    },
    [
      collectionOwner,
      kind,
      typed,
      searchWay,
      disabled,
      readOnly,
      standing.loading,
      standing.problem,
    ],
  );
  const found = useResource(load, EMPTY);
  const details = useVersionDetail({
    kind,
    scope,
    disabled: disabled || readOnly,
    searchWay,
    onRead: onPick,
    owner: detailOwner,
  });
  const none =
    noneLabel === undefined
      ? undefined
      : { name: noneLabel, versionId: "", number: 1, versionName: null };
  const current = selected ?? (value === "" ? none : undefined);
  const options: VersionCandidate[] = !typed
    ? []
    : [...(none === undefined ? [] : [none]), ...found.value.items];
  const words = (item: ListVersion) =>
    item.versionId === ""
      ? noneLabel!
      : versionLabelText({
          entry: item.name,
          number: item.number,
          name: item.versionName,
        });
  const chosenWords = current === undefined ? "" : words(current);
  const showingChosen = typed === null && current !== undefined;
  return (
    <>
      <Autocomplete
        fullWidth
        size="small"
        options={options}
        value={current ?? null}
        disabled={disabled}
        readOnly={readOnly}
        disableClearable={noneLabel === undefined}
        inputValue={typed ?? chosenWords}
        clearOnBlur={false}
        disabledItemsFocusable
        isOptionEqualToValue={(item, chosen) =>
          item.versionId === chosen.versionId
        }
        getOptionKey={(item) => item.versionId}
        getOptionLabel={words}
        filterOptions={(items) => items}
        getOptionDisabled={(item) =>
          "searchMatches" in item &&
          searchWay !== undefined &&
          item.searchMatches !== true
        }
        onInputChange={(_event, text, reason) => {
          if (reason === "input" || reason === "clear")
            setSearch({ value, typed: text });
        }}
        onChange={(_event, chosen) => {
          if (chosen === null) {
            if (none !== undefined) {
              setSearch({ value, typed: null });
              details.read("");
            }
          } else if (
            !("searchMatches" in chosen) ||
            searchWay === undefined ||
            chosen.searchMatches === true
          ) {
            setSearch({ value, typed: null });
            details.read(chosen.versionId);
          }
        }}
        loading={found.loading || details.running}
        noOptionsText={say(
          !typed
            ? "editorVersionPicker.placeholder"
            : "editorVersionPicker.noMatch",
        )}
        renderOption={(props, item) => (
          <Box
            component="li"
            {...props}
            key={item.versionId}
            sx={{ whiteSpace: "normal", overflowWrap: "anywhere" }}
          >
            <Box sx={{ minWidth: 0 }}>
              {item.versionId === "" ? noneLabel : candidateLabel(item)}
              {"searchMatches" in item &&
              item.searchMatches === false &&
              searchWay !== undefined ? (
                <Typography variant="caption" color="text.secondary">
                  {say("question.searchShapeMismatch", { way: searchWay })}
                </Typography>
              ) : null}
            </Box>
          </Box>
        )}
        renderInput={(params) => (
          <Tooltip title={showingChosen ? chosenWords : ""} describeChild>
            <Box>
              <LabelledTextField
                {...params}
                label={label}
                placeholder={say("editorVersionPicker.placeholder")}
                slotProps={{
                  ...params.slotProps,
                  input: {
                    ...params.slotProps.input,
                    startAdornment: showingChosen ? (
                      <Box
                        aria-hidden="true"
                        sx={{
                          position: "absolute",
                          insetInlineStart: "6px",
                          insetInlineEnd:
                            noneLabel === undefined ? "36px" : "62px",
                          pointerEvents: "none",
                          whiteSpace: "nowrap",
                          overflow: "hidden",
                          textOverflow: "ellipsis",
                        }}
                      >
                        {current!.versionId === ""
                          ? noneLabel
                          : candidateLabel(current!)}
                      </Box>
                    ) : (
                      params.slotProps.input.startAdornment
                    ),
                    sx: showingChosen
                      ? {
                          "& input": {
                            color: "transparent",
                            caretColor: "text.primary",
                          },
                        }
                      : undefined,
                  },
                }}
                helperText={
                  found.value.more
                    ? say("editorVersionPicker.more")
                    : typed &&
                        !found.loading &&
                        found.problem === null &&
                        found.value.items.length === 0
                      ? say("editorVersionPicker.noMatch")
                      : hint
                }
              />
            </Box>
          </Tooltip>
        )}
      />
      {found.problem === null ? null : <ProblemView problem={found.problem} />}
      {details.problem === null ? null : (
        <ProblemView problem={details.problem} />
      )}
    </>
  );
}

function candidateLabel(item: ListVersion) {
  return visible("workflow.runsVersion", {
    label: (
      <VersionLabel
        entry={item.name}
        number={item.number}
        name={item.versionName}
      />
    ),
  });
}
