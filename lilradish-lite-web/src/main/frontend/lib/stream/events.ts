/** One event of a `text/event-stream`, as this side reads it. */
export interface StreamEvent {
  readonly type: string;
  readonly data: string;
}

interface EventScan {
  readonly events: readonly StreamEvent[];
  readonly rest: string;
}

/**
 * Cuts an accumulated buffer of a `text/event-stream` into whole events, by the
 * HTML Standard's rules for interpreting an event stream. The caller carries
 * `rest` into the next chunk and, once the stream has ended, discards whatever
 * is left in it: that is the incomplete event the standard throws away.
 *
 * Every chunk re-reads the whole of `rest`, so a sender that never completes an
 * event costs the caller time as well as memory: cap what is carried forward.
 */
export function scanEvents(buffer: string, ended = false): EventScan {
  // A trailing CR may be the first half of a CRLF still in flight; once nothing
  // more is coming it is a line end of its own, and withholding it loses an event.
  const scannable =
    !ended && buffer.endsWith("\r") ? buffer.slice(0, -1) : buffer;
  // An event ends only where one line end immediately follows another, so with
  // no such pair anywhere the buffer is all tail and reading it is waste.
  const eventEndPattern = /\n[\r\n]|\r\r/;
  if (!eventEndPattern.test(scannable)) {
    return { events: [], rest: buffer };
  }
  const lineEndPattern = /\r\n|\n|\r/g;

  const events: StreamEvent[] = [];
  let eventType = "";
  let data = "";
  let cursor = 0;
  // `rest` restarts at the last event boundary rather than the last line end: a
  // half-arrived event is re-read next time instead of becoming carried state.
  let boundary = 0;
  let lineEnd: RegExpExecArray | null;
  while ((lineEnd = lineEndPattern.exec(scannable)) !== null) {
    const line = scannable.slice(cursor, lineEnd.index);
    cursor = lineEnd.index + lineEnd[0].length;

    if (line === "") {
      if (data !== "") {
        events.push({
          type: eventType === "" ? "message" : eventType,
          // Unconditional: each `data` line appended the line feed cut here.
          data: data.slice(0, -1),
        });
      }
      eventType = "";
      data = "";
      boundary = cursor;
      continue;
    }
    if (line.startsWith(":")) {
      continue;
    }
    const colon = line.indexOf(":");
    const field = colon < 0 ? line : line.slice(0, colon);
    const afterColon = colon < 0 ? "" : line.slice(colon + 1);
    const value = afterColon.startsWith(" ") ? afterColon.slice(1) : afterColon;
    // `id` and `retry` are dropped: a disconnect ends the stream rather than
    // resuming it, and the run's recorded output is what a client re-reads.
    if (field === "event") {
      eventType = value;
    } else if (field === "data") {
      data += `${value}\n`;
    }
  }
  return { events, rest: buffer.slice(boundary) };
}
