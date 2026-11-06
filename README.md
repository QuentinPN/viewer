# Viewer

Client Windows 10/11 natif pour un serveur Emby (hébergé sur QNAP). Stack : Tauri 2, Svelte 5 + TypeScript, Rust, libmpv.

**État actuel** (voir `ROADMAP.md`) :
- Gate 0 (outillage) : validée localement ; la CI n'a encore jamais tourné sur GitHub.
- Gate 1 (lecture native) : chemin principal validé. Restent à vérifier notamment HEVC, AV1, 4K, HDR et les écrans à 125/150 %.
- Gate 2 (intégration Emby) : connexion, bibliothèques, fiches, lecture directe, transcodage et reprise validés.
- Gate 2.1 (session persistante, perte de connexion) : validée.
- Gate 3 (expérience principale) : accueil, recherche, tri et filtres, favoris, pages d'épisode, lecteur final, épisode suivant, cache des images et réglages du cache validés. Les autres sections des réglages restent à faire.
- Gate 4 (qualité de bout en bout) : en cours. La reconnexion après une coupure réseau est faite, le reste est à faire.
- Gate 5 (empaquetage) : pas commencée, bloquée par le choix de la licence (la build libmpv actuelle est sous GPL, ADR 0002). Il n'existe donc pas encore d'installeur.

L'application se lance depuis les sources et fonctionne avec un vrai serveur Emby.

## Prérequis (Windows x64)
- Node ≥ 22 et npm.
- Rust stable MSVC (`rustup`, composants `rustfmt` et `clippy`).
- Visual Studio Build Tools avec « MSVC x64/x86 » et un Windows SDK.
- WebView2 Runtime (inclus dans Windows 11).

## Démarrage
```bash
npm ci
```
```bash
powershell -ExecutionPolicy Bypass -File scripts/fetch-libmpv.ps1
```
```bash
npm run tauri dev
```
`fetch-libmpv.ps1` télécharge la build libmpv épinglée dans `vendor/mpv/` et vérifie son SHA-256.

Si `cargo` échoue avec `LNK1104 msvcrt.lib`, une installation Visual Studio incomplète est choisie par défaut. Préfixer alors les commandes par `scripts/with-msvc.ps1` (voir ADR 0001).

## Vérifications
```bash
npm run verify
```
Cette commande enchaîne Biome, svelte-check, Vitest, le build Vite, `cargo fmt --check`, clippy en `-D warnings` et `cargo test` (avec un test moteur réel sur libmpv).

Les scénarios de bout en bout pilotent le build debug, fenêtre hors écran, sur un compte de test Emby. Ils demandent `cargo build`, le serveur Vite (`npm run dev`) et le compte de test dans le Gestionnaire d'identification Windows (voir `TESTING.md`, qui indique aussi lesquels écrivent sur le serveur) :
```bash
powershell -ExecutionPolicy Bypass -File scripts/e2e/run.ps1 gate2-play
```

Le scénario de lecture Gate 1 (captures d'écran et vraie touche clavier) se lance ainsi :
```bash
powershell -ExecutionPolicy Bypass -File scripts/spike/run-gate1.ps1 -Media "D:\chemin\vers\video.mkv"
```
Les résultats arrivent dans `artifacts/gate1/` (non versionné). **Il faut toujours relire les captures** : des contrôles automatiques peuvent tous passer alors que l'image est invisible (ADR 0003).

## Documentation
- Spécifications : `PRODUCT.md`, `ARCHITECTURE.md`, `DESIGN.md`, `TESTING.md`.
- État détaillé et historique : `ROADMAP.md`.
- Décisions : `docs/adr/`.
- Preuves : `docs/evidence/`.
- Questions ouvertes : `docs/analyse-initiale.md`.
