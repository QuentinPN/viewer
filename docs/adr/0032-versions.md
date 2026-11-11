# ADR 0032 — Numéros de version et montées de version

Statut : Accepté (2026-11-07), à la demande du propriétaire.

## Contexte
L'application restait en 0.1.0 : chaque installateur portait le même numéro, et rien ne distinguait la version installée. Le numéro était écrit à trois endroits (`package.json`, `src-tauri/Cargo.toml`, `src-tauri/tauri.conf.json`), plus les deux fichiers de verrouillage.

## Décision
- **SemVer, en 0.x tant que le périmètre de `PRODUCT.md` n'est pas complet et vérifié.** Avant 1.0 : une nouveauté visible monte le mineur (0.3.0 → 0.4.0), une version faite seulement de correctifs monte le correctif (0.3.0 → 0.3.1). La 1.0 est la première version qui couvre tout le périmètre, portes de `ROADMAP.md` vérifiées.
- **Source unique : `package.json`.** `tauri.conf.json` pointe dessus (`"version": "../package.json"`), ce qui donne le numéro de l'installateur et de l'entrée de désinstallation. Cargo exige un numéro littéral : `Cargo.toml` et `Cargo.lock` sont tenus égaux par le script, et un test Vitest échoue s'ils divergent.
- **`npm run release -- <patch|minor|major|X.Y.Z>`** (`scripts/release.mjs`) :
  - refuse un arbre de travail modifié et un numéro qui ne monte pas ;
  - écrit le numéro dans `package.json`, `package-lock.json`, `Cargo.toml` et `Cargo.lock` ;
  - ajoute en tête de `CHANGELOG.md` une section datée avec les sujets des commits depuis le dernier tag ;
  - commite « Release vX.Y.Z » et pose le tag annoté `vX.Y.Z`. Il ne pousse rien.
- **Le numéro est affiché** dans Réglages, section « À propos », lu par `app_info` (`CARGO_PKG_VERSION`).
- **Historique** : tags posés après coup sur les versions déjà installées : `v0.1.0` (6d3450d, premier installateur), `v0.2.0` (c844da3, icône, barre de titre, passer intros et génériques), `v0.3.0` (0c0b004, direction Apple TV), puis `v0.3.1` pour le correctif du plein écran.

## Alternatives
- **`npm version`** : ne touche ni Cargo ni le journal des changements. Rejeté.
- **Numéro dérivé de git (`git describe`) au build** : l'installateur NSIS exige un numéro SemVer fixe, et un build hors dépôt n'en aurait pas. Rejeté.
- **Outil de release (release-please, cargo-release)** : dépendance de plus pour un seul paquet par écosystème, sans CI (Actions désactivées). Rejeté.

## Conséquences
- L'installateur NSIS de Tauri remplace une version plus ancienne déjà installée ; le numéro de l'entrée de désinstallation suit.
- Construire un installateur se fait après `npm run release`, pour que son numéro corresponde au tag : `npm run release:publish` le construit et le publie en release GitHub (ADR 0106).
- Android TV (2026-11-11) : version propre à l'app TV, `versionName` et `versionCode` dans `android-tv/app/build.gradle.kts`, journal dans `android-tv/CHANGELOG.md` ; chaque APK livré sur la Shield augmente la version (1.0.0, code 2, le 2026-11-11). Publication par `npm run android:publish`, tags `android-vX.Y.Z` (ADR 0107).
