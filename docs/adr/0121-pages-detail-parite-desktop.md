# ADR 0121 — Pages détail du desktop à parité avec l'Android TV

Statut : Accepté (2026-11-13). Reprend sur Windows les ADR 0063, 0068, 0071, 0079, 0083 et 0087 de l'Android TV.

## Contexte
Un audit de parité montre que les fiches film, série et épisode de l'Android TV offrent ce que le desktop n'a pas : distribution avec photos et page personne, genres et studios ouvrables, bande-annonce, « Titres similaires », collections (BoxSet) et page collection. Le marquage « vu » d'une saison ou d'une série entière existe déjà sur le desktop (ADR 0096 : bouton « Vu » de la fiche série, case de la saison dans `EpisodeList`) : rien à faire pour ce point.

Le code de la TV est la référence vérifiée : mêmes points d'accès et mêmes paramètres, aucun nouveau.

## Décision
### Données (Rust, `EmbyService`)
- Le détail reste `GET /Users/{UserId}/Items/{Id}` sans `Fields`. `BaseItemDto` lit en plus `People[].Id` (valeur primitive quelconque, comme la TV), `People[].PrimaryImageTag`, `LocalTrailerCount` et `Collections` (`NameLongIdPair[]`, lu en `serde_json::Value` : toute autre forme donne une liste vide).
- `ItemDetail` gagne `cast[].id` et `cast[].photo` (image `Primary` de la personne, servie par `emby-img`), `hasLocalTrailer` et `collections` (trois au plus, identifiants validés, doublons retirés, comme `collectionRefs`).
- Nouvelles commandes, toutes en lecture :
  - `emby_similar(itemId)` : `GET /Items/{Id}/Similar` avec `UserId`, `Limit`, `IncludeItemTypes=Movie,Series`, `Fields=PrimaryImageAspectRatio,ProductionYear`, `EnableUserData`, `ImageTypeLimit=1`, `EnableImageTypes=Primary` ; sans le titre lui-même ni doublons.
  - `emby_titles(source)` : `GET /Users/{UserId}/Items`, une source parmi
    - personne : `PersonIds`, `Recursive`, `IncludeItemTypes=Movie,Series`, `SortBy=ProductionYear,SortName`, `SortOrder=Descending`, `Limit=100` (requête `personItemsQuery`) ;
    - collection : `ParentId={BoxSet}`, `SortBy=PremiereDate,ProductionYear,SortName`, `SortOrder=Ascending`, `Limit=100` (`collectionItemsQuery`) ;
    - genre ou studio : `Recursive`, `IncludeItemTypes` du type de la fiche, `Genres` ou `Studios` (paramètres déjà employés par la grille de la TV), `SortBy=SortName`, `Limit=200`.
    Les noms de genre et de studio passent la règle `is_usable_genre` (pas de `|`, pas de caractère de contrôle, 100 caractères au plus). Le résultat garde films et séries seulement, une fois chacun.
  - `emby_local_trailer(itemId)` : `GET /Users/{UserId}/Items/{Id}/LocalTrailers` (`EnableImages=false`, `EnableUserData=false`) ; renvoie l'id de la première bande-annonce valide, ou rien.

### Interface (Svelte)
- La fiche reste au-dessus de la grille ; une **pile de pages** s'y ajoute dans `LibraryBrowser` : fiche d'un autre titre, page personne, page collection, liste d'un genre ou d'un studio. Échap ou Retour dépile ; les pages couvertes gardent leur défilement (`content-visibility: hidden`).
- Distribution : rangée horizontale de cartes (photo ronde ou initiales, nom, rôle) ; une carte ouvre la page de la personne : ses films et séries, du plus récent au plus ancien.
- Genres et studios deviennent des boutons ; chacun ouvre la liste des titres du même type (films ou séries) ayant ce genre ou ce studio, sur toutes les bibliothèques.
- « Bande-annonce » apparaît quand `LocalTrailerCount > 0` ; l'appui lit la liste puis joue la première par le chemin de lecture habituel (`emby_play`, donc PlaybackInfo).
- « Titres similaires » et « Collection : {nom} » : rangées d'affiches sous la fiche, absentes quand il n'y a rien ou en cas d'échec (sauf session expirée, gérée comme partout).

## Alternatives
- **Ouvrir la grille de la bibliothèque filtrée** pour un genre ou un studio, comme la TV : demande de retrouver la bibliothèque du titre et d'ajouter un filtre studio au panneau Filtres et à sa mémoire ; une page de liste dans la pile garde la fiche dessous et ne change pas la grille. Retenue pour ce lot.
- **Bandes-annonces distantes (YouTube)** : le desktop n'a ni `tauri-plugin-opener` ni autre ouvreur de lien sûr ; en ajouter un est une dépendance et une permission IPC de plus. Non fait : seule la bande-annonce locale est proposée.

## Conséquences
- Une requête de plus par fiche (similaires), plus une par collection ; les pages personne, collection, genre et studio coûtent une requête à l'ouverture.
- Une bande-annonce jouée suit le chemin de lecture normal, rapports de lecture compris (la TV n'en envoie pas).
- **NON VÉRIFIÉ** : aucun appel au serveur réel pour ce lot, rien n'a été vu dans l'application lancée. Mêmes réserves que les ADR TV (présence de `Collections` sans `Fields`, forme de `People[].Id`).
