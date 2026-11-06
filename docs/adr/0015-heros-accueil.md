# ADR 0015 — Héros de l'accueil

Statut : Accepté (2026-10-28).

## Contexte
`DESIGN.md` prévoit un « héros cinématographique » en tête de l'accueil. L'ADR 0010 l'avait laissé de côté, faute de décision sur trois points : quels titres montrer, s'ils changent seuls, et ce que font les boutons. L'utilisateur a tranché le 2026-10-28 :
- **contenu** : « Reprise, sinon nouveauté » ;
- **rotation** : « Statique + flèches », sans défilement automatique ;
- **actions** : « Lecture » (ou « Reprendre ») et « Plus d'infos ».

Le contrat a été vérifié dans `/emby/openapi.json` d'Emby 4.10.1.0 et sur le serveur réel, en lecture seule (test ignoré `real_server_hero`) :
- `GET /Users/{Id}/Items` accepte `ImageTypes=Backdrop` (seuls les titres qui ont un fond), `IsPlayed=false`, `SortBy=DateCreated` ;
- `GET /Users/{Id}/Items/Resume` renvoie, pour un épisode, `ParentBackdropItemId`, `ParentBackdropImageTags`, `ParentLogoItemId`, `ParentLogoImageTag` et `SeriesPrimaryImageTag` quand `EnableImageTypes` contient `Backdrop,Logo` ;
- `GET /Shows/{Id}/Episodes?IsPlayed=false&Limit=1` renvoie le premier épisode non vu, dans l'ordre de la série.

## Décision
- **Commande** `emby_hero() -> HeroItem[]`. Rust choisit les titres et construit seul les URL d'images.
- **Contenu** : au plus **5 titres**, chacun avec un fond (`Backdrop`).
  1. D'abord les éléments de « Reprendre » (bouton « Reprendre »). Un épisode représente sa série, avec le fond et le logo de la série.
  2. S'il reste de la place, des films et séries **non vus**, du plus récemment ajouté au plus ancien (bouton « Lecture »).

  Un même film ou une même série n'apparaît qu'une fois. Les titres sans fond sont écartés.
- **Ce que lit le bouton principal** :
  - un film se lit lui-même ;
  - une série reprise lit l'épisode en cours ;
  - une série récente lit son premier épisode non vu, cherché par une requête par série (au plus 5, l'une après l'autre ; `tokio` n'a pas ici de quoi les lancer en parallèle sans nouvelle dépendance). Si cet épisode a une position, le bouton dit « Reprendre ».

  Sans épisode à lire (série entièrement vue), seul « Plus d'infos » reste.
- **Plus d'infos** ouvre la fiche du film ou de la série (`HeroItem.detail`, une `MediaItem`).
- **Affichage** :
  - un seul titre visible, fond plein cadre, voile, logo (ou titre), année, épisode, résumé sur trois lignes ;
  - flèches précédent/suivant et points, uniquement s'il y a plusieurs titres ; clavier ← et → quand le focus est dans le héros ;
  - **aucun changement automatique** ;
  - fondu de 200 ms entre deux titres, supprimé avec `prefers-reduced-motion` ;
  - sémantique de carrousel : `aria-roledescription="carrousel"`, et pour chaque titre `aria-roledescription="diapositive"` avec « 2 sur 5 ».
- **États** :
  - pendant le chargement, un bloc squelette de même hauteur ;
  - en cas d'erreur, un message et « Réessayer » ;
  - **caché s'il est vide**.

  Le héros est relu à la fin d'une lecture (comme « Reprendre ») et au retour du réseau. Il garde le titre affiché s'il est encore dans la liste.

## Conséquences
- Une requête de plus à l'accueil, plus une par série récente du héros. Mesuré sur le serveur : 1,9 s pour 5 titres, dont 3 séries. Le reste de l'accueil ne l'attend pas.
- `SortBy=DateCreated` date l'ajout de la série, pas celui de son dernier épisode. Une série ancienne qui reçoit un épisode ne remonte pas dans le héros ; elle remonte dans « Ajoutés récemment ».
- Les épisodes spéciaux (saison 0) : leur place dans l'ordre de `/Shows/{Id}/Episodes` n'a pas été observée sur le serveur réel.
- Le cache local ne garde pas le héros : Emby reste la source de vérité.
