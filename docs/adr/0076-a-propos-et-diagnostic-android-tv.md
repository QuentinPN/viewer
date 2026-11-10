# ADR 0076 — « À propos » et diagnostic dans les réglages (Android TV)

Statut : Accepté (2026-11-10). Étend la section « À propos » des réglages TV (ADR 0051), qui ne montrait que le numéro de version ; complète le panneau « Informations de lecture » du lecteur (ADR 0061).

## Contexte
Pour savoir pourquoi un fichier passe en transcodage ou se lit mal sur une TV, il faut connaître l'appareil, la version d'Android, le serveur, et ce que les décodeurs de l'appareil disent savoir faire (HEVC 10 bits, AV1, HDR10, Dolby Vision). CLAUDE.md demande une compatibilité honnête, avec des diagnostics. Jusqu'ici, rien de cela n'était visible sans adb.

## Décision
- **Fin de l'écran « Réglages »**, section « À propos » en quatre groupes de lignes en lecture seule. Chaque ligne est un `ListItem` qui prend le focus (OK n'y fait rien), pour que ▲ ▼ y descendent à la télécommande.
- **Application** : « Version » `versionName (versionCode)` et « Compilation » « Débogage » ou « Publication ». Lus par `PackageManager` (`longVersionCode`, API 28) et le drapeau `FLAG_DEBUGGABLE`, plutôt que `BuildConfig` : le module ne l'active pas, et l'activer pour deux valeurs touche la configuration Gradle.
- **Appareil** : « Modèle » `Build.MANUFACTURER` + `Build.MODEL` (sans répéter la marque quand le modèle commence par elle), « Système » « Android 11 (API 30) ».
- **Serveur** : nom et version d'Emby lus par `GET /System/Info/Public` (`ServerName`, `Version`), la fonction `EmbyClient.publicInfo()` déjà utilisée à la connexion. C'est le seul appel « informations système » du dépôt : Windows (`src-tauri/src/emby/client.rs`, `public_info`), son faux serveur (`src-tauri/src/emby/tests.rs`, `public_info()`) et la spec OpenAPI du serveur relevée en Gate 2 (`docs/evidence/gate2-2026-10-27.md`). `/System/Info` authentifié n'est utilisé nulle part et n'est pas vérifié ; il n'est pas introduit. Aucune fonction n'est ajoutée à `EmbyClient`.
  - Un appel par ouverture de l'écran (`produceState`), annulé quand l'écran se ferme. États : « Chargement… », valeurs lues, ou la raison en français (« Serveur injoignable », « Réponse inattendue du serveur », « Session expirée… »).
  - « Adresse » : schéma, hôte et port non standard de la session (`http://192.0.2.14:8096`), jamais le chemin, une requête ou le jeton.
- **Lecture** : les décodeurs **annoncés par l'appareil** (`MediaCodecList`, `REGULAR_CODECS`, sans encodeurs ni alias), une ligne par codec : HEVC (H.265), AV1, VP9, H.264, puis Dolby Vision.
  - Valeur : « Matériel » quand un décodeur matériel existe (`isHardwareAccelerated` à partir de l'API 29 ; avant, les décodeurs logiciels d'Android sont reconnus à leur nom `OMX.google.*`, `c2.android.*`, `c2.google.*`), sinon « Logiciel seulement », sinon « Aucun décodeur ». Puis ce que leurs profils annoncent : « 10 bits », « HDR10 », « HDR10+ » ; les noms des décodeurs retenus en dessous.
  - Dolby Vision : décodeurs du type `video/dolby-vision`, avec les numéros de profil annoncés (« profils 5, 8 »), ou « Non annoncé ».
  - « Écran » : formats HDR annoncés par l'affichage par défaut (`Display.Mode.getSupportedHdrTypes` à partir de l'API 34, `Display.getHdrCapabilities` avant) : sur un boîtier, c'est ce que la TV derrière le HDMI déclare.
  - Une note le dit en clair : une indication, pas une garantie. Un décodeur peut lire du HDR10 en n'annonçant que Main 10 ; la lecture dépend aussi du fichier, du débit et de la chaîne HDMI.
  - La liste est lue hors du fil de l'interface (`Dispatchers.Default`), une fois par ouverture ; « Lecture en cours… » d'ici là.
- **Logique pure testée** : `settings/About.kt` (lignes de codec, profils Dolby Vision, formats HDR de l'écran, textes de version, d'appareil, d'adresse et du serveur). Les valeurs des profils `MediaCodecInfo.CodecProfileLevel` et des types `Display.HdrCapabilities` y sont recopiées, vérifiées contre `android.jar` (API 37) : certaines sont de l'API 29 (minSdk 28, avertissement lint `InlinedApi`) et les tests tournent sur la JVM. La lecture Android est dans `settings/DeviceReport.kt`.

## Alternatives
- **Versions de libmpv et FFmpeg** (`mpv-version`, `ffmpeg-version`) : `MPVLib` de mpv-android est une instance unique du processus ; la créer dans les réglages entrerait en conflit avec le lecteur et chargerait libmpv pour deux chaînes de caractères. Écarté ; le panneau « Informations de lecture » (ADR 0061) peut les montrer plus tard, mpv y étant déjà lancé.
- **`/System/Info` authentifié** (système d'exploitation du serveur, chemins, transcodage matériel) : jamais appelé ni vérifié dans ce dépôt, et il expose des chemins du serveur sans utilité ici. Écarté.
- **`BuildConfig`** : demande `buildFeatures.buildConfig = true` ; `PackageManager` donne les mêmes valeurs. Écarté.
- **Résumé « peut lire le HDR10 : oui »** : promettrait une lecture que seule la chaîne complète décide. Écarté au profit de « annoncé ».

## Conséquences
- `SettingsScreen` reçoit le client Emby au lieu du numéro de version ; `MainActivity` le lui passe.
- Ouvrir les réglages fait un appel réseau léger de plus, sur un point d'accès qui ne demande pas d'authentification (l'en-tête d'autorisation de la session part comme pour les autres appels, jamais dans l'URL).
- Réversible : retirer les groupes ne touche à rien d'autre.

## Vérification
- Tests unitaires `AboutTest` (11) : décodeurs matériels avant logiciels, HDR10 / HDR10+ / 10 bits pour HEVC, VP9, AV1 et H.264, codec absent, plusieurs décodeurs nommés, profils Dolby Vision, ordre des lignes, noms logiciels avant l'API 29, formats HDR de l'écran, textes de version et d'appareil, adresse sans chemin ni requête (IPv6 comprise), lignes du serveur pour chaque état et raisons d'erreur. `testDebugUnitTest` (342 tests), `assembleDebug` et `lintDebug` passent, sans avertissement lint dans les fichiers modifiés.
- Non vérifié sur le Shield : le rendu et le défilement des lignes, la réponse réelle de `/System/Info/Public` depuis la TV, et ce que `MediaCodecList` et `Display` annoncent sur le Shield (décodeurs NVIDIA, Dolby Vision, HDR de la TV branchée).
