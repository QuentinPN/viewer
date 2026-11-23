// Update manifests written to the public releases repository on each publish.
// The apps read them to find a newer version.

/** `desktop/latest.json`, read by the Go updater
 *  (go-app/internal/updater/manifest.go): one installer and its `ed25519`
 *  signature (signature.ts). The layout is the one of Tauri's updater plugin,
 *  which the first releases used. */
export function desktopManifest(
  version: string,
  notes: string,
  url: string,
  ed25519: string,
  now: Date,
) {
  return {
    version,
    notes,
    pub_date: now.toISOString(),
    platforms: {
      "windows-x86_64": {
        ed25519: ed25519.trim(),
        url,
      },
    },
  };
}

/** `android/latest.json`, read by the Android TV app. */
export function androidManifest(
  version: { name: string; code: number },
  notes: string,
  url: string,
  apk: { bytes: number; sha256: string },
) {
  return {
    versionCode: version.code,
    versionName: version.name,
    notes,
    url,
    sha256: apk.sha256,
    size: apk.bytes,
  };
}
