# Hand-off — Lecture liée ordi ↔ Shield TV (partage Discord)

## Besoin (mots du propriétaire)

> « Je stream mon écran à mes amis sur discord pour regarder des épisodes d'animés, j'aimerais pouvoir leur stream mon écran, et moi regarder sur ma télé en même temps (synchronisé) l'épisode qu'eux voient sur mon partage d'écran, et si je fait une action sur la TV ça la fait sur l'ordi aussi (ex, pause, skip >>>, ou <<<, skip intro etc.) »

Il a demandé un plan avant de coder ; le plan ci-dessous a été présenté. **Étape 0 faite le 2026-11-14** : le relais Emby convient (ADR 0123, preuve `docs/evidence/synchro-tv-relais-emby-2026-11-14.md`). **Étapes 1 à 6 faites le 2026-11-14** (ADR 0124, desktop 1.4.0, Android TV 1.3.0). Test réel : `docs/evidence/synchro-tv-reel-2026-11-14.md`. Tout passe sauf la dérive en régime établi : p95 230 ms pour une cible de 150 ms. L'écart restant vient de `time-pos` de mpv sur la Shield, pas du relais.

## Constat dans le dépôt

- Desktop (Tauri 2 + Svelte 5, Rust) et Android TV (Kotlin + Compose, `android-tv/`) lisent tous deux via **libmpv** (`src-tauri/src/player`, `android-tv/.../player/MpvPlayerEngine.kt`) → propriété `speed` disponible des deux côtés.
- Passer l'intro / générique existe déjà : desktop `src/lib/player/segments.ts` + `src-tauri/src/segments`, TV `player/Skip.kt`. Chapitres : `chapters.ts` / `Chapters.kt`.
- WebSocket Emby déjà en place (ADR 0110) : desktop `src-tauri/src/emby/library_events.rs` (`tokio-tungstenite`), TV `emby/LibraryEvents.kt` (OkHttp). **Ouverte seulement sur l'accueil** aujourd'hui. Jeton uniquement dans l'URL de la socket (seule voie qui reçoit des messages, constaté sur Emby 4.10.2.0), jamais exposé à l'UI ni journalisé.
- Rapports de lecture déjà envoyés : `Sessions/Playing`, `/Progress`, `/Stopped` (desktop `emby/reporting.rs`, TV `PlaybackTracker.kt`).
- Aucun code de contrôle à distance (`Sessions/Capabilities`, `Sessions/{id}/Playing/*`, `Sessions/{id}/Command`) n'existe encore.

## Choix de conception (à confirmer par ADR)

- **Tolérance** : Discord ajoute ~0,3–1 s de retard pour les amis ⇒ viser < 150 ms d'écart TV/ordi. Suffisant pour passer **par le relais Emby** (pas de port ouvert, pas d'appairage, pas d'invite pare-feu Windows).
- **Leader = ordi** (c'est ce que voient les amis), **suiveur = TV**. L'ordi diffuse `{état, position, horodatage}` toutes les 1–2 s.
- **Commandes en position absolue** (« aller à 12:34 »), jamais relatives, pour éviter la dérive cumulative.
- **Correction de dérive côté TV** : < 60 ms rien ; 60 ms–1 s vitesse ±4 % quelques secondes ; > 1 s saut.
- **Démarrage synchronisé** : les deux chargent, attendent « prêt », démarrent ensemble ; si l'un bufferise, l'autre se met en pause (« En attente de la TV… »).
- **Répercuté** : pause/lecture, ±10 s, scrub, chapitres, passer intro/générique, épisode suivant (lien conservé), arrêt (quitter sur la TV arrête l'ordi).
- **Jamais répercuté** : volume (l'ordi doit rester à 100 % pour Discord) ; pistes audio/sous-titres après le démarrage (alignées seulement au départ).
- **Option « Décalage TV »** 0–1,5 s : retarde la TV pour coller à ce que voient les amis sur Discord.
- **Liaison** : bouton « Regarder avec l'ordi » sur la TV (lecteur + fiche), « Envoyer aussi sur la TV » sur l'ordi ; liste des sessions Viewer en ligne du même compte (aucun code). Pastille dans l'OSD : « Lié à <appareil> · ±40 ms » ; perte du lien = message, chaque lecteur continue seul.
- **Secours** si le relais Emby est insuffisant : lien direct LAN, **la TV héberge** le serveur et l'ordi s'y connecte en sortie (évite l'invite du pare-feu Windows) ; découverte via les sessions Emby ou NSD.

## Plan (tranches verticales, une ADR + un commit par tranche)

0. **Vérifier sur le serveur** (compte démo, `debug.env` à la racine du checkout principal, ne jamais l'afficher ; demander la permission avant de se connecter) : déclaration `Sessions/Capabilities/Full` (SupportsMediaControl, SupportedCommands), relais `Sessions/{id}/Playing/{Pause|Unpause|Seek…}` et `Sessions/{id}/Command`, réception `Playstate` / `GeneralCommand` sur la socket, possibilité de passer un message libre (ex. `SendString`) pour la position, latence mesurée. **Ces noms d'endpoints sont des hypothèses non vérifiées.** Consigner la preuve dans `docs/evidence/` et décider relais vs LAN dans une ADR.
1. Socket Emby ouverte toute la session (pas seulement l'accueil) sur les deux apps ; amender l'ADR 0110.
2. Protocole de synchro partagé Rust + Kotlin (messages, horodatage, file de commandes) avec tests unitaires (Vitest/cargo test/JUnit).
3. Mode suiveur TV : correction de vitesse et sauts, tests de la logique de dérive.
4. UI de liaison + pastille d'état des deux côtés (français, tutoiement, focus visible, `DESIGN.md`).
5. Décalage TV, enchaînement d'épisodes, coupures/reconnexion.
6. Test réel ordi + Shield (192.0.2.20, adb) avec mesure d'écart ; bump de version + changelog desktop et Android ; désinstaller `io.github.quentinpn.viewer.tv.debug` de la Shield après le test.

## Limites connues

- Les deux apps doivent être ouvertes ; Emby ne réveille pas une Shield en veille.
- Les deux appareils rapportent leur progression à Emby (même épisode, sans conséquence).
- Un côté en transcodage rend les sauts plus lents ; l'attente « prêts tous les deux » compense.

## Rappels d'environnement

- Lire `PRODUCT.md`, `ARCHITECTURE.md`, `DESIGN.md`, `ROADMAP.md`, `TESTING.md` et les skills `emby-api`, `media-playback`, `security-review`.
- Heredocs instables dans ce shell : écrire les patchs multi-lignes en script dans le scratchpad.
- Commiter chaque tranche vérifiée ; ne jamais pousser sans demande. Remote `origin` = Gitea privé.
- Boilerplate installé (non commité au moment du hand-off) : agents `explorer`/`fixer`/`checker`, hook PostToolUse qui lance `npm test`.
