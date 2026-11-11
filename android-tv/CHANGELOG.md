# Journal des versions — Android TV

Chaque version livrée sur la Shield augmente `versionName` et `versionCode` dans `app/build.gradle.kts` et ajoute sa section ici.

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
