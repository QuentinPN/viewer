import { describe, expect, it } from "vitest";
import { errorHint, toEmbyError } from "./api";

describe("toEmbyError", () => {
  it("keeps typed errors from Rust", () => {
    const e = { kind: "timeout", message: "délai dépassé" };
    expect(toEmbyError(e)).toEqual(e);
  });

  it("wraps strings and unknown values", () => {
    expect(toEmbyError("boom")).toEqual({
      kind: "invalidResponse",
      message: "boom",
    });
    expect(toEmbyError(42).message).toBe("erreur inattendue");
    expect(toEmbyError({ kind: "x" }).kind).toBe("invalidResponse");
  });
});

describe("errorHint", () => {
  it("gives an actionable hint for recoverable errors", () => {
    expect(errorHint({ kind: "unreachable", message: "" })).toContain("8096");
    expect(errorHint({ kind: "insecureRemoteUrl", message: "" })).toContain(
      "https://",
    );
    expect(errorHint({ kind: "server", message: "" })).toBeNull();
  });
});
