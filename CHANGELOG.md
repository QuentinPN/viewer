# Journal des versions

## 1.4.0 — 2026-11-14

- Lecteur : « Envoyer aussi sur la TV » ouvre le même épisode sur la Shield, qui suit ta lecture. Tu peux ainsi partager ton écran sur Discord et regarder sur la télé en même temps. La pastille « Lié à SHIELD · ±40 ms » montre l'écart mesuré par la TV ; « Délier » laisse chacun continuer seul (ADR 0123, 0124).
- Pause, lecture, ±10 s et « Passer l'intro » faits sur la TV s'appliquent aussi à l'ordi. Retour sur la TV arrête la lecture ici.
- Écart mesuré sur une vraie Shield : médiane environ 50 ms, rarement plus d'un quart de seconde.

## 1.3.1 — 2026-11-13

- Réglages : les sections occupent toute la fenêtre, en colonnes selon sa largeur (une sur fenêtre étroite, jusqu'à quatre sur grand écran). Les réglages du quotidien viennent d'abord, en panneaux pleins et sans trou (Lecture et Qualité l'une sous l'autre, puis Audio et sous-titres, puis Accueil) ; mises à jour, cache, journaux et détails techniques sont regroupés plus bas sous « Informations et maintenance », en panneaux plus discrets.
- L'app te tutoie partout, comme la TV : connexion, choix du serveur, messages d'erreur, recherche et bibliothèques.

## 1.3.0 — 2026-11-13

- Réglages › Mises à jour : version installée et bouton « Rechercher des mises à jour ». La nouvelle version est téléchargée et vérifiée, puis « Redémarrer maintenant » l'installe. Hors du réseau de la maison, le message le dit (ADR 0112).
- Réglages › Accueil : choisis l'ordre des rangées de l'accueil (glisser-déposer ou « Monter / Descendre ») et masque celles que tu ne veux pas ; une rangée masquée n'est plus demandée au serveur (ADR 0113).
- Accueil : le bandeau ressemble à celui de la TV. Plus d'étiquette « Reprendre » au-dessus du titre ; le bouton dit « Reprendre à 12:34 » et « Reste 22 min » s'affiche à côté de la barre de progression.
- Réglages › À propos et Serveur, comme sur la TV : version et type de build de l'app, Windows, cartes graphiques et pilotes, nom et version du serveur Emby, adresse et chiffrement de la connexion (ADR 0116).
- Réglages › Capacités de lecture : ce que la carte graphique sait décoder en matériel (H.264, HEVC, VP9, AV1) et si le HDR est activé sur chaque écran (ADR 0116).
- Langues audio et sous-titres : par défaut, celles de ton compte Emby ; un réglage local les remplace sur ce PC seulement (ADR 0115).
- Lecteur : heure et « Fin à 22:47 » dans les commandes ; dans « Options de lecture », vitesse, mode nuit (compression du volume), synchronisation des sous-titres et de l'audio (Z / X pour les sous-titres), format de l'image (original, remplir, étirer) et minuterie de mise en veille, dont « Fin de l'épisode ».
- Lecteur : change la qualité pendant la lecture ; la vidéo reprend au même endroit (ADR 0117).
- Accueil : rangée « Playlists » et page de playlist (« Tout lire » lance la première entrée ; pas encore de lecture enchaînée). Elle se range et se masque dans Réglages › Accueil (ADR 0122).
- Recherche : tes 8 dernières recherches s'affichent sous le champ vide, par serveur et par compte ; « Effacer » les retire (ADR 0122).
- Réglages › Audio et sous-titres : « Mode nuit » par défaut pour la lecture ; le choix des sous-titres « Comme le compte Emby » porte enfin ce nom (ADR 0115, 0122).
- Fiches : rangée « Distribution » (« Invités » sur un épisode) et page d'une personne avec ses films et séries ; genres et studios cliquables ; « Titres similaires » ; rangée de chaque collection et page de collection ; bouton « Bande-annonce » quand le serveur a une bande-annonce locale (ADR 0121).

## 1.2.0 — 2026-11-13

- Mises à jour automatiques : l'app télécharge la nouvelle version en arrière-plan, puis une pastille « Mise à jour prête » propose de redémarrer. Un clic installe sans assistant et relance l'app ; « Plus tard » la garde pour le prochain démarrage. Jamais pendant la lecture. L'installeur est signé et vérifié avant d'être lancé (ADR 0112).
- Les versions sont publiées sur le Gitea personnel (ADR 0111). Cette version s'installe encore à la main ; les suivantes arriveront toutes seules.

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
