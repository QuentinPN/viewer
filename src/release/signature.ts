// Ed25519 signature of the desktop installers, checked by the Go app's updater
// (go-app/internal/updater/signature.go). Web Crypto only, so the release script
// and Vitest share this code; testdata/signature-vectors.json pins both sides.

/** Separates the update signatures from any other use of the key. */
const SIGNATURE_PREFIX = "viewer-update-v1\0";

/** `SignedMessage` of signature.go: prefix, version, 0x00, SHA-512(installer).
 *  `version` is the canonical semver of the release, without a leading v. */
export async function signedMessage(
  version: string,
  installer: Uint8Array<ArrayBuffer>,
): Promise<Uint8Array<ArrayBuffer>> {
  const digest = new Uint8Array(
    await crypto.subtle.digest("SHA-512", installer),
  );
  const head = new TextEncoder().encode(`${SIGNATURE_PREFIX}${version}\0`);
  const message = new Uint8Array(head.length + digest.length);
  message.set(head);
  message.set(digest, head.length);
  return message;
}

/** The `ed25519` field of the manifest: base64 signature of `signedMessage`
 *  with the PKCS#8 PEM private key. */
export async function signUpdate(
  version: string,
  installer: Uint8Array<ArrayBuffer>,
  privateKeyPem: string,
): Promise<string> {
  const key = await importPrivateKey(privateKeyPem, false);
  const signature = await crypto.subtle.sign(
    "Ed25519",
    key,
    await signedMessage(version, installer),
  );
  return toBase64(new Uint8Array(signature));
}

/** What the Go app accepts: `signature` over `signedMessage` under
 *  `publicKey`, the base64 of its 32 raw bytes. */
export async function verifyUpdate(
  version: string,
  installer: Uint8Array<ArrayBuffer>,
  signature: string,
  publicKey: string,
): Promise<boolean> {
  const key = await crypto.subtle.importKey(
    "raw",
    fromBase64(publicKey.trim()),
    "Ed25519",
    false,
    ["verify"],
  );
  return crypto.subtle.verify(
    "Ed25519",
    key,
    fromBase64(signature.trim()),
    await signedMessage(version, installer),
  );
}

/** The public half of a PKCS#8 PEM ed25519 key, in the format of
 *  go-app/build/updater-ed25519.pub (base64 of 32 bytes). */
export async function publicKeyOf(privateKeyPem: string): Promise<string> {
  const jwk = await crypto.subtle.exportKey(
    "jwk",
    await importPrivateKey(privateKeyPem, true),
  );
  if (!jwk.x) throw new Error("clé ed25519 sans partie publique");
  return toBase64(fromBase64(jwk.x.replace(/-/g, "+").replace(/_/g, "/")));
}

function importPrivateKey(pem: string, extractable: boolean) {
  const body = pem
    .replace(/-----(BEGIN|END) PRIVATE KEY-----/g, "")
    .replace(/\s+/g, "");
  return crypto.subtle.importKey(
    "pkcs8",
    fromBase64(body),
    "Ed25519",
    extractable,
    ["sign"],
  );
}

function toBase64(bytes: Uint8Array): string {
  return btoa(String.fromCharCode(...bytes));
}

function fromBase64(text: string): Uint8Array<ArrayBuffer> {
  const padded = text.padEnd(Math.ceil(text.length / 4) * 4, "=");
  return Uint8Array.from(atob(padded), (c) => c.charCodeAt(0));
}
