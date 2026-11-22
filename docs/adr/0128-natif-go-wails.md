# ADR 0128 — Natif en Go + Wails v3 à la place de Rust + Tauri

Statut : Accepté pour la migration (2026-11-22, spike validé : `docs/evidence/gate1-go-2026-11-22.md`). Le basculement de l'app livrée reste à décider par le propriétaire (`go-app/MAP.md`).

## Contexte
Le build release Rust prend 3 à 5 minutes à froid : 5 min 19 s dans la CI du 2026-11-21, 3 min 14 s en local. La CI desktop atteignait 9 min 30 s. La branche `chore/faster-desktop-build` traite déjà une partie du problème : dossier `target` persistant et LTO thin. Le propriétaire veut évaluer une migration du natif vers Go, dont la compilation est beaucoup plus rapide. Svelte, l'app Android TV et le serveur Emby ne changent pas.

Surface à porter :
- 26 948 lignes de Rust, dont environ 7 400 de tests ;
- 311 tests, 80 commandes et 7 événements ;
- l'intégration libmpv par le HWND de la fenêtre principale (ADR 0003, mode `parent`).

## Décision proposée
- Natif en **Go**, avec **Wails v3** (v3.1.0, stable depuis le 2026-11-01), compilé **sans cgo** (`CGO_ENABLED=0`).
- libmpv-2.dll est chargée par `syscall`. La boucle d'événements mpv tourne dans une goroutine.
- Composition vidéo identique à l'ADR 0003 : fenêtre sans bordure et transparente, HWND principal passé à mpv par `wid`, clavier gardé dans la WebView.
- Migration par vagues, dans `go-app/`. `src-tauri/` reste l'app livrée et la référence jusqu'au basculement.
- La fidélité se prouve par les tests Rust portés en Go et par des « golden » JSON : les formes sérialisées des commandes et des événements, produites depuis les fixtures existantes.

## Condition de passage (spike, gate 1 rejoué)
Le spike `go-app/cmd/spike` doit reproduire sous Wails les contrôles de l'ADR 0003 :
- vidéo visible sous une interface HTML semi-transparente ;
- redimensionnement, plein écran, pistes audio et sous-titres, décodage matériel, flux HTTP ;
- fermeture propre, et un aller-retour commande/événement avec une erreur structurée ;
- mesure du temps de build.

Comme en 2026-10, des captures d'écran font foi, et pas seulement les contrôles automatiques. Si le spike échoue sur la composition ou la fermeture, cet ADR passe à « Rejeté » et Tauri reste en place.

## Conséquences
- Si l'ADR est accepté, il remplacera les ADR 0001 (socle), 0004 (client Emby en Rust), 0112 (mises à jour), 0028 (installateur) et 0029 (barre de titre), ainsi que la base technique du CLAUDE.md. Il le fera vague par vague, au fil du portage.
- Mises à jour : le système de mise à jour de Wails vérifie les signatures ed25519, et non minisign. Il faudra soit une dernière release Tauri qui installe la version Wails, soit une réinstallation manuelle unique, acceptée par le propriétaire.
- Jeton : si Go ne relit pas l'entrée du Gestionnaire d'identification écrite par le crate `keyring`, il faudra une reconnexion unique, acceptée.
- Jusqu'au basculement, les commits sont de type `refactor`/`chore` et ne déclenchent aucune release.
