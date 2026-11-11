import { describe, expect, it } from "vitest";
import type { ShelfServer } from "../emby/types";
import { saved } from "./fixtures";
import {
  columnsOf,
  hostOf,
  initialOf,
  isPlainHttp,
  moveIndex,
  passwordNotice,
  tileAction,
  tileHue,
  tileKey,
  tileLabel,
} from "./shelf";

const tile = (access: ShelfServer["access"]): ShelfServer => ({
  server: saved,
  access,
});

describe("shelf tiles", () => {
  it("are keyed by server and user", () => {
    expect(tileKey(tile("session"))).toBe("srv1/u1");
  });

  it("show the host without scheme or trailing slash", () => {
    expect(hostOf("http://192.168.1.14:8096/")).toBe("192.168.1.14:8096");
    expect(hostOf("https://emby.exemple.fr/emby")).toBe("emby.exemple.fr/emby");
    expect(hostOf(" 10.0.0.2:8096 ")).toBe("10.0.0.2:8096");
  });

  it("draw the first letter, or a question mark", () => {
    expect(initialOf("salon")).toBe("S");
    expect(initialOf("  émile")).toBe("É");
    expect(initialOf("   ")).toBe("?");
  });

  it("keep a stable hue per server", () => {
    expect(tileHue("srv1")).toBe(tileHue("srv1"));
    expect(tileHue("srv1")).toBeGreaterThanOrEqual(0);
    expect(tileHue("srv1")).toBeLessThan(360);
    expect(tileHue("abc")).not.toBe(tileHue("abd"));
  });

  it("name the server and user, and the mode", () => {
    expect(tileLabel(tile("session"), false)).toBe(
      "Se connecter à Emby (quentin)",
    );
    expect(tileLabel(tile("rejected"), false)).toBe(
      "Se connecter à Emby (quentin), mot de passe requis",
    );
    expect(tileLabel(tile("signedOut"), true)).toBe("Oublier Emby (quentin)");
  });

  it("validate a saved token, otherwise ask the password without a request", () => {
    expect(tileAction(tile("session"))).toBe("resume");
    expect(tileAction(tile("rejected"))).toBe("password");
    expect(tileAction(tile("signedOut"))).toBe("password");
  });

  it("say « Session expirée » only after a rejection", () => {
    expect(passwordNotice(tile("rejected"))?.kind).toBe("sessionExpired");
    expect(passwordNotice(tile("signedOut"))).toBeNull();
    expect(passwordNotice(tile("session"))).toBeNull();
  });
});

describe("manual sign-in address", () => {
  it("warns about plain HTTP, also without scheme", () => {
    expect(isPlainHttp("http://192.168.1.14:8096")).toBe(true);
    expect(isPlainHttp("192.168.1.14:8096")).toBe(true);
    expect(isPlainHttp("HTTPS://emby.exemple.fr")).toBe(false);
    expect(isPlainHttp("  ")).toBe(false);
  });
});

describe("keyboard moves between tiles", () => {
  it("go left and right without wrapping", () => {
    expect(moveIndex(0, "ArrowRight", 3, 3)).toBe(1);
    expect(moveIndex(2, "ArrowRight", 3, 3)).toBeNull();
    expect(moveIndex(0, "ArrowLeft", 3, 3)).toBeNull();
    expect(moveIndex(1, "ArrowLeft", 3, 3)).toBe(0);
  });

  it("go up and down by rows", () => {
    expect(moveIndex(1, "ArrowDown", 6, 3)).toBe(4);
    expect(moveIndex(4, "ArrowUp", 6, 3)).toBe(1);
    expect(moveIndex(1, "ArrowUp", 6, 3)).toBeNull();
    // A shorter last row: down lands on its last tile.
    expect(moveIndex(2, "ArrowDown", 4, 3)).toBe(3);
    expect(moveIndex(3, "ArrowDown", 4, 3)).toBeNull();
    // One row: up and down do not move.
    expect(moveIndex(1, "ArrowDown", 3, 3)).toBeNull();
  });

  it("jump to the ends and ignore other keys", () => {
    expect(moveIndex(1, "Home", 4, 4)).toBe(0);
    expect(moveIndex(1, "End", 4, 4)).toBe(3);
    expect(moveIndex(1, "Enter", 4, 4)).toBeNull();
    expect(moveIndex(0, "ArrowRight", 0, 1)).toBeNull();
  });

  it("count the tiles of the first row", () => {
    expect(columnsOf([])).toBe(1);
    expect(columnsOf([100, 100, 100])).toBe(3);
    expect(columnsOf([100, 100, 340, 340])).toBe(2);
  });
});
