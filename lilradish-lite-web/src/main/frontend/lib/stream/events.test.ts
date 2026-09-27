import { describe, expect, it } from "vitest";

import { scanEvents } from "./events";

describe("scanEvents", () => {
  it("dispatches a complete event and leaves nothing behind", () => {
    const scan = scanEvents("event: token\ndata: hello\n\n");

    expect(scan.events).toEqual([{ type: "token", data: "hello" }]);
    expect(scan.rest).toBe("");
  });

  it("dispatches both events when two arrive in one chunk", () => {
    const scan = scanEvents("data: a\n\ndata: b\n\n");

    expect(scan.events).toEqual([
      { type: "message", data: "a" },
      { type: "message", data: "b" },
    ]);
    expect(scan.rest).toBe("");
  });

  it.each([
    ["a line feed", "data: x\n\nnext"],
    ["a carriage return line feed pair", "data: x\r\n\r\nnext"],
    ["a bare carriage return", "data: x\r\rnext"],
  ])("ends a line on %s", (_case, chunk) => {
    const scan = scanEvents(chunk);

    expect(scan.events).toEqual([{ type: "message", data: "x" }]);
    expect(scan.rest).toBe("next");
  });

  it("ignores a comment line, which a sender may interleave to hold a connection open", () => {
    const scan = scanEvents(": keep-alive\n\nevent: token\ndata: x\n\n");

    expect(scan.events).toEqual([{ type: "token", data: "x" }]);
    expect(scan.rest).toBe("");
  });

  it("joins multiple data lines with a line feed and drops the trailing one", () => {
    const scan = scanEvents("data: first\ndata: second\n\n");

    expect(scan.events).toEqual([{ type: "message", data: "first\nsecond" }]);
    expect(scan.rest).toBe("");
  });

  it.each([
    ["one leading space separates a value from its field", "data: x\n\n", "x"],
    ["a second space belongs to the value", "data:  x\n\n", " x"],
    ["no space at all still parses", "data:x\n\n", "x"],
    [
      "only the first colon separates, so a value may hold more",
      'data: {"at":"09:30"}\n\n',
      '{"at":"09:30"}',
    ],
    ["an empty value still appends a line feed", "data:\ndata: x\n\n", "\nx"],
    [
      "a line with no colon is a field with an empty value",
      "data\ndata: x\n\n",
      "\nx",
    ],
  ])("reads a field value so that %s", (_case, chunk, expected) => {
    const scan = scanEvents(chunk);

    expect(scan.events).toEqual([{ type: "message", data: expected }]);
    expect(scan.rest).toBe("");
  });

  it("falls back to the message type for an event declaring none, after one that did", () => {
    const scan = scanEvents("event: token\ndata: a\n\ndata: b\n\n");

    expect(scan.events).toEqual([
      { type: "token", data: "a" },
      { type: "message", data: "b" },
    ]);
    expect(scan.rest).toBe("");
  });

  it("dispatches an event whose only data line is empty, since its buffer is not", () => {
    const scan = scanEvents("data:\n\n");

    expect(scan.events).toEqual([{ type: "message", data: "" }]);
    expect(scan.rest).toBe("");
  });

  it("drops an event that carries no data rather than dispatching it empty", () => {
    const scan = scanEvents("event: token\n\n");

    expect(scan.events).toEqual([]);
    expect(scan.rest).toBe("");
  });

  it("does not let a type leak from a dropped event into the next one", () => {
    const scan = scanEvents("event: token\n\ndata: x\n\n");

    expect(scan.events).toEqual([{ type: "message", data: "x" }]);
    expect(scan.rest).toBe("");
  });

  it("ignores the reconnection fields it has no use for instead of failing", () => {
    const scan = scanEvents("id: 7\nretry: 3000\ndata: x\n\n");

    expect(scan.events).toEqual([{ type: "message", data: "x" }]);
    expect(scan.rest).toBe("");
  });

  it("returns the whole buffer untouched when no line has ended", () => {
    const scan = scanEvents("data: partial");

    expect(scan.events).toEqual([]);
    expect(scan.rest).toBe("data: partial");
  });

  it("hands back every line of an event whose blank line has not arrived", () => {
    const scan = scanEvents("event: token\ndata: hello\n");

    expect(scan.events).toEqual([]);
    expect(scan.rest).toBe("event: token\ndata: hello\n");
  });

  it("resumes an event split across two chunks", () => {
    const first = scanEvents("event: token\ndata: hel");
    const second = scanEvents(`${first.rest}lo\n\n`);

    expect(first.events).toEqual([]);
    expect(second.events).toEqual([{ type: "token", data: "hello" }]);
    expect(second.rest).toBe("");
  });

  it("dispatches nothing for a blank line arriving before any data", () => {
    const first = scanEvents("\ndata: x");
    const second = scanEvents(`${first.rest}\n\n`);

    expect(first.events).toEqual([]);
    expect(first.rest).toBe("\ndata: x");
    expect(second.events).toEqual([{ type: "message", data: "x" }]);
    expect(second.rest).toBe("");
  });

  it("dispatches nothing for a CRLF blank line arriving before any data", () => {
    const first = scanEvents("\r\ndata: x");
    const second = scanEvents(`${first.rest}\r\n\r\n`);

    expect(first.events).toEqual([]);
    expect(first.rest).toBe("\r\ndata: x");
    expect(second.events).toEqual([{ type: "message", data: "x" }]);
    expect(second.rest).toBe("");
  });

  it("keeps a data line whose CRLF is split across two chunks as one line", () => {
    const first = scanEvents("data: first\r");
    const second = scanEvents(`${first.rest}\ndata: second\r\n\r\n`);

    expect(first.rest).toBe("data: first\r");
    expect(second.events).toEqual([{ type: "message", data: "first\nsecond" }]);
    expect(second.rest).toBe("");
  });

  it("withholds a trailing carriage return while more input may arrive", () => {
    const scan = scanEvents("data: x\r\n\r");

    expect(scan.events).toEqual([]);
    expect(scan.rest).toBe("data: x\r\n\r");
  });

  it("dispatches once the withheld CRLF completes in the next chunk", () => {
    const first = scanEvents("data: x\r\n\r");
    const second = scanEvents(`${first.rest}\n`);

    expect(second.events).toEqual([{ type: "message", data: "x" }]);
    expect(second.rest).toBe("");
  });

  it("discards an event still short of its blank line when the stream has ended", () => {
    const scan = scanEvents("event: token\ndata: hello\n", true);

    expect(scan.events).toEqual([]);
    expect(scan.rest).toBe("event: token\ndata: hello\n");
  });

  it("reads a trailing carriage return as a line end once the stream has ended", () => {
    const scan = scanEvents("data: x\r\n\r", true);

    expect(scan.events).toEqual([{ type: "message", data: "x" }]);
    expect(scan.rest).toBe("");
  });
});
