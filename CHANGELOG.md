# Journal des versions

## 1.1.1 — 2026-11-13

- L'accueil se met à jour tout seul : un film ou une série ajouté sur le serveur apparaît dans « Ajoutés récemment » quelques secondes après, tant que l'accueil est affiché (ADR 0110). Sinon, au plus 5 min après ou dès le retour dans l'app (ADR 0109).

## 1.1.0 — 2026-11-11

- Écran de pré-connexion « L'étagère » : une vignette par serveur et compte enregistrés, reconnexion sans mot de passe tant que la session est valide, « Gérer » pour oublier un serveur, connexion manuelle (ADR 0103).
- Menu des cartes façon Emby (clic droit ou touche menu) : marquer vu ou non vu, favori, retirer de « Reprendre », ajouter à une playlist (ADR 0100).
- Grille de bibliothèque compacte avec rail des lettres et rail d'icônes pour le tri et les filtres, filtre par décennie (ADR 0101).
- Les marques « vu » se mettent à jour partout sans recharger la page (ADR 0096).
- Le bouton « Passer l'intro » ou « Passer le générique » disparaît 10 s après son apparition (ADR 0089).
- Correctif : la barre du haut du lecteur s'affichait au milieu de la fenêtre hors plein écran.
- Développement : la version debug a ses propres données et son propre coffre, séparés de l'app installée (ADR 0105).

## 1.0.1 — 2026-11-08

- Open the main window maximized by default
- Fix: library grid keeps stale played marks and counts after a playback
- Fix: home latest rows keep stale episode counts after a playback

## 1.0.0 — 2026-11-07

- Fix: caption buttons ignored once the player controls faded
- Audio and subtitle preferences (ADR 0035)
- Settings: streaming quality, local and remote bitrate caps (ADR 0037)
- Chapters in the player (ADR 0034)
- Diagnostics: local redacted log file, panic hook and log folder button
- Detail: choose between versions of a film or episode (ADR 0036)
- Test: watched state set by the server at the end of a film

## 0.3.1 — 2026-11-07

- Versioning: single version source, release script and About section
- Fix: fullscreen from a maximized window left the taskbar visible

## 0.3.0 — 2026-11-07

- Nouvelle direction visuelle inspirée d'Apple TV (ADR 0031) : pilule de navigation flottante, accueil, fiches film et épisode, saisons en pilules, grille d'épisodes, commandes du lecteur, cartes givrées de connexion et de perte de connexion, écran de démarrage avec logo.
- Correctifs : pilule de navigation avec beaucoup de bibliothèques, panneau du lecteur et carte de perte de connexion à 150 %, avertissement Svelte de l'accueil.

## 0.2.0 — 2026-11-07

- Icône propre à l'application et barre de titre personnalisée (ADR 0029).
- Passer les intros et génériques, repères du serveur Emby puis d'IntroDB et TheIntroDB (ADR 0030).

## 0.1.0 — 2026-11-07

- Premier installateur Windows par utilisateur (Gate 5) : connexion à Emby, accueil, bibliothèques, recherche, tri et filtres, favoris, fiches, lecteur mpv avec suivi de lecture, épisode suivant.
