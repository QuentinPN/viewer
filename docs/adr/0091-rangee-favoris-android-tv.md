# ADR 0091 — Rangée « Favoris » sur l'accueil (Android TV)

Statut : Accepté (2026-11-10). Complète les ADR 0014, 0056, 0059 et 0088.

## Contexte
L'application Windows montre depuis l'ADR 0014 une rangée « Favoris » entre « À suivre » et « Ajoutés récemment ». Sur Android TV, les favoris ne servaient qu'à filtrer la grille d'une bibliothèque (filtre « Favoris », ADR 0013) : pour retrouver un film ou une série marqué depuis un autre client, il fallait ouvrir la bonne bibliothèque puis le filtre. Ce lot ajoute la rangée, en lecture seule.

## Contrat Emby vérifié
Aucune nouvelle route : la requête est celle de la rangée Windows (ADR 0014, `EmbyClient::favorites` côté Rust), dont le contrat a été vérifié dans `/emby/openapi.json` d'Emby 4.10.1.0 :
- `ItemsService` → `GET /Users/{UserId}/Items` (déjà employé par la grille, ADR 0013, et par les listes de lecture, ADR 0088) avec `Filters` (valeurs séparées par des virgules, dont `IsFavorite`), `Recursive`, `IncludeItemTypes`, `SortBy`, `SortOrder`, `Limit`, `Fields`, `EnableUserData`, `ImageTypeLimit`, `EnableImageTypes`. Réponse : `QueryResult<BaseItemDto>`.
- La valeur `IsFavorite` de `Filters` est celle que la grille Android TV envoie déjà (`ItemsOptions.filters()`), reprise telle quelle.

Contrat retenu : `GET /Users/{UserId}/Items` avec `Filters=IsFavorite`, `Recursive=true`, `IncludeItemTypes=Movie,Series`, `SortBy=SortName`, `SortOrder=Ascending`, `Limit=16`, `Fields=PrimaryImageAspectRatio,ProductionYear`, `EnableUserData=true`, `ImageTypeLimit=1`, `EnableImageTypes=Primary` (`EmbyClient.favorites`, `favoritesQuery`). Pas de `ParentId` : les favoris de toutes les bibliothèques. Jeton dans l'en-tête `X-Emby-Authorization`, jamais dans l'URL ni dans un journal.

`libraryQuery` (la requête de la grille) n'est pas réemployée : elle exige un `ParentId`, et ses tri et filtres viennent des options d'une grille. Seule la valeur `Filters` en est reprise, par `ItemsOptions(favorites = true).filters()`, pour que la rangée et une grille filtrée sur « Favoris » demandent la même chose au serveur.

**NON VÉRIFIÉ** dans ce lot : la réponse réelle du serveur pour cette requête sur Android TV (le serveur n'a pas été interrogé). Les tests se fondent sur la forme documentée et sur celle des favoris de l'ADR 0014 (un film, une série avec `UnplayedItemCount`).

## Décision
- **Lecture** (`emby/Favorites.kt`) : `favoriteTitles` garde les films et les séries, sans doublon, dans l'ordre du serveur, 16 au plus. Un identifiant inutilisable dans un chemin (vide, `../x`) est écarté : la carte ne pourrait pas construire l'URL de son affiche. Tout autre type (collection, épisode, dossier) est omis, comme sous Windows.
- **Ordre : par titre** (`SortName`, A → Z), comme sous Windows. Les favoris sont une liste que l'utilisateur compose lui-même : un ordre stable laisse chaque titre à sa place d'un chargement à l'autre, et le focus mémorisé au retour d'une page retrouve sa carte. `DateCreated` décroissant reproduirait l'ordre de « Derniers ajouts » juste en dessous, et ne dit pas quand le titre a été mis en favori : Emby ne documente aucun tri par date de mise en favori.
- **Rangée « Favoris »** (`favoriteShelf`) **après « À suivre » et avant « Derniers ajouts »**, comme sous Windows : ce sont des titres choisis, plus proches de ce qu'on veut regarder que les ajouts récents. Affiches portrait de la grille (`ItemCard`), avec la coche « vu » ou le nombre d'épisodes restants (`EnableUserData=true`) ; l'année sous le titre. Pas de carte « Tout voir » : la grille filtrée sur « Favoris » d'une bibliothèque reste le moyen de tout voir.
- **Chargement en parallèle** : la requête part en même temps que « Reprendre », « À suivre » et le héros, sans attendre les bibliothèques. Aucun favori : pas de rangée. Un échec de lecture retire la rangée sans toucher au reste de l'accueil ; une session expirée renvoie à la connexion, comme partout (`favoritesOrNothing`). Elle est relue avec les autres rangées après chaque lecture (`refresh`).
- **Navigation** : OK sur un film ouvre la page film, sur une série la page série, comme les autres rangées d'affiches ; le retour rend le focus à la carte ouverte (clé de rangée « Favoris »).
- **Lecture seule** : aucun ajout ni retrait de favori, aucun `POST` ni `DELETE` vers `/Users/{UserId}/FavoriteItems/{Id}`.
- **Épisodes écartés** : une carte d'épisode sur l'accueil le lance au lieu d'ouvrir une page, et son image 16:9 recadrée en affiche devient illisible ; les mêler aux affiches demanderait une autre forme de carte. Hors de ce lot.

## Alternatives
- **Réemployer `libraryQuery`** : exige une bibliothèque ; une requête par bibliothèque multiplierait les appels et mêlerait mal deux listes triées. Écarté.
- **Tri `DateCreated` décroissant** : voir Décision. Écarté.
- **Rangée en bas, comme « Listes de lecture »** : les favoris sont ouverts plus souvent que les listes ; l'ordre Windows est gardé. Écarté.

## Conséquences
- L'accueil fait une requête de plus à chaque chargement, en parallèle des premières ; sans favori, la réponse est vide et rien ne change à l'écran.
- Au-delà de 16 favoris, la rangée s'arrête aux 16 premiers par titre ; les suivants restent accessibles par le filtre « Favoris » de chaque bibliothèque.
- Un favori ajouté ou retiré depuis un autre client apparaît au prochain chargement de l'accueil (lancement, « Réessayer », retour d'une lecture), pas en direct.

## Vérification
- Tests unitaires : `FavoritesTest` (paramètres de la requête, sans `ParentId` ; films et séries seulement, sans doublon ni identifiant inutilisable, dans l'ordre du serveur, 16 au plus ; chemin `/emby/Users/u1/Items` et paramètres de l'appel avec MockWebServer, méthode `GET` ; jeton absent de l'URL, ni `api_key` ni `X-Emby-Token`, présent dans `X-Emby-Authorization` ; identifiant refusé et client non connecté avant tout appel) ; `FavoriteShelfTest` (rangée absente sans film ni série, titre « Favoris », affiches portrait, ni lecture immédiate ni « Tout voir »). `testDebugUnitTest` (456 tests, 0 échec), `assembleDebug` et `lintDebug` passent, sans avertissement de lint dans les fichiers changés.
- Serveur réel : non interrogé dans ce lot.
- Non vérifié sur le Shield : la rangée « Favoris » entre « À suivre » et « Derniers ajouts » à 1080p, ses affiches et leurs marques, OK sur un film et sur une série, Retour avec le focus sur la carte ouverte, la rangée cachée sans favori.
