// Checks a minisign signature the way the installed Tauri app does before it
// installs an update (tauri-plugin-updater 2.14.1, verify_signature with the
// minisign-verify crate): pubkey and signature as base64 of the minisign text
// files, prehashed ("ED", BLAKE2b-512) or plain ("Ed") signature, then the global
// signature over signature + trusted comment, then `version:` in the comment.
import { createHash, createPublicKey, verify } from "node:crypto";

const lines = (base64: string) =>
  Buffer.from(base64.trim(), "base64").toString("utf8").trim().split(/\r?\n/);

/** Returns why `signature` (content of the .sig) does not verify, or null. */
export function minisignProblem(
  data: Buffer,
  signature: string,
  pubkey: string,
  version: string,
): string | null {
  const pub = Buffer.from(lines(pubkey)[1] ?? "", "base64");
  const [, sigLine, comment, globalLine] = lines(signature);
  const sig = Buffer.from(sigLine ?? "", "base64");
  const global = Buffer.from(globalLine ?? "", "base64");
  if (pub.length !== 42 || sig.length !== 74 || global.length !== 64)
    return "format minisign inattendu";
  if (!comment?.startsWith("trusted comment: "))
    return "commentaire de confiance absent";
  if (!sig.subarray(2, 10).equals(pub.subarray(2, 10)))
    return "signée par une autre clé que celle de tauri.conf.json";
  const key = createPublicKey({
    key: {
      kty: "OKP",
      crv: "Ed25519",
      x: pub.subarray(10).toString("base64url"),
    },
    format: "jwk",
  });
  const alg = sig.subarray(0, 2).toString("latin1");
  const signed =
    alg === "ED" ? createHash("blake2b512").update(data).digest() : data;
  if (!verify(null, signed, key, sig.subarray(10)))
    return "signature de l'installateur invalide";
  const trusted = comment.slice("trusted comment: ".length);
  const globalData = Buffer.concat([sig.subarray(10), Buffer.from(trusted)]);
  if (!verify(null, globalData, key, global))
    return "signature du commentaire de confiance invalide";
  if (!trusted.split(/\s+/).includes(`version:${version}`))
    return `le commentaire de confiance ne porte pas version:${version}`;
  return null;
}
