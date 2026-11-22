# Viewer

Client Windows 10/11 natif pour un serveur Emby (hébergé sur un mini-PC, médias sur un NAS QNAP). Stack : Go + Wails v3 (`go-app/`), Svelte 5 + TypeScript, libmpv. Depuis la v1.7.0, l'installateur publié est l'app Go ; l'ancienne app Tauri 2 + Rust a été retirée du dépôt après cette version.

**État actuel** :
- Gate 0 (outillage) : validée localement ; la CI n'a encore jamais tourné sur GitHub.
- Gate 1 (lecture native) : chemin principal validé. Restent à vérifier notamment HEVC, AV1, 4K, HDR et les écrans à 125/150 %.
- Gate 2 (intégration Emby) : connexion, bibliothèques, fiches, lecture directe, transcodage et reprise validés.
- Gate 2.1 (session persistante, perte de connexion) : validée.
- Gate 3 (expérience principale) : accueil, recherche, tri et filtres, favoris, pages d'épisode, lecteur final, épisode suivant, cache des images et réglages du cache validés. Les autres sections des réglages restent à faire.
- Gate 4 (qualité de bout en bout) : en cours. La reconnexion après une coupure réseau est faite, le reste est à faire.
- Gate 5 (empaquetage) : pas commencée, bloquée par le choix de la licence (la build libmpv actuelle est sous GPL). Il n'existe donc pas encore d'installeur.

L'application se lance depuis les sources et fonctionne avec un vrai serveur Emby.

## Prérequis (Windows x64)
- Node ≥ 22 et npm.
- Go, à la version de `go-app/go.mod`. Pas de cgo : tout se construit avec `CGO_ENABLED=0`, sans compilateur C.
- WebView2 Runtime (inclus dans Windows 11).
- Pour l'installateur seulement : NSIS 3.x (`makensis`).

## Démarrage
```bash
npm ci
```
```bash
powershell -ExecutionPolicy Bypass -File scripts/fetch-libmpv.ps1
```
```bash
node scripts/e2e/app.mjs --build
go-app/bin/viewer-debug.exe
```
`fetch-libmpv.ps1` télécharge la build libmpv épinglée dans `vendor/mpv/` et vérifie son SHA-256. `app.mjs --build` lance `npm run build` (le frontend va dans `go-app/cmd/viewer/frontend/dist`, embarqué par l'exe), puis un `go build` debug. Le build debug trouve libmpv dans `vendor/mpv/` et garde ses données à part (identifiant `io.github.quentinpn.viewer.debug`). `npm run dev` ne sert qu'à voir l'interface dans un navigateur : sans l'app Go, aucune commande native ne répond.

## Vérifications
```bash
npm run verify
```
Cette commande enchaîne Biome, svelte-check, Vitest, le build Vite, puis `gofmt -l`, `go vet` et `go test` dans `go-app` (`CGO_ENABLED=0`).

Les scénarios de bout en bout pilotent le build debug Go par CDP, fenêtre hors écran, sur un compte de test Emby. Ils demandent le compte de test dans le Gestionnaire d'identification Windows (voir `TESTING.md`, qui indique aussi lesquels écrivent sur le serveur) ; `-Build` reconstruit l'exe debug avant :
```bash
powershell -ExecutionPolicy Bypass -File scripts/e2e/run.ps1 gate2-play -Build
```

Le banc de lecture Gate 1 (spike Go, captures de la seule fenêtre du spike) se lance ainsi :
```bash
pwsh scripts/spike/run-gate1-go.ps1 -Media "D:\chemin\vers\video.mkv"
```
Les résultats arrivent dans `artifacts/gate1-go/` (non versionné). **Il faut toujours relire les captures** : des contrôles automatiques peuvent tous passer alors que l'image est invisible.

## Documentation
- Produit : `PRODUCT.md`.
- Direction visuelle : `DESIGN.md`.
- Décisions en vigueur et leur raison : `DECISIONS.md`.
- Tests, combinaisons testées et limites connues : `TESTING.md`.
- L'historique (anciens ADR, preuves) reste dans l'historique git ; les preuves d'une nouvelle fonctionnalité vont dans sa PR.
