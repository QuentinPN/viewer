# Journal des versions — Android TV

Chaque section est écrite par Gitea Actions au merge dans `main`, avec `versionName` et `versionCode` (ADR 0126).

## 1.5.0 — 2026-11-22

### Nouveautés

- bouton « Bande-annonce » sur les fiches, ordinateur et TV (demander)
- segment « Rechercher » dans l'onglet Demander (tv)

### Corrections

- la recherche de Demander garde le focus au retour (tv)

## 1.4.0 — 2026-11-19

- Nouvel onglet « Demander » : « Découvrir », « Parcourir » (films et séries, avec tous les filtres), fiche d'un titre et demande d'un film ou de saisons choisies, à la télécommande (ADR 0125).
- « Mes demandes » suit l'état de tes demandes ; la recherche propose « À demander » sous les résultats Emby.
- Réglages › Demandes : adresse d'Request, état de la connexion, déconnexion.
- Connexion sans mot de passe via ta session Emby ; il faut un serveur Request en API 1.x.
- Testé sur la Shield.

## 1.3.0 — 2026-11-14

- Lecture liée avec l'ordi : quand l'ordi envoie un épisode à la TV, elle l'ouvre et suit sa lecture, en rattrapant l'écart par de petites variations de vitesse, inaudibles. Elle saute quand l'écart est grand (ADR 0123, 0124).
- « Regarder avec l'ordi » dans le menu du lecteur rejoint ce que l'ordi lit ; « Délier » laisse chacun continuer seul. La pastille « Lié à … · ±40 ms » montre l'écart.
- Pause, lecture, ±10 s et « Passer l'intro » sur la télécommande s'appliquent aussi à l'ordi ; Retour arrête les deux.
- Réglages › « Décalage TV » (0 à 1,5 s) : retarde la TV pour coller à l'image que voient tes amis sur Discord.

## 1.2.0 — 2026-11-13

- Réglages › Mises à jour : version installée, « Rechercher une mise à jour » et, quand elle est prête, « Installer la version X » (ADR 0112).
- Réglages › Accueil : appui long sur OK pour déplacer une rangée, ▲ ▼ pour la placer, OK ou Retour pour la poser (ADR 0114).
- Menu du profil : ton nom puis le serveur (« HomeNAS · non chiffré » en HTTP), comme sur Windows.
- Serveur injoignable : un écran garde ta session, réessaie tout seul (2, 4, 8… 30 s) et propose « Réessayer » ou « Changer de serveur » ; tu reviens où tu étais (ADR 0119).
- Fiches film et épisode : bouton « Infos techniques » (vidéo, fichier, pistes audio et sous-titres) de la version jouée (ADR 0119).
- « Rechercher une mise à jour » sans version publiée sur le canal : « Aucune version publiée pour l'instant. » au lieu d'une erreur HTTP 404.
- Tous les textes te tutoient, comme sur Windows (accueil, connexion, recherche, erreurs, réglages).
- Réglages › Langues : langue audio, mode et langue des sous-titres. Par défaut « Comme le compte Emby » ; un choix local passe devant (ADR 0115, 0120).

## 1.1.0 — 2026-11-13

- Mises à jour automatiques : l'app télécharge la nouvelle version en arrière-plan (taille, SHA-256 et certificat de signature vérifiés), puis une carte « Mise à jour prête » sur l'accueil propose « Installer » ou « Plus tard ». Android demande une confirmation, et une seule fois l'autorisation d'installer des applications (ADR 0112). Rouvrir l'app après l'installation.
- Cette version s'installe encore avec adb ; les suivantes arriveront toutes seules.

## 1.0.3 — 2026-11-13

- L'accueil se met à jour tout seul : un film ou une série ajouté sur le serveur apparaît dans « Ajoutés récemment » quelques secondes après, tant que l'accueil est affiché (ADR 0110). Sinon, au plus 5 min après ou au retour dans l'app depuis l'écran d'accueil de la TV. Le focus reste sur la même carte (ADR 0109).

## 1.0.2 — 2026-11-13

- Correctif : après une avance ou un recul (◀ ▶) pendant l'intro ou le générique, OK met en lecture ou en pause au lieu de « Passer l'intro ». Le bouton reste visible sans le focus ; il le reprend quand les commandes disparaissent (ADR 0108).
- Lecture en réseau : mémoire tampon plus grande (150 Mo) et quelques secondes chargées avant de démarrer et après une coupure, pour une seule courte attente au lieu de micro-coupures répétées sur un Wi-Fi juste (ADR 0108).

## 1.0.1 — 2026-11-11

- Correctif : avec une télécommande qui passe par la TV (HDMI-CEC, comme la LG Magic Remote), l'appui long qui ouvre le menu d'une carte ne lance plus la lecture quand on descend ensuite dans le menu.

## 1.0.0 — 2026-11-11

- Écran de pré-connexion « L'étagère » : une vignette par serveur et compte enregistrés, reconnexion sans mot de passe tant que la session est valide, « Gérer » pour oublier un serveur, connexion manuelle (ADR 0104).
- Menu des cartes façon Emby (appui long sur une carte) : marquer vu ou non vu, favori, retirer de « Reprendre », ajouter à une playlist (ADR 0098).
- Grille de bibliothèque compacte avec rail des lettres et rail d'icônes pour le tri et les filtres (ADR 0099).
- Développement : l'app debug (`io.github.quentinpn.viewer.tv.debug`) s'installe à côté de la version normale sans la toucher (ADR 0102).

## 0.1.0 — 2026-11-10

Première version installée sur la Shield (build release, ADR 0095).

- Lecture par libmpv avec décodage matériel : HEVC 10 bits, 4K HDR10, sous-titres ASS affichés à l'heure, choix des pistes audio et sous-titres, délai audio et sous-titres, chapitres, vitesse, mode nuit, format d'image, minuterie de mise en veille.
- Négociation avec Emby (PlaybackInfo) : lecture directe ou transcodage, qualité réglable depuis le lecteur, raisons du transcodage affichées, pistes conservées en transcodage.
- Accueil : grande affiche, « Reprendre », « À suivre », favoris, ajouts récents par bibliothèque, playlists, rangées réglables, toutes les bibliothèques dans la barre.
- Fiches film, série, saison et épisode : casting, page d'une personne, titres similaires, bandes-annonces, notes, genres et studios qui ouvrent la bibliothèque du titre.
- Bibliothèques : grille paginée, tri, filtres, décennies, saut à une lettre, titre au hasard.
- Recherche avec recherches récentes.
- Épisode suivant, passer l'intro et le générique (le bouton disparaît après 10 s), avance accélérée en maintenant la touche.
- Suivi de lecture vers Emby, reprise, rapports d'arrêt perdus gardés et renvoyés.
- Réglages : apparence des sous-titres, cache des images, page « À propos ». Jeton chiffré par le Keystore Android et jamais écrit dans les journaux.
- Actions vu, favori et « Retirer de Reprendre » sur les fiches et l'accueil (ADR 0097).
- Écran de chargement au premier affichage de l'accueil, logo de l'app desktop sur la bannière et l'icône.
