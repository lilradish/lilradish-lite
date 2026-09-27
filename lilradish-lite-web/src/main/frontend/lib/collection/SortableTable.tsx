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
import type { Theme } from "@mui/material/styles";
import type { MouseEvent, ReactNode } from "react";
import { Link as RouterLink } from "react-router";

import { say } from "../../i18n/lib";
import { isolated } from "../direction/isolated";
import { Notice } from "../notice/Notice";
import type { PagedResource } from "../request/usePagedResource";
import type { Ordering } from "./ordering";
import { PagingControls } from "./PagingControls";

interface Column<T, K extends string> {
  readonly label: string;
  /** The first column's is drawn inside a link: phrasing content, nothing interactive. */
  readonly cell: (row: T) => ReactNode;
  /** Absent where the values have no order to put the rows in. */
  readonly sortKey?: K;
}

type Picking<T> =
  | {
      /** The key of the row the address names, whether or not it is on this page. */
      readonly picked: string | null;
      readonly hrefOf: (row: T) => string;
    }
  | { readonly picked?: never; readonly hrefOf?: never };

/** Where the page in hand is read again as it is kept up, which says nothing new: only a first read says it reads. */
type Rereading =
  | { readonly quietWhileAnswered?: false }
  | {
      readonly quietWhileAnswered: true;
      readonly page: { readonly answered: object | null };
    };

// A press on any of these is its own, and the row going somewhere as well would
// be two answers to one press.
const INTERACTIVE =
  "a, button, input, select, textarea, label, summary, [role=button], [role=link]";

const OPENING_ROW_SX = { cursor: "pointer" };

// Painted inside the cell rather than as its border, which in the collapsed
// border model takes its width out of the cell and moves the text.
const PICKED_CELL_SX = {
  boxShadow: (given: Theme) => `inset 4px 0 0 ${given.palette.primary.main}`,
};

const STATUS_REGION_SX = { my: 1 };

const STATUS_SX = { color: "text.secondary" };

/**
 * One page of a collection, in the order the caller asked the server for. This
 * never sorts rows itself: a page sorted here is a page, not the collection,
 * in that order.
 *
 * The headers stay whatever the body is doing — reading, empty, refused — so a
 * sort just pressed keeps focus, and the table keeps saying what it is sorted
 * by. What there is to say about the body is said outside the table, in a
 * region that is there before the words it announces.
 *
 * A picked row is an address, so its control is a link. `aria-current` rather
 * than `aria-selected`, which belongs to composite widgets and this table is
 * none; and a bar rather than a tint, which would be colour alone.
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
    readonly empty: string;
  } & Picking<T> &
    Rereading,
) {
  const { label, columns, order, onOrder, page, keyOf, picked, hrefOf, empty } =
    props;
  const quiet =
    props.quietWhileAnswered === true && props.page.answered !== null;
  // A wait is a state; nothing found is a hint, which a notice says.
  const status =
    page.loading && !quiet ? (
      <Typography variant="body2" sx={STATUS_SX}>
        {say("read.pending")}
      </Typography>
    ) : page.problem === null && page.items.length === 0 && page.onFirstPage ? (
      <Notice severity="info">{empty}</Notice>
    ) : null;

  return (
    <>
      <TableContainer>
        <Table aria-label={label}>
          <SortingHead columns={columns} order={order} onOrder={onOrder} />
          <TableBody>
            {page.items.map((row) => {
              const key = keyOf(row);
              if (hrefOf === undefined) {
                return (
                  <TableRow key={key}>
                    {columns.map((column, index) => (
                      <TableCell key={index}>
                        {isolated(column.cell(row))}
                      </TableCell>
                    ))}
                  </TableRow>
                );
              }
              const current = key === picked;
              return (
                <TableRow
                  key={key}
                  hover
                  onClick={forwardToLink}
                  sx={OPENING_ROW_SX}
                >
                  {columns.map((column, index) =>
                    index === 0 ? (
                      <TableCell
                        key={index}
                        sx={current ? PICKED_CELL_SX : undefined}
                      >
                        <Link
                          component={RouterLink}
                          to={hrefOf(row)}
                          aria-current={current || undefined}
                        >
                          {isolated(column.cell(row))}
                        </Link>
                      </TableCell>
                    ) : (
                      <TableCell key={index}>
                        {isolated(column.cell(row))}
                      </TableCell>
                    ),
                  )}
                </TableRow>
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

/** A collection sent whole: with nothing further to read, the order is the caller's to make and to keep. */
export function WholeTable<T, K extends string>({
  label,
  columns,
  order,
  onOrder,
  rows,
  keyOf,
  empty,
}: {
  readonly label: string;
  readonly columns: readonly Column<T, K>[];
  readonly order: Ordering<K>;
  readonly onOrder: (next: Ordering<K>) => void;
  readonly rows: readonly T[];
  readonly keyOf: (row: T) => string;
  readonly empty: string;
}) {
  return (
    <TableContainer>
      <Table aria-label={label}>
        <SortingHead columns={columns} order={order} onOrder={onOrder} />
        <TableBody>
          {rows.length === 0 ? (
            <TableRow>
              <TableCell colSpan={columns.length} sx={STATUS_SX}>
                {empty}
              </TableCell>
            </TableRow>
          ) : (
            rows.map((row) => (
              <TableRow key={keyOf(row)}>
                {columns.map((column, index) => (
                  <TableCell key={index}>
                    {isolated(column.cell(row))}
                  </TableCell>
                ))}
              </TableRow>
            ))
          )}
        </TableBody>
      </Table>
    </TableContainer>
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
