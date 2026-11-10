# ADR 0071 — Rangée « Collection » d'un film (Android TV)

Statut : Accepté (2026-11-10). Complète les ADR 0063 et 0068.

## Contexte
Une page film ne disait pas qu'il appartient à une saga : pour voir *Dune : Deuxième partie* depuis *Dune*, il fallait revenir à la grille ou espérer le trouver dans « Titres similaires ». Emby regroupe pourtant ces films dans des collections (éléments `BoxSet`), créées à la main ou par le serveur à partir des métadonnées.

## Contrat Emby vérifié
Documentation officielle, consultée le 2026-11-10 :
- [`BaseItemDto`](https://dev.emby.media/reference/pluginapi/MediaBrowser.Model.Dto.BaseItemDto.html) a une propriété `Collections` de type `NameLongIdPair[]` (sans description) : les collections qui contiennent l'élément, nom et identifiant numérique.
- `ItemsService` → [`GET /Users/{UserId}/Items`](https://dev.emby.media/reference/RestAPI/ItemsService/getUsersByUseridItems.html) : `ParentId` (« Specify this to localize the search to a specific item or folder »), `SortBy` (dont `PremiereDate`, `ProductionYear`, `SortName`, plusieurs clés séparées par des virgules), `SortOrder`, `Limit`, `Fields`, `EnableUserData`, `ImageTypeLimit`, `EnableImageTypes`. Réponse : `QueryResult<BaseItemDto>`, déjà décodé.
- Pas de paramètre `ListItemIds` dans cette page ni dans [`GET /Items`](https://dev.emby.media/reference/RestAPI/ItemsService/getItems.html) : la piste « chercher les BoxSet qui contiennent ce film » n'est pas documentée et n'est pas employée. `CollectionService` (`/Collections`) ne sert qu'à créer une collection et à y ajouter ou retirer des éléments.

Contrat retenu :
1. Les collections d'un film viennent de son propre détail, `GET /Users/{UserId}/Items/{Id}` sans `Fields` (`EmbyClient.fullItem`, déjà lu par la page) : aucune requête de plus quand le film n'est dans aucune collection.
2. Les titres d'une collection : `GET /Users/{UserId}/Items` avec `ParentId={BoxSetId}`, `SortBy=PremiereDate,ProductionYear,SortName`, `SortOrder=Ascending`, `Limit=100`, `Fields=PrimaryImageAspectRatio,ProductionYear`, `EnableUserData=true`, `ImageTypeLimit=1`, `EnableImageTypes=Primary` (`EmbyClient.collectionItems`).

Le jeton passe, comme pour tous les appels, dans l'en-tête `X-Emby-Authorization` ; jamais dans l'URL ni dans un journal.

**NON VÉRIFIÉ** (aucun appel au serveur réel pour ce lot) : qu'Emby 4.10 renvoie bien `Collections` dans le détail sans `Fields` (la liste des valeurs de `Fields` ne le cite pas) ; la forme réelle des entrées (`Id` nombre ou chaîne) ; qu'une BoxSet employée comme `ParentId` liste ses titres sans `Recursive` ; la présence de `PremiereDate` dans cette réponse sans la demander dans `Fields`. Si `Collections` manque, la rangée n'apparaît jamais, sans autre effet.

## Décision
- **Lecture** (`emby/Collections.kt`) : `BaseItemDto.rawCollections` garde `Collections` tel quel (`JsonElement`) ; `collectionRefs` en tire `CollectionRef(id, nom)`. `Id` est lu comme n'importe quelle valeur primitive (nombre documenté, chaîne acceptée), une entrée sans nom ou avec un identifiant que `segment` refuserait est omise, les doublons aussi, trois collections au plus (`COLLECTIONS_LIMIT`). Toute autre forme du champ donne une liste vide : la rangée disparaît, la page film reste lisible.
- **Ordre** (`collectionTitles`) : films et séries seulement, sans doublon, par date de sortie (`PremiereDate`), sinon par année, puis titre A → Z ; sans date ni année en dernier. Le serveur trie déjà ; le client garde cet ordre quoi que le serveur fasse des dates manquantes.
- **Rangée « Collection : <nom> »** (`ui/CollectionRow.kt`) sur la page film, sous « Distribution » et avant « Titres similaires » : affiches de la grille (`ItemCard`), le film de la page compris, en place, marqué « Ce film » en couleur d'accent ; OK sur lui ne fait rien. Une rangée par collection. Une collection où le film serait seul n'a pas de rangée (`collectionShelf`). La rangée commence défilée jusqu'au film de la page.
- **Chargement** : les collections sont lues après le film (`rememberCollections`), une requête par collection, sans bloquer la page ; un échec retire la rangée en silence ; une session expirée renvoie à la connexion, comme partout. Lues une fois par film : une lecture ne change pas les collections (les coches « vu » des affiches ne se rafraîchissent donc pas après une lecture).
- **Place** : sous « Distribution » plutôt que juste sous les détails. La rangée arrive après la page ; placée au-dessus de « Distribution », elle pousserait vers le bas une carte de personne déjà focalisée au retour d'une page personne.
- **Navigation et retour** : OK sur une affiche ouvre le film (ou la série) par-dessus, dans la pile de `MainActivity` (ADR 0063, 0068). `DetailPage.Movie` reçoit `collection` : `openFromCollection` y mémorise l'affiche (et oublie une carte de personne) ; Retour relit la page, la première rangée qui contient l'affiche lui rend le focus, sinon (rangée absente ou en échec) le bouton « Lire » le reprend dès que les collections sont lues. `remembering` (ouvrir une personne) efface `collection`.
- **Page collection** : non faite pour ce lot. Le titre de la rangée n'est pas focalisable ; y ajouter une page demanderait une nouvelle `DetailPage`, un écran et ses états, pour une liste que la rangée montre déjà en entier dans la plupart des sagas.

## Alternatives
- **Chercher les BoxSet par `ListItemIds`** (`IncludeItemTypes=BoxSet&Recursive=true&ListItemIds={filmId}`) : non documenté dans la référence REST consultée. Écarté.
- **Lire toutes les BoxSet puis leurs titres** pour trouver celles du film : une requête par collection de la bibliothèque à chaque page film. Écarté.
- **`GroupItemsIntoCollections`** : regroupe les films derrière leur collection dans une liste, ne dit pas à quelle collection appartient un film donné. Écarté.
- **Rangée au-dessus de « Distribution »** : voir « Place ». Écarté.

## Conséquences
- Une page film d'une collection coûte une requête de plus par collection (trois au plus), plus une image par affiche.
- Retour vers le film le relit et relit ses collections (bref « Chargement… »), comme depuis un titre similaire.

## Vérification
- Tests unitaires : `EmbyClientTest.collectionItemsAsksForTheTitlesOfOneBoxSet` (chemin, paramètres, jeton absent de l'URL, ni `api_key` ni `X-Emby-Token`, présent dans `X-Emby-Authorization`), `collectionItemsRejectsAnIdThatWouldChangeThePath` ; `CollectionsTest` (identifiant nombre ou chaîne, entrées refusées, forme inattendue, limite, requête, ordre de sortie, décodage du détail) ; `CollectionRowTest` (rangée sans autre film, rangée qui reprend le focus, pile et Retour, série d'une collection). `testDebugUnitTest` (306 tests, 0 échec), `assembleDebug` et `lintDebug` passent, sans avertissement de lint dans les fichiers changés.
- Non vérifié sur le Shield ni sur le serveur : la présence et la forme de `Collections`, la liste et l'ordre réels d'une collection, la place de la rangée à 1080p, le marquage « Ce film », Retour avec le focus sur l'affiche ouverte.
