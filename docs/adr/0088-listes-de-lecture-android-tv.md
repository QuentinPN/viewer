# ADR 0088 — Listes de lecture sur l'accueil (Android TV)

Statut : Accepté (2026-11-10). Complète les ADR 0056, 0059, 0064 et 0068.

## Contexte
Emby garde des listes de lecture par utilisateur (éléments `Playlist`) : une soirée de films, des épisodes choisis dans plusieurs séries. L'application TV ne les montrait nulle part ; il fallait passer par un autre client pour les retrouver. Ce lot les montre, en lecture seule.

## Contrat Emby vérifié
Documentation officielle, consultée le 2026-11-10 :
- [`GET /Playlists/{Id}/Items`](https://dev.emby.media/reference/RestAPI/PlaylistService/getPlaylistsByIdItems.html) (`PlaylistService`, « Gets the original items of a playlist ») : `UserId`, `StartIndex`, `Limit`, `Fields`, `EnableImages`, `EnableUserData`, `ImageTypeLimit`, `EnableImageTypes`. Réponse : `QueryResult<BaseItemDto>`. Pas de `SortBy` : l'ordre est celui de la liste.
- [`BaseItemDto`](https://dev.emby.media/reference/pluginapi/MediaBrowser.Model.Dto.BaseItemDto.html) : `MediaType` (chaîne), `ChildCount` (`int?`), `PlaylistItemId` (chaîne, non lu ici).
- `ItemsService` → `GET /Users/{UserId}/Items` (déjà employé, ADR 0071) avec `IncludeItemTypes`, `Recursive`, `SortBy`, `Fields`.

Lu sur le serveur réel (Emby 4.10.2.0, compte de test « claude », GET seulement, jeton dans l'en-tête `X-Emby-Authorization`, jamais dans l'URL ni affiché ; session fermée par `POST /Sessions/Logout` à la fin) :
- `GET /Users/{UserId}/Items?IncludeItemTypes=Playlist&Recursive=true&SortBy=SortName&SortOrder=Ascending&Fields=ChildCount,CumulativeRunTimeTicks,PrimaryImageAspectRatio&…` répond `200`, `TotalRecordCount: 0` : **le compte de test n'a aucune liste de lecture**. Aucune n'a été créée.
- La même requête avec `MediaTypes=Video` en plus répond **toutes les vidéos du serveur** (`TotalRecordCount: 60423`, des épisodes et des films) : avec `MediaTypes`, Emby ne tient plus compte de `IncludeItemTypes=Playlist`. `MediaTypes` n'est donc jamais envoyé ; les listes audio sont écartées côté client par `MediaType`.
- `/Users/{UserId}/Views` ne contient pas de dossier « Playlists » pour ce compte (sans liste, rien à montrer).

Contrat retenu :
1. Les listes : `GET /Users/{UserId}/Items` avec `IncludeItemTypes=Playlist`, `Recursive=true`, `SortBy=SortName`, `SortOrder=Ascending`, `Limit=50`, `Fields=ChildCount,PrimaryImageAspectRatio`, `EnableUserData=false`, `ImageTypeLimit=1`, `EnableImageTypes=Primary` (`EmbyClient.playlists`, `playlistsQuery`).
2. Les éléments d'une liste : `GET /Playlists/{Id}/Items` avec `UserId`, `Limit=300`, `Fields=PrimaryImageAspectRatio`, `EnableUserData=true`, `ImageTypeLimit=1`, `EnableImageTypes=Primary,Backdrop,Thumb` (`EmbyClient.playlistItems`, `playlistItemsQuery`).

**NON VÉRIFIÉ** (le compte n'a pas de liste) : la forme réelle d'une `Playlist` dans la réponse (présence de `MediaType` et de `ChildCount`), la réponse réelle de `/Playlists/{Id}/Items` (ordre, `SeriesName`, `ParentIndexNumber`, `RunTimeTicks`, images des films avec `Backdrop`/`Thumb`), le comportement avec une liste qui contient un élément deux fois ou un élément supprimé. Les tests se fondent sur les formes documentées ; celles des épisodes reprennent ce que le serveur a renvoyé pour des épisodes ordinaires lors de la même lecture.

## Décision
- **Lecture** (`emby/Playlists.kt`) : `videoPlaylists` garde les `Playlist` dont `MediaType` vaut `Video` ou manque (un champ absent ne cache pas la liste), sans doublon, dans l'ordre du serveur (A → Z). `playlistEntries` garde les films et les épisodes dans l'ordre de la liste ; un élément présent deux fois n'apparaît qu'à sa première place (la page ouverte et le focus au retour se règlent par identifiant) ; tout autre type (audio, bande-annonce, dossier) est omis, faute de page à ouvrir. `BaseItemDto` gagne `MediaType` et `ChildCount`.
- **Rangée « Listes de lecture »** (`playlistShelf`) sur l'accueil, **en dernier**, après « Derniers ajouts » de chaque bibliothèque : comme la rangée « Collection » d'une page film (ADR 0071), une rangée secondaire vient sous le contenu principal et n'apparaît que s'il y a quelque chose à ouvrir. Affiches de la grille (`ItemCard`), le nom sous l'affiche et « N titres » quand `ChildCount` est connu ; sans affiche, le nom dans la carte. Aucune liste vidéo : pas de rangée. Un échec de lecture des listes retire la rangée sans toucher au reste de l'accueil ; une session expirée renvoie à la connexion, comme partout (`playlistsOrNothing`).
- **Page liste** (`ui/PlaylistScreen.kt`, `DetailPage.Playlist`) : le nom, puis « 3 titres · 4 h 12 » (`playlistSummary`) — la durée totale seulement si chaque élément a une durée, une somme partielle se lirait comme le total —, puis les éléments en grille de cartes 16:9 comme « Reprendre » : un film avec son titre et son année, un épisode sous le nom de sa série avec « S1 · É2 — titre ». États « Chargement… », erreur avec « Réessayer », et « Aucun film ni épisode dans cette liste de lecture. ».
- **Navigation** : OK sur une liste ouvre sa page (pile de `MainActivity`, ADR 0063, 0068) ; OK sur un élément ouvre la page film ou la page épisode (`playlistEntryPage`) par-dessus, la liste mémorisant l'élément (`remembering`, `opened`) pour lui rendre le focus au retour. Retour depuis un épisode ouvert d'une liste revient à la liste et non à la page de sa série (`backFromEpisode`). Retour depuis la liste revient à l'accueil, sur la carte de la liste.
- **Pas de bouton « Lire »** : le lecteur lit un élément à la fois et n'enchaîne que l'épisode suivant d'une série ; « Lire » sur une liste lancerait le premier élément puis s'arrêterait, ce qui ne serait pas lire la liste. Une file de lecture demande un mécanisme à part : hors de ce lot.
- **Lecture seule** : ni création, ni modification, ni réordonnancement, ni suppression ; aucun appel en écriture vers `/Playlists`.

## Alternatives
- **`MediaTypes=Video` dans la requête des listes** : mesuré sur le serveur réel, il fait répondre toutes les vidéos. Écarté.
- **`GET /Users/{UserId}/Items?ParentId={PlaylistId}`** : fonctionnerait peut-être, mais `PlaylistService` documente précisément les éléments d'une liste dans leur ordre ; l'ordre de `Items` dépend de `SortBy`. Écarté.
- **Rangée juste sous « Reprendre » / « À suivre »** : les listes sont moins consultées que les ajouts récents ; en haut, elles pousseraient ces rangées vers le bas. Écarté.
- **Cartes portrait sur la page liste** : l'image principale d'un épisode est une image 16:9, recadrée en affiche elle devient illisible. Écarté.
- **Bouton « Lire » qui lance le premier élément** : voir Décision. Écarté.

## Conséquences
- L'accueil fait une requête de plus à chaque chargement (les listes), après les bibliothèques ; sans liste, la réponse est vide et rien ne change à l'écran.
- La page liste lit 300 éléments au plus, sans pagination ; une liste plus longue est tronquée.
- Les coches « vu » de la page liste se relisent à chaque retour sur elle ; la rangée de l'accueil, elle, n'a pas de marque « vu ».

## Vérification
- Tests unitaires : `PlaylistsTest` (requête des listes sans `MediaTypes`, filtre des listes vidéo, ordre et types des éléments, décodage des formes documentées, chemin et paramètres des deux appels, jeton absent de l'URL, ni `api_key` ni `X-Emby-Token`, présent dans `X-Emby-Authorization`, identifiants refusés avant tout appel) ; `PlaylistScreenTest` (rangée visible seulement avec une liste vidéo, libellés « N titres » et durée totale, page ouverte par un film ou un épisode, focus mémorisé et Retour d'un épisode vers la liste). `testDebugUnitTest` (433 tests, 0 échec), `assembleDebug` et `lintDebug` passent, sans avertissement de lint dans les fichiers changés.
- Serveur réel : requête des listes lue (0 liste sur le compte de test) et piège `MediaTypes` mesuré, voir « Contrat Emby vérifié ».
- Non vérifié sur le Shield : la rangée « Listes de lecture » en bas de l'accueil (il faut d'abord qu'une liste existe sur le compte utilisé), son affiche et « N titres », la page liste à 1080p (en-tête, résumé, cartes 16:9 des films et des épisodes), OK sur un film et sur un épisode, Retour avec le focus sur l'élément ouvert puis sur la carte de la liste à l'accueil, l'état vide et l'erreur.
