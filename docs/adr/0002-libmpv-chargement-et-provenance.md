# ADR 0002 — libmpv : chargement dynamique, provenance et licence

Statut : Accepté pour le développement. La licence de redistribution reste **ouverte** (bloque la Gate 5).

## Contexte
Le moteur de lecture est libmpv (CLAUDE.md). Il faut une DLL Windows reproductible, une intégration Rust maintenable, et une stratégie de licence compatible avec la distribution future.

## Décision
- **Build** : `mpv-dev-x86_64-20261021-git-e471496c56.7z`, publiée par shinchiro/mpv-winbuild-cmake (release `20261021`). API client 2.6.
  - SHA-256 de l'archive : `efc719eff3dc5954b4a5efc800632808bcec01cc9034940dd14e5eb7fe9ea633`
  - SHA-256 de `libmpv-2.dll` : `04e4d27dc385dc18ddd93fbe69c75e635b864f3c6ec53a9122df54c2f7842f4a`

  `scripts/fetch-libmpv.ps1` télécharge l'archive, vérifie les deux hachages et refuse toute divergence. `vendor/` est ignoré par git.
- **Chargement dynamique** (`libloading`) plutôt que lien statique avec `mpv.lib` : pas de bibliothèque d'import à générer, et l'application peut démarrer puis afficher un diagnostic clair si la DLL manque (`PlayerError::LibraryUnavailable`). La version majeure de l'API (2) est vérifiée au chargement.
- **Binding minimal maison** (`src-tauri/src/player/ffi.rs`, 14 fonctions) plutôt que la crate `libmpv2` : surface réduite, auditable, sans dépendance au linker.
- **Durcissement des options mpv** : `config=no`, `load-scripts=no`, `ytdl=no`, `osc=no`, bindings clavier/souris désactivés, `hwdec=auto-safe`. Côté IPC, seuls les chemins locaux absolus et `http(s)` sont acceptés : `av://`, `edl://`, `file://`, etc. sont refusés (`MediaSource::parse`).
- **Authentification HTTP par en-tête** (`http-header-fields`), jamais dans l'URL. Les logs mpv passent par un filtre qui masque `api_key=`, `X-Emby-Token` et `X-Emby-Authorization`.

## Alternatives
- Crate `libmpv2` : rejetée (lien à l'import lib, abstraction plus large que nécessaire).
- Build LGPL maison (`-Dgpl=false`) : nécessaire pour une distribution non-GPL, coût de build élevé. Reporté à la Gate 5.
- Processus `mpv.exe` séparé piloté par IPC JSON : isolation des crashs, mais intégration fenêtre et latence moins bonnes. Gardé en repli si l'embarquement devient instable.

## Conséquences
- **Licence** : les builds shinchiro incluent des composants GPL. Redistribuer Viewer avec cette DLL impose une distribution compatible GPL. L'utilisateur a choisi « usage perso, à trancher ». Avant toute distribution : choisir entre GPL-3.0 pour Viewer et une libmpv LGPL compilée par nos soins, puis auditer les licences FFmpeg et des codecs (Gate 5).
- Mettre à jour libmpv implique de changer la release, le nom de l'archive et les deux hachages ensemble, puis de rejouer la Gate 1.

## Validation
- Test `headless_engine_lifecycle`, exécuté contre cette DLL : chargement, lecture lavfi, seek, sous-titre externe, pause, stop, teardown idempotent.
- `missing_library_is_reported` couvre le cas de la DLL absente.
- `fetch-libmpv.ps1` testé depuis un `vendor/` supprimé, puis relancé (chemin idempotent).
