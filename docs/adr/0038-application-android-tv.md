# ADR 0038 — Application Android TV : Kotlin natif, libmpv de mpv-android

Statut : Accepté (2026-11-09). Gate TV-1 (faisabilité de la lecture) en cours.

## Contexte
Le propriétaire veut Viewer sur sa télévision, surtout pour les sous-titres ASS. Le lecteur de la TV LG ne sait pas les afficher : Emby doit les extraire du MKV entier ou les incruster, ce qui fait attendre jusqu'à 5 minutes. Les appareils sont une NVIDIA Shield TV 2017 (Android 11, API 30, arm64), une FireStick (Fire OS, dérivé d'Android) et une TV Nokia sous Android TV. Un seul APK peut donc couvrir les trois. webOS est écarté : il n'a que `<video>` HTML5, sans rendu ASS ni libmpv, et ne règle pas le problème.

## Décision
- **Application Android native en Kotlin**, dans `android-tv/` du même dépôt : la documentation, les ADR et les contrats Emby restent partagés. Aucune réutilisation de code avec Tauri.
- **Lecteur : libmpv**, comme sur Windows. On garde le même moteur, le même rendu ASS (libass), le Direct Play et le décodage matériel (`hwdec=mediacodec`). Il reste derrière une interface `PlayerEngine` côté Kotlin.
- **Provenance de libmpv** : les `.so` arm64-v8a de la release officielle **mpv-android `2026-09-17`** (`app-default-arm64-v8a-release.apk`, SHA-256 `0B0E0A534DD6B8D2B117F06952E0600064AD97702E48BFF02543E3E32107803D`). `scripts/fetch-libmpv-android.ps1` les extrait après vérification du hachage, vers un dossier ignoré par git. Le pont JNI (`libplayer.so`) exige la classe `is.xyz.mpv.MPVLib` : elle est recopiée du même tag (licence MIT, en-tête conservé) et protégée de R8.
- **UI : Jetpack Compose for TV**, ajoutée après la Gate TV-1. Le PoC n'a qu'une `Activity` et une `SurfaceView`, pour éprouver la lecture seule.
- **Outillage** : AGP 9.5.1 (Kotlin intégré), Gradle 9.9.0 par wrapper, Kotlin 2.4.21, `compileSdk`/`targetSdk` 37, `minSdk` 28. ABI unique : arm64-v8a (Shield, FireStick récentes, Nokia à confirmer).
- **Client Emby** : réécrit en Kotlin (OkHttp + kotlinx.serialization) à partir des contrats vérifiés côté Windows (ADR 0004, 0007 à 0009). Le jeton va dans l'Android Keystore. Hors du PoC.

## Alternatives
- **Tauri 2 Android** : il réutiliserait le client Emby en Rust. Mais la WebView est lente sur les box TV, la navigation à la télécommande y est fragile, et il faudrait refaire sur Android l'intégration de la vidéo native sous la WebView. Le support Android TV de Tauri est encore jeune. Rejeté.
- **Media3/ExoPlayer** : c'est le standard Android, mais le SSA/ASS n'y est que partiel, sans styles ni effets. Gardé en repli derrière `PlayerEngine` pour un format que libmpv lirait mal.
- **Compiler libmpv soi-même** (buildscripts mpv-android) : il faut Linux ou WSL et des heures de build, pour un gain nul en usage personnel. Reporté à un éventuel besoin LGPL.
- **`dev.jdtech.mpv:libmpv` (Maven Central)** : dernière version 0.4.1 de 2024, trop ancienne. Rejeté.
- **App webOS** : voir le contexte. Rejetée.

## Conséquences
- Une seconde base de code à maintenir, en Kotlin. Le client Emby sera dupliqué.
- **Licence** : la libmpv de mpv-android est GPL. Comme pour l'ADR 0002, l'usage reste strictement personnel, sans aucune distribution.
- `libplayer.so` est une variable globale unique : un seul lecteur mpv à la fois par processus, et un `create` en double tue le processus (`die`). Le moteur Kotlin doit le garantir.
- Mettre à jour libmpv : changer le tag, l'URL, le hachage et `MPVLib.kt` ensemble, puis rejouer la Gate TV-1.

## Validation
Gate TV-1 sur la Shield (192.0.2.20) : MKV local avec ASS, HEVC 10 bits, flux HTTP, décodage matériel, changement de pistes, seek, touches de la télécommande, teardown propre. Les preuves vont dans `docs/evidence/`.
