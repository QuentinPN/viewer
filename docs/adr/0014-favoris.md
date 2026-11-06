# ADR 0014 — Favoris : bouton sur la fiche et rangée d'accueil

Statut : Accepté (2026-10-28).

## Contexte
`DESIGN.md` prévoit une rangée « Favorites » sur l'accueil. Jusqu'ici, l'application lisait les favoris pour filtrer une grille (ADR 0013), mais ne pouvait ni en ajouter ni en retirer.

Le contrat a été vérifié dans `/emby/openapi.json` d'Emby 4.10.1.0 :
- `POST /Users/{UserId}/FavoriteItems/{Id}` ajoute un favori, et `DELETE` sur la même route le retire ;
- les deux renvoient le `UserItemDataDto` de l'élément, dont `IsFavorite`.

La liste vient de `GET /Users/{UserId}/Items` avec `Filters=IsFavorite`.

## Décision
- **Bouton « Favori »** sur la fiche d'un film et sur celle d'une série, à côté de « Lecture ».
  - Bascule `aria-pressed`, avec un libellé constant. L'étoile est vide ou pleine, et une infobulle dit « Ajouter aux favoris » ou « Retirer des favoris ».
  - Changement **optimiste** : l'état change au clic, puis prend celui que le serveur renvoie. Si la requête échoue, l'état précédent revient (`src/lib/detail/favorite.ts`).
  - Pendant l'aller-retour, d'autres clics sont ignorés (`aria-disabled`).
  - Une erreur affiche « Favori non modifié » et remonte comme toutes les autres : un serveur perdu ouvre l'écran de reconnexion, un 401 termine la session.
- **Commandes Tauri** :
  - `emby_set_favorite(item_id, favorite) -> bool` ;
  - `emby_favorites() -> MediaItem[]`.

  Rust valide l'identifiant avant tout envoi et construit seul l'URL. Le jeton reste dans l'en-tête. `ItemDetail.favorite` vient de `UserData.IsFavorite`.
- **Rangée « Favoris » de l'accueil**, entre « À suivre » et « Ajoutés récemment ».
  - Contenu : films et séries (`IncludeItemTypes=Movie,Series`), par titre (`SortName`), au plus 16, en affiches qui ouvrent la fiche.
  - Les autres types (collections, épisodes…) sont écartés.
  - Rangée **cachée quand elle est vide**. En cas d'erreur, elle propose « Réessayer ».
  - Relue en place après chaque changement fait sur une fiche (compteur `favoritesVersion`) et au retour du réseau. Les cartes restent affichées jusqu'à la réponse.
- Le **héros** de l'accueil n'est pas traité ici : il demande sa propre décision de design (choix de l'élément, rotation, mouvement réduit).

## Conséquences
- Un favori ajouté ou retiré depuis un autre client apparaît au prochain chargement de l'accueil ou au retour du réseau, pas en direct.
- Une grille filtrée sur « Favoris » (ADR 0013) n'est pas relue après un changement sur une fiche : la carte reste jusqu'au prochain changement d'options ou de bibliothèque.
- Le cache local ne garde pas les favoris : Emby reste la source de vérité.
