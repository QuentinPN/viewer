# Viewer

Client Windows 10/11 natif pour un serveur Emby (hébergé sur QNAP). Stack : Tauri 2, Svelte 5 + TypeScript, Rust, libmpv.

**État actuel** (voir `ROADMAP.md`) :
- Gate 0 (outillage) : validée localement.
- Gate 1 (lecture native) : chemin principal validé, plusieurs points encore à vérifier.
- Aucune intégration Emby pour l'instant. L'interface est un prototype de lecteur, pas l'application finale.

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

Le scénario de lecture Gate 1 (captures d'écran et vraie touche clavier) se lance ainsi :
```bash
powershell -ExecutionPolicy Bypass -File scripts/spike/run-gate1.ps1 -Media "D:\chemin\vers\video.mkv"
```
Les résultats arrivent dans `artifacts/gate1/` (non versionné). **Il faut toujours relire les captures** : des contrôles automatiques peuvent tous passer alors que l'image est invisible (ADR 0003).

## Documentation
- Spécifications : `PRODUCT.md`, `ARCHITECTURE.md`, `DESIGN.md`, `TESTING.md`.
- Décisions : `docs/adr/`.
- Preuves : `docs/evidence/`.
- Questions ouvertes : `docs/analyse-initiale.md`.
