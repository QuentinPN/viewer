import { describe, expect, it } from "vitest";
import vectors from "../../go-app/internal/updater/testdata/signature-vectors.json";
import {
  publicKeyOf,
  signedMessage,
  signUpdate,
  verifyUpdate,
} from "./signature";

// The same vectors are checked by the Go verifier
// (go-app/internal/updater/signature_vectors_test.go).

const hex = (bytes: Uint8Array) =>
  Array.from(bytes, (b) => b.toString(16).padStart(2, "0")).join("");
const fromHex = (text: string) =>
  Uint8Array.from(text.match(/../g) ?? [], (h) => Number.parseInt(h, 16));
const fromBase64 = (text: string) =>
  Uint8Array.from(atob(text), (c) => c.charCodeAt(0));

/** PKCS#8 PEM of the test seed (RFC 8410 prefix + 32-byte seed). */
const pem = (() => {
  const der = fromHex(`302e020100300506032b657004220420${vectors.seed}`);
  return `-----BEGIN PRIVATE KEY-----\n${btoa(String.fromCharCode(...der))}\n-----END PRIVATE KEY-----\n`;
})();

describe("update signature shared with the Go app", () => {
  it("derives the vectors' public key", async () => {
    expect(await publicKeyOf(pem)).toBe(vectors.publicKey);
  });

  for (const c of vectors.cases) {
    it(`builds the Go message and signature for ${c.version}`, async () => {
      const installer = fromBase64(c.installer);
      expect(hex(await signedMessage(c.version, installer))).toBe(c.message);
      expect(await signUpdate(c.version, installer, pem)).toBe(c.signature);
      expect(
        await verifyUpdate(
          c.version,
          installer,
          c.signature,
          vectors.publicKey,
        ),
      ).toBe(true);
    });
  }

  it("rejects another version, other bytes or another key", async () => {
    const c = vectors.cases[0];
    if (!c) throw new Error("no vector");
    const installer = fromBase64(c.installer);
    const { publicKey } = vectors;
    expect(await verifyUpdate("9.9.9", installer, c.signature, publicKey)).toBe(
      false,
    );
    const altered = Uint8Array.from([...installer, 0]);
    expect(await verifyUpdate(c.version, altered, c.signature, publicKey)).toBe(
      false,
    );
    const otherKey = btoa(String.fromCharCode(...new Uint8Array(32)));
    expect(
      await verifyUpdate(c.version, installer, c.signature, otherKey),
    ).toBe(false);
  });
});
