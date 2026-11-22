# Tests

Ce fichier dit comment vérifier le code, ce qui ne s'automatise pas, ce qui a réellement été testé et ce qui ne l'est pas. Rien n'y est affirmé sans résultat enregistré.

## Commandes

Deux piles coexistent. `npm run verify` et le harnais E2E `run.ps1` visent encore Tauri/Rust (`src-tauri`). La porte Go (`go-app`) se lance à la main, avec un seul scénario E2E : `wails-smoke.mjs`.

### Frontend (racine)

| Commande | Effet |
|---|---|
| `npm run lint` | `biome check .` (`npm run lint:fix` corrige) |
| `npm run check` | `svelte-check` avec `--fail-on-warnings` |
| `npm run test` | `vitest run` (`npm run test:watch` en continu) |
| `npm run build` | `vite build` (produit `dist/`) |
| `npm run verify:web` | lint, check, test, build |
| `npm run verify` | `verify:web` puis `verify:rust` |

La CI (`.github/workflows/ci.yml`) lance `npx biome ci .`, `npm run check`, `npm run test`, `npm run build`, puis un job Rust avec `VIEWER_REQUIRE_LIBMPV=1`. Les releases passent par `.gitea/workflows/release.yml`.

### Rust (Tauri)

`npm run verify:rust` enchaîne trois commandes, à lancer à la main si besoin :

```
cargo fmt --manifest-path src-tauri/Cargo.toml --all -- --check
cargo clippy --manifest-path src-tauri/Cargo.toml --all-targets -- -D warnings
cargo test --manifest-path src-tauri/Cargo.toml
```

- Sur un checkout propre, lancer d'abord `npm run build` (Tauri attend `dist/`) et `scripts/fetch-libmpv.ps1` (libmpv).
- Erreur de lien `LNK1104 msvcrt.lib` : préfixer la commande par `scripts/with-msvc.ps1`.
- Sous PowerShell, passer le séparateur entre guillemets : `'--'`.
- Tests réels contre le serveur Emby, ignorés par défaut, un par un : `./scripts/with-test-account.ps1 ./scripts/with-msvc.ps1 cargo test --manifest-path src-tauri/Cargo.toml real_server_search '--' --ignored --nocapture`. Le préfixe prend `VIEWER_EMBY_URL`, `VIEWER_EMBY_USER`, `VIEWER_EMBY_PASSWORD` dans le Gestionnaire d'identifiants. Autres noms : `cargo test ... '--' --ignored --list` ; `real_server_playback_reporting_and_resume`, `real_server_home_rows`, `real_server_next_up` et `real_server_transcoding` écrivent sur le compte de test.
- Avant une release : `cargo audit` depuis `src-tauri`.

### Go (Wails v3), depuis `go-app/`

```
gofmt -l .                  # ne doit rien afficher
go vet ./...
staticcheck ./...           # outil non installé sur le poste : non vérifié
go test ./...
CGO_ENABLED=0 go build ./...   # PowerShell : $env:CGO_ENABLED='0'; go build ./...
```

Le frontend Wails se construit avec `npm run build:wails`, l'exécutable avec `npm run build:wails:exe` (`go-app/bin/viewer.exe`) : `scripts/build-wails-exe.ts` porte les seuls drapeaux de release (`-H=windowsgui`, `buildMode=release`, version de package.json, `updater.PublicKey` lu dans `go-app/build/updater-ed25519.pub`) et vérifie que l'exe contient la clé.

### Release desktop (`scripts/publish.ts`)

`node scripts/publish.ts --dry-run` fait tout sauf publier : libmpv, `verify:web`, portes Go (`gofmt -l` vide, `go vet`, `go test`, en `CGO_ENABLED=0` ; staticcheck absent), `scripts/build-wails-installer.ps1 -Build` (frontend Wails, exe, NSIS), puis deux signatures de l'installateur :

- minisign avec la clé Tauri (`~/.viewer/updater.key`, `tauri signer sign --app-version`), relue par `scripts/minisign.ts` contre la `pubkey` de `tauri.conf.json` et le `version:` du commentaire de confiance ;
- ed25519 sur `SignedMessage` (`src/release/signature.ts`) avec `~/.viewer/updater-ed25519.key`, dont la clé publique doit être celle de `go-app/build/updater-ed25519.pub`.

Il écrit `go-app/bin/latest.json` (url `file://`). Vérification par le code Go de l'app (`parseManifest` puis `Verify`) :

```
cd go-app
$env:VIEWER_RELEASE_MANIFEST="$PWD\bin\latest.json"; $env:VIEWER_RELEASE_INSTALLER="$PWD\bin\Viewer_<version>_x64-setup.exe"
go test ./internal/updater -run Test_go_release_manifest_verifies -v -count=1
```

Les vecteurs `go-app/internal/updater/testdata/signature-vectors.json` sont lus par le test Go et par `src/release/signature.test.ts` : les deux côtés doivent produire le même message et la même signature.

Les vecteurs `contracts/seasons/vectors.json` (logo saisonnier) sont lus par `src/lib/brand/season.test.ts` et par `SeasonTest` côté Android (chemin relatif `../../contracts/seasons`, pas de copie à synchroniser).

### Android TV, depuis `android-tv/`

`./gradlew testDebugUnitTest assembleDebug` (commande enregistrée le 2026-11-09, AGP 9.5.1, Gradle 9.9.0, SDK 37, minSdk 28). Libmpv Android : `scripts/fetch-libmpv-android.ps1`.

### E2E (CDP, `scripts/e2e/`)

`./scripts/e2e/run.ps1 <scénario>` lance `scripts/e2e/<scénario>.mjs` avec le compte de test. Prérequis : `cargo build --manifest-path src-tauri/Cargo.toml` et `npm run dev` ; la fenêtre s'ouvre hors écran. Le harnais pilote `src-tauri/target/debug/viewer.exe`.

- Il attaque le vrai serveur Emby ; `gate3-upnext` marque un épisode vu puis non vu.
- Il ne voit ni l'image vidéo ni le son.
- Scénarios flaky : `gate2-transcode` et `gate2-resume` ont chacun échoué une fois au premier passage, puis réussi.
- Scénarios : `ls scripts/e2e/*.mjs` (gate2 à gate7, `measure-*`, `requests-ui`, hors fichiers de support `app`, `scenario`, `net-proxy`, `dev-styles`).
- Version Go : `node scripts/e2e/wails-smoke.mjs [--build] [--offline]` pilote le build **debug** Wails avec le compte de `debug.env`. Il écrit puis efface une position sur le compte de test. Il couvre la connexion, les illustrations `/emby-img/` et `/requestimg/`, la lecture (`player://event`, hwdec, pistes), une capture PrintWindow de la seule fenêtre de l'app, Espace et → envoyés par CDP, le point de reprise, Demander en lecture seule, la fermeture pendant la lecture et la déconnexion. Wails efface `WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS` : en debug seulement, `VIEWER_E2E_BROWSER_ARGS` le remplace. Dernier passage le 2026-11-22 : 33/33 sur le vrai serveur.

### Bancs de lecture (`scripts/spike/`)

`run-gate1.ps1` et `close-probe.ps1` (Tauri), `run-gate1-go.ps1` (Go/Wails, à lancer avec `pwsh`), `run-gate-tv1.ps1` (Shield), `serve-file.mjs` (fichier local en HTTP), `emby-remote-control-probe.mjs` (relais de commandes Emby).

## Ce qu'il faut tester à la main

Les points ci-dessous ne s'automatisent pas : image et son réels, matériel, humain devant l'écran.

### Image

- Un flux 4K HDR10 fait basculer la TV en HDR ; les couleurs sont justes.
- Dolby Vision : couleurs correctes (ou repli propre, la Shield 2017 n'en a pas).
- Un film entier sans saccade ni surchauffe, sur le PC puis sur la Shield.
- Modes d'aspect, redimensionnement, plein écran, changement d'écran.
- Windows en mise à l'échelle 125 % et 150 %, multi-écrans, DPI mixtes.

### Son

- AC3 5.1 et AAC stéréo, y compris AAC 44,1 kHz sur la Shield (repli OpenSL ES).
- DTS et DTS-HD en bitstream vers l'ampli ; sortie multicanale.
- Décalage audio, mode nuit, changement de piste audio en cours de lecture.

### Sous-titres

- SRT, PGS et ASS : apparence et position, pendant et hors affichage des contrôles.
- Sous-titre externe conservé, décalage de sous-titre, passage SRT sur un flux 4K (attente de cache connue).

### Télécommande et clavier

- Appui maintenu sur les flèches (pas de 10, 30, 60 s), touches média, recherche vocale, Accueil puis reprise.
- Build Wails : focus visible, Espace (pause) et Échap, au vrai clavier.

### Réseau réel

- Couper puis rétablir le réseau (câble, Wi-Fi) pendant une lecture ; forcer l'arrêt de l'app ; reprise à la bonne position, y compris des heures après.
- États d'erreur de la grille et de la recherche ; serveur Emby passé en HTTPS.

### Parcours

- Enchaînement d'épisodes : carte « épisode suivant », saut du générique, minuterie.
- Plusieurs versions d'un même média et plafond de qualité.
- Installeur sur machine Windows propre (Wails ou NSIS) : SmartScreen, WebView2 absent, installation, lancement, connexion, lecture, désinstallation.
- HEVC 10 bits et HDR sur le bureau.

## Combinaisons testées

Résultats réellement enregistrés. Tauri en build debug sauf mention. « Image non vue » : mesure par journaux, sans contrôle visuel.

| Date | Plateforme / matériel | Codec, conteneur, sortie | Résultat |
|---|---|---|---|
| 2026-10-27 | Win10 22H2, libmpv | MKV multipiste AAC | Son audible, souris et clavier OK (rapporté par le propriétaire) ; fermeture 154–189 ms, 4/4 sans erreur 1412 |
| 2026-10-28 | RTX 3080 | Direct Play MKV H.264 1920x800 et 1920x1080, d3d11va | Position qui avance ; image non vue |
| 2026-10-28 | RTX 3080 | Transcodage HLS (plafond 3 Mbit/s) H.264 1280x532, ac3, SRT externe | E2E 10/10 |
| 2026-11-06 | Ryzen 5 5600X / RTX 3080, fenêtre hors écran, son coupé | MKV direct H.264 et HEVC jusqu'à 3840x1606 (11 films), audio eac3/dts/ac3/opus | Démarrage médian 1184 ms (965–2144 ms), 0 image perdue sur 60 s ; ne prouve pas la fluidité |
| 2026-11-06 | libmpv réel | HTTPS dans le lecteur | Nom invalide, certificat expiré et auto-signé refusés ; certificat public accepté ; chemin complet dans l'app jamais exécuté |
| 2026-11-06 | relais réseau local | Coupures pendant lecture | Coupure de 8 s survécue ; coupure longue annoncée après 51,6 s (direct) ou 173,9 s (HLS) ; HLS avec renvoi du rapport d'arrêt 11/11, annoncée après 236,0 s, position gardée, aucun « vu » envoyé, reprise proposée |
| 2026-11-06 | build debug, proxy local `log-segments`, plafond 3 Mbit/s, serveur Emby réel | Pause 5 min pendant un transcodage | Reprise sans erreur (pause 30 min abandonnée) |
| 2026-11-06 | build debug, relais local `net-proxy.mjs` servant des octets illisibles (pas de vrai fichier corrompu) | Repli en transcodage si le fichier direct est illisible | 14/14 |
| 2026-11-06 | serveur Emby réel 4.10.2.0, compte de test, second appareil relevant `/Sessions` toutes les 10 s | Transcodage abandonné sans rapport d'arrêt | Session de transcodage disparue après 50 à 70 s, sur 4 essais |
| 2026-11-06 | libmpv v0.41.0 | Revue de sécurité du lecteur | Liste blanche de protocoles, `max_redirects=0`, `player_open` refusé hors dev |
| 2026-11-07 | Win10 du propriétaire | Installeur NSIS Tauri 35,6 Mio, H.264 1920x800 | Installation, lancement, connexion, lecture (première image 5,1 s, seek à 600 s en 1,3 s, sortie 0), désinstallation propre ; son coupé, image non vue, pas de machine propre, WebView2 déjà présent, installation virtualisée MSIX |
| 2026-11-07 | serveur Emby | Chapitres, marqueurs | 16 chapitres lus sur un MKV direct ; 0 marqueur intro/générique sur 400 films et 400 épisodes |
| 2026-11-09 | Shield TV 2017 (Android 11, arm64, 3 Go, 4K 59,94 Hz, HDR10), libmpv, mediacodec, audiotrack | HEVC 1080p 10 bits, AAC stéréo, ASS | Moteur prêt 65–114 ms, fichier chargé en 1,3–1,9 s, 0 image perdue, ASS visible, fermeture 130–292 ms |
| 2026-11-09 | idem | HEVC Main10 4K HDR10, AC3 5.1 | gamma pq, bt.2020, 0 image perdue sur 2 min, seek +60 s sans attente de cache, pause et changement de piste audio OK, fermeture 309 ms ; passage en SRT : attente de cache 4 à 8 s, 5 images perdues |
| 2026-11-09 | idem | Surcouche du lecteur TV (pause, seek, menu pistes, ASS, retour) | OK ; aucun jeton Emby dans logcat |
| 2026-11-14 | Bureau debug + Shield liée par le relais Emby `SendString` (Emby 4.10.2.0) | Synchronisation de lecture | Liaison en 5,3 s ; dérive en régime établi n=118, médiane 49 ms, p95 230 ms, max 250 ms (échec face à la cible p95 < 150 ms) ; retour sous 60 ms après +10 s en 1,7 s, après pause/reprise en 7,8 s ; commandes TV vers bureau 3–404 ms |
| 2026-11-22 | viewer-pc, Node 26.13.1, Go 1.27.4, makensis du cache Tauri | `node scripts/publish.ts --dry-run` (version 1.5.0, branche `ci/release-wails`) | Sortie 0 : verify:web, gofmt, vet, test, installateur Wails 42,4 Mio. Minisign relue par `minisign-verify` 0.3.0 (crate du plugin, même chemin que `verify_signature`) contre la `pubkey` de tauri.conf.json, `version:1.5.0` signé, octet ajouté refusé. Ed25519 vérifiée par `Test_go_release_manifest_verifies`. Installateur non exécuté, rien publié |
| 2026-11-22 | Win10 22H2, RTX 3080, WebView2 157, Go 1.27.4, Wails v3.1.0, `CGO_ENABLED=0` ; spike, pas l'app complète | H.264 720p30 + AAC synthétiques (fichier local et HTTP local), gpu-next/d3d11, d3d11va | Chargement et lecture 1,35 s (local) et 1,37 s (HTTP), 0 image perdue, vidéo visible sous la surcouche HTML (captures revues), piste audio 2 vers 1 OK, ASS OK, redimensionnement et plein écran OK, seek 30 s OK, arrêt 54–60 ms, sortie < 0,6 s code 0, fermeture par la croix 3/3 en 112–153 ms sans erreur 1412 ; touche Espace non vérifiée |

La dérive de la ligne du 2026-11-14 est l'estimation de la TV elle-même (`time-pos` de mpv), sans horloge partagée ni caméra.

## Limites connues

- Synchro TV : p95 de dérive d'environ 230 ms pour une cible de 150 ms ; à la vitesse 1 exacte, mpv retire `scaletempo2` et `time-pos` saute d'environ 300 ms ; après un changement de vitesse, `time-pos` est faussé un moment.
- Shield : AudioTrack refuse l'AAC stéréo 44,1 kHz (repli OpenSL ES) ; attente de cache de 4 à 8 s au changement de sous-titre en 4K ; pas de Dolby Vision ; DTS, bitstream, PGS et AV1 non couverts ; transcodage, drapeau HDR et sortie multicanale non vérifiés sur la TV.
- TV : les sous-titres mpv passent sous la couche Compose et chevauchent la barre quand les contrôles sont affichés ; la touche INFO n'existe pas sur la télécommande.
- Le renvoi du rapport d'arrêt après coupure réseau n'a jamais été vérifié à la main sur la Shield (tests unitaires seulement).
- Aucun résultat de lecture pour l'application Wails complète : la ligne Go est un spike. Clavier, focus, Espace et Échap non vérifiés ; la zone cliente fait 1 px de plus ; le rendu ASS « Forced » n'est pas vérifié. Installeur Wails jamais exécuté : la mise à jour d'une app Tauri installée vers l'installateur Wails (`/S /UPDATE /R /ARGS`) est lue dans le code du plugin et du `.nsi`, pas observée. Pipeline de release exécuté en essai à blanc local seulement, pas encore sur le runner.
- Bureau : aucun rendu HEVC 10 bits, HDR ou Dolby Vision enregistré ; pas de machine Windows propre ; SmartScreen non testé ; mise à l'échelle 125 et 150 %, multi-écrans et DPI mixtes non testés.
- Coupures réseau : seul le relais local a servi, jamais de vrai débranchement.
- Le premier transcodage d'un film peut prendre environ 1 min (extraction des sous-titres). Un film sur un partage NAS périmé donnait un transcodage qui ne s'ouvrait jamais, corrigé depuis par un délai d'ouverture. Après une longue coupure HLS, la reprise revient à la position de la coupure.
- Prévisualisation de seek non testée (miniatures Emby désactivées). Le relais Emby répond 204 pour une session disparue, et `/Command/SendString` perd `Arguments` dans le corps.
- E2E : Tauri uniquement, sans image ni son, scénarios parfois instables.
- Licence : la build libmpv est GPL v3 (x264/x265, rubberband), déduite de la recette et confirmée seulement en partie par les chaînes des DLL ; usage personnel uniquement, ce n'est pas un avis juridique.
- `staticcheck` absent du poste ; la porte Go n'est pas dans `npm run verify`.

Les nouvelles preuves (captures, journaux) vont dans la description de la PR ou un dossier temporaire, pas dans le dépôt ; ne consigner ici que le résultat, en une ligne de tableau.
