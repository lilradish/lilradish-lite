import Box from "@mui/material/Box";
import Link from "@mui/material/Link";
import Table from "@mui/material/Table";
import TableBody from "@mui/material/TableBody";
import TableCell from "@mui/material/TableCell";
import TableContainer from "@mui/material/TableContainer";
import TableHead from "@mui/material/TableHead";
import TableRow from "@mui/material/TableRow";
import TableSortLabel from "@mui/material/TableSortLabel";
import Typography from "@mui/material/Typography";
import type { MouseEvent, ReactNode } from "react";
import { Link as RouterLink } from "react-router";

import { say } from "../../i18n/lib";
import { isolated } from "../direction/isolated";
import { Notice } from "../notice/Notice";
import type { PagedResource } from "../request/usePagedResource";
import type { Ordering } from "./ordering";
import { PagingControls } from "./PagingControls";
import { TABLE_SX, CELL_SX } from "./table";

interface Column<T, K extends string> {
  readonly label: string;
  /** The first column's is drawn inside a link: phrasing content, nothing interactive. */
  readonly cell: (row: T) => ReactNode;
  /** Absent where the values have no order to put the rows in. */
  readonly sortKey?: K;
  /** A figure, set at the trailing end in digits of one width, so a column of them reads by size. */
  readonly numeric?: true;
  readonly compact?: true;
}

type Picking<T> =
  | {
      /** The key of the row the address names, whether or not it is on this page. */
      readonly picked: string | null;
      readonly hrefOf: (row: T) => string;
      readonly linkNameOf?: (row: T) => string;
      readonly linkDescriptionOf?: (row: T) => string;
    }
  | {
      readonly picked?: never;
      readonly hrefOf?: never;
      readonly linkNameOf?: never;
      readonly linkDescriptionOf?: never;
    };

// A press on any of these is its own, and the row going somewhere as well would
// be two answers to one press.
const INTERACTIVE =
  "a, button, input, select, textarea, label, summary, [role=button], [role=link]";

const OPENING_ROW_SX = { cursor: "pointer" };

const STATUS_REGION_SX = { my: 1 };

const STATUS_SX = { color: "text.secondary" };

/** How a cell holding a figure is set, a numeric column's and any other table's alike, its heading's included. */
export const FIGURE_SETTING = {
  align: "right",
  sx: { typography: "figure", width: "1%", whiteSpace: "nowrap" },
} as const;

const WORDS_SETTING = {};
const COMPACT_SETTING = { sx: { width: "1%", whiteSpace: "nowrap" } };

function cellSetting<T, K extends string>(column: Column<T, K>) {
  return column.numeric === true
    ? FIGURE_SETTING
    : column.compact === true
      ? COMPACT_SETTING
      : WORDS_SETTING;
}

/**
 * Keep server order: sorting a page cannot sort the collection.
 *
 * Headers stay during loading, emptiness and refusal, preserving focus and the
 * sort label. Body status lives outside the table, in a region mounted before
 * the words it announces.
 *
 * A picked row has an address, so uses a link with `aria-current`:
 * `aria-selected` belongs to composite widgets. A bar avoids relying on colour alone.
 */
export function SortableTable<T, K extends string>(
  props: {
    readonly label: string;
    readonly columns: readonly Column<T, K>[];
    readonly order: Ordering<K>;
    readonly onOrder: (next: Ordering<K>) => void;
    /** Its refusal is the caller's to show; this shows no rows for it. */
    readonly page: PagedResource<T>;
    readonly keyOf: (row: T) => string;
    /**
     * Said where a settled, unrefused read of the first page found nothing. A
     * later page coming back empty says nothing about the collection: whatever
     * was on it may have gone since, and the rest is still there.
     */
    readonly empty: ReactNode;
  } & Picking<T>,
) {
  const {
    label,
    columns,
    order,
    onOrder,
    page,
    keyOf,
    picked,
    hrefOf,
    linkNameOf,
    linkDescriptionOf,
    empty,
  } = props;
  const status = page.loading ? (
    <Typography variant="body2" sx={STATUS_SX}>
      {say("read.pending")}
    </Typography>
  ) : page.problem === null && page.items.length === 0 && page.onFirstPage ? (
    <Notice severity="info">{empty}</Notice>
  ) : null;

  return (
    <>
      <TableContainer>
        <Table size="small" aria-label={label} sx={TABLE_SX}>
          <SortingHead columns={columns} order={order} onOrder={onOrder} />
          <TableBody sx={CELL_SX}>
            {page.items.map((row) => {
              const key = keyOf(row);
              return (
                <BodyRow
                  key={key}
                  row={row}
                  columns={columns}
                  href={hrefOf?.(row)}
                  linkName={linkNameOf?.(row)}
                  linkDescription={linkDescriptionOf?.(row)}
                  current={key === picked}
                />
              );
            })}
          </TableBody>
        </Table>
      </TableContainer>
      <Box role="status" sx={STATUS_REGION_SX}>
        {status}
      </Box>
      <PagingControls page={page} />
    </>
  );
}

/**
 * With the whole collection available, the caller owns its order.
 */
export function WholeTable<T, K extends string>(
  props: {
    readonly label: string;
    readonly columns: readonly Column<T, K>[];
    readonly order: Ordering<K>;
    readonly onOrder: (next: Ordering<K>) => void;
    readonly rows: readonly T[];
    readonly keyOf: (row: T) => string;
    readonly empty: ReactNode;
  } & Picking<T>,
) {
  const {
    label,
    columns,
    order,
    onOrder,
    rows,
    keyOf,
    empty,
    picked,
    hrefOf,
    linkNameOf,
    linkDescriptionOf,
  } = props;
  return (
    <TableContainer>
      <Table size="small" aria-label={label} sx={TABLE_SX}>
        <SortingHead columns={columns} order={order} onOrder={onOrder} />
        <TableBody sx={CELL_SX}>
          {rows.length === 0 ? (
            <TableRow>
              <TableCell colSpan={columns.length} sx={STATUS_SX}>
                {empty}
              </TableCell>
            </TableRow>
          ) : (
            rows.map((row) => {
              const key = keyOf(row);
              return (
                <BodyRow
                  key={key}
                  row={row}
                  columns={columns}
                  href={hrefOf?.(row)}
                  linkName={linkNameOf?.(row)}
                  linkDescription={linkDescriptionOf?.(row)}
                  current={key === picked}
                />
              );
            })
          )}
        </TableBody>
      </Table>
    </TableContainer>
  );
}

function BodyRow<T, K extends string>({
  row,
  columns,
  href,
  linkName,
  linkDescription,
  current,
}: {
  readonly row: T;
  readonly columns: readonly Column<T, K>[];
  readonly href: string | undefined;
  readonly linkName: string | undefined;
  readonly linkDescription: string | undefined;
  readonly current: boolean;
}) {
  if (href === undefined) {
    return (
      <TableRow>
        {columns.map((column, index) => (
          <TableCell key={index} {...cellSetting(column)}>
            {isolated(column.cell(row))}
          </TableCell>
        ))}
      </TableRow>
    );
  }
  return (
    <TableRow
      hover
      selected={current}
      onClick={forwardToLink}
      sx={OPENING_ROW_SX}
    >
      {columns.map((column, index) =>
        index === 0 ? (
          <TableCell key={index} {...cellSetting(column)}>
            <Link
              component={RouterLink}
              to={href}
              aria-current={current || undefined}
              aria-label={linkName}
              aria-description={linkDescription}
            >
              {isolated(column.cell(row))}
            </Link>
          </TableCell>
        ) : (
          <TableCell key={index} {...cellSetting(column)}>
            {isolated(column.cell(row))}
          </TableCell>
        ),
      )}
    </TableRow>
  );
}

function SortingHead<T, K extends string>({
  columns,
  order,
  onOrder,
}: {
  readonly columns: readonly Column<T, K>[];
  readonly order: Ordering<K>;
  readonly onOrder: (next: Ordering<K>) => void;
}) {
  return (
    <TableHead>
      <TableRow>
        {/* Keyed by position, which is safe for a declaration that never
            reorders; two columns may share a label. */}
        {columns.map((column, index) => {
          const sortKey = column.sortKey;
          const sorted = sortKey !== undefined && sortKey === order.column;
          return (
            <TableCell
              key={index}
              {...cellSetting(column)}
              sortDirection={
                sorted ? (order.descending ? "desc" : "asc") : false
              }
            >
              {sortKey === undefined ? (
                column.label
              ) : (
                <TableSortLabel
                  // A real button, not MUI's default span[role=button].
                  component="button"
                  active={sorted}
                  direction={sorted && order.descending ? "desc" : "asc"}
                  onClick={() =>
                    onOrder({
                      column: sortKey,
                      descending: sorted && !order.descending,
                    })
                  }
                >
                  {column.label}
                </TableSortLabel>
              )}
            </TableCell>
          );
        })}
      </TableRow>
    </TableHead>
  );
}

/**
 * A plain press anywhere on the row is a press on its link, so the link alone
 * decides where it goes and how history takes it. Left alone: a modified press,
 * which asks the browser for a tab or a window that only the link itself can
 * open; a press on something else in the row; and the end of a drag that
 * selected words, where the reader wanted the words.
 */
function forwardToLink(event: MouseEvent<HTMLTableRowElement>) {
  if (
    event.button !== 0 ||
    event.metaKey ||
    event.ctrlKey ||
    event.shiftKey ||
    event.altKey ||
    document.getSelection()?.isCollapsed === false ||
    (event.target instanceof Element &&
      event.target.closest(INTERACTIVE) !== null)
  ) {
    return;
  }
  event.currentTarget.querySelector("a")?.click();
}
