# ADR 0122 — Playlists, recherches récentes et mode nuit (desktop)

- Statut : accepté
- Date : 2026-11-13

## Contexte

Un audit de parité entre l'app Android TV et le client Windows a relevé quatre écarts :

- la TV a une rangée « Playlists » à l'accueil et une page par playlist (ADR 0088) ; le desktop ne sait qu'ajouter un titre à une playlist depuis le menu des cartes (ADR 0100) ;
- la TV garde les recherches récentes (ADR 0075) ; la recherche desktop repart de zéro ;
- le mode nuit (ADR 0072) n'est réglable que dans « Options de lecture » du lecteur desktop ;
- le choix de sous-titres par défaut s'appelait encore « Comme sur le serveur Emby », alors que les langues suivent le compte Emby (ADR 0115).

## Décision

### Playlists

- Mêmes requêtes que la TV, aucun nouvel endpoint :
  - la rangée : `GET /Users/{UserId}/Items?IncludeItemTypes=Playlist&Recursive=true&SortBy=SortName&SortOrder=Ascending&Limit=50&Fields=ChildCount,PrimaryImageAspectRatio`, sans `MediaTypes` (sur Emby 4.10.2.0, `MediaTypes=Video` à côté de `IncludeItemTypes=Playlist` renvoyait toutes les vidéos du serveur). Les playlists audio sont écartées côté client ; une playlist sans `MediaType` est gardée.
  - la page : `GET /Playlists/{Id}/Items?UserId=&Limit=300&Fields=PrimaryImageAspectRatio&EnableUserData=true`, dans l'ordre de la playlist. Seuls les films et les épisodes sont gardés, une seule fois chacun (à leur première place).
- Côté Rust, deux commandes Tauri (`emby_home_playlists`, `emby_playlist_items`) passent par `EmbyClient`. L'interface ne construit aucune URL ; les images passent par le cache d'images existant.
- La rangée a la clé `playlists`, comme sur la TV, et se place par défaut en dernier, après les « Ajoutés récemment ». Elle se range et se masque dans Réglages › Accueil (ADR 0113). Masquée, elle n'est pas demandée au serveur. Une disposition déjà enregistrée qui ne connaît pas cette clé la reçoit selon la règle de l'ADR 0113 : juste après la rangée qui la précède par défaut, soit la dernière « Ajoutés récemment ».
- La page d'une playlist remplace l'accueil (bouton « Accueil » pour revenir, le focus retourne sur la carte). Elle indique le nombre de titres et leur durée totale quand tous en ont une, puis les titres dans l'ordre, en cartes 16:9 comme « Reprendre ».
- Contrairement à la TV, qui ouvre la page du titre, une carte lance sa lecture directement (depuis le point de reprise s'il y en a un) et « Tout lire » lance le premier titre. Le lecteur desktop n'a pas de file d'attente : la fin d'un titre n'enchaîne pas sur le suivant de la playlist. C'est une limite connue, à traiter dans une tranche dédiée si besoin.
- Rien n'est modifié côté serveur depuis cette page : ni retrait, ni réordonnancement.

### Recherches récentes

- Comme sur la TV : au plus 8 recherches, la plus récente d'abord, nettoyées comme la requête envoyée au serveur ; un terme trop court n'est pas gardé ; une ancienne écriture du même terme (casse et accents mis à part) est remplacée.
- Une recherche est retenue quand ses résultats sont arrivés, pas à chaque frappe.
- Gardées sur ce PC uniquement (`localStorage`), par serveur et par utilisateur Emby ; jamais envoyées au serveur. Ce ne sont pas des secrets ; le jeton n'y est jamais.
- Un petit panneau sous le champ de recherche les affiche tant que le champ a le focus et reste vide ; un clic (ou Entrée) relance la recherche, et « Effacer » les supprime toutes.

### Mode nuit

- Réglages › Audio et sous-titres propose « Mode nuit », lié au même stockage que le lecteur (`viewer.player.nightMode`). Activé ici, il vaut pour chaque lecture ; le couper dans le lecteur le coupe aussi ici.

### Libellé

- L'option devient « Comme le compte Emby ».

## Conséquences

- Rien n'a été vérifié contre le vrai serveur ni vu dans l'app en marche : les tests couvrent l'analyse des réponses (Rust), l'ordre des rangées et l'historique de recherche (Vitest).
