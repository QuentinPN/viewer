// Update manifests written to the public releases repository on each publish.
// The apps read them to find a newer version.

/** `desktop/latest.json`, in the format of Tauri's updater plugin. */
export function desktopManifest(
  version: string,
  notes: string,
  url: string,
  signature: string,
  now: Date,
) {
  return {
    version,
    notes,
    pub_date: now.toISOString(),
    platforms: {
      "windows-x86_64": { signature: signature.trim(), url },
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
