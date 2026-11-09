# ADR 0063 — « Titres similaires » sur les pages film et série (Android TV)

Statut : Accepté (2026-11-09). Complète les ADR 0050, 0054 et 0060.

## Contexte
Arrivé au bout d'une page film ou série, on ne pouvait que revenir en arrière. Emby sait proposer des titres proches d'un élément (genres, personnes, studios) ; aucune page de l'application ne s'en servait encore, ni sur TV ni sous Windows.

La navigation TV (`MainActivity`) tenait une page série et une page film dans deux variables, la série prioritaire sur le film. Ouvrir un film depuis une page série n'aurait donc rien affiché, et Retour depuis un titre ouvert ne pouvait ramener qu'à l'écran d'origine (accueil, bibliothèque, recherche).

## Contrat Emby vérifié
Documentation officielle, `LibraryService` → [`GET /Items/{Id}/Similar`](https://dev.emby.media/reference/RestAPI/LibraryService/getItemsByIdSimilar.html), consultée le 2026-11-09 :
- requiert une authentification utilisateur ; `Id` dans le chemin ;
- paramètres de requête documentés utilisés ici : `UserId`, `Limit`, `IncludeItemTypes` (liste séparée par des virgules), `Fields`, `EnableImageTypes`, `ImageTypeLimit`, `EnableUserData` ;
- réponse 200 : `QueryResult_BaseItemDto` (`Items`, `TotalRecordCount`), donc le `QueryResult<BaseItemDto>` déjà décodé par le client.

`Fields` vaut `PrimaryImageAspectRatio,ProductionYear`, comme la recherche (ADR 0011) : `ProductionYear` n'apparaît pas dans la liste d'options de cette page, mais la recherche l'envoie déjà sans erreur et le serveur ignore une valeur qu'il ne connaît pas. Aucun appel au serveur réel n'a été fait pour ce lot.

## Décision
- **Requête** (`EmbyClient.similar`) : `/Items/{Id}/Similar` avec `UserId`, `Limit=12`, `IncludeItemTypes=Movie,Series`, `Fields`, `EnableUserData=true`, `ImageTypeLimit=1`, `EnableImageTypes=Primary`. Ids contrôlés par `segment`, jeton dans l'en-tête `X-Emby-Authorization` seulement (test `similarAsksForFilmsAndSeriesWithPosters`).
- **Filtrage** (`similarTitles`, `ui/Similar.kt`) : films et séries seulement, sans l'élément de la page ni doublon, 12 au plus. Le serveur filtre déjà par type ; le client recontrôle, car une carte d'un autre type n'aurait pas de page où s'ouvrir.
- **Affichage** : une rangée « Titres similaires » sous le contenu existant, avec l'affiche de la bibliothèque et de l'accueil (`ItemCard` en portrait : titre, année, marque vu / épisodes restants, progression). Elle est lue **après** la page (une fois celle-ci chargée), une seule fois par titre ; une relecture après lecture (`refresh`) ne la relit pas.
- **Échec** : une erreur ou une réponse vide masque la rangée, titre compris ; la page reste. Seule une session expirée renvoie à la connexion, comme partout.
- **Mise en page** : la page film garde ses détails sur la hauteur de l'écran, dans une colonne qui défile ; la page série défile aussi. Bas depuis les boutons du film (ou « Version ») ou depuis la rangée d'épisodes atteint la rangée similaire, que le défilement amène à l'écran.
- **Ouverture et Retour** : OK sur une affiche ouvre la page film ou série du titre (`similarPage`). `MainActivity` tient désormais une **pile de pages** (`DetailPage.Movie` / `DetailPage.Series`) : l'accueil, la bibliothèque et la recherche la remplacent par une seule page, un titre similaire s'empile. Retour dépile : film → film similaire → Retour → premier film ; encore Retour → l'écran d'origine. La pile garde 10 pages au plus, la plus ancienne étant oubliée au-delà. La fiche épisode (ADR 0060) reste au-dessus de la pile ; son Retour remplace la page du dessus par la série sur la bonne saison et la bonne carte, sans toucher aux pages dessous.
- **État par page** : chaque page est recomposée à neuf (`key`) ; une page retrouvée par Retour est relue et le focus revient sur son premier bouton (film) ou sur l'épisode d'ouverture (série), pas sur l'affiche similaire choisie.

## Alternatives
- **Remplacer la page sans pile** : plus simple, mais Retour depuis un titre similaire ramènerait à l'accueil en perdant le film de départ, et une page série masquait un film ouvert depuis elle. Écarté.
- **Navigation Compose (`navigation-compose`)** : une pile complète avec sauvegarde d'état, mais une nouvelle dépendance et la réécriture de toute la navigation de `MainActivity` pour un seul besoin. Écarté ; une liste de pages suffit.
- **Garder les pages montées sous la nouvelle** pour retrouver le focus exact : même raison que l'ADR 0060. Écarté.
- **Lire `/Items/{Id}/Similar` en même temps que la page** : une requête de plus au premier affichage, pour une rangée hors de l'écran. Écarté.

## Conséquences
- Ouvrir une page film ou série coûte une requête de plus, après la page.
- Retour vers une page de la pile la relit (bref « Chargement… »).
- La page série défile désormais verticalement : le focus d'une carte d'épisode peut faire remonter légèrement la page pour l'afficher entière.
- Rien ne change à l'accueil (`HomeScreen.kt`, `HomeHero.kt` non modifiés) : seuls ses rappels d'ouverture passent par la pile.

## Vérification
- Tests unitaires : `EmbyClientTest.similarAsksForFilmsAndSeriesWithPosters` (chemin, paramètres, jeton absent de l'URL et présent dans l'en-tête) ; `SimilarTest` (films et séries seulement sans l'élément lui-même, doublons et limite, page ouverte par type, Retour film → film similaire → premier film, limite de la pile, Retour d'un épisode qui garde les pages dessous). `testDebugUnitTest` (204 tests, 0 échec), `assembleDebug` et `lintDebug` passent, sans avertissement de lint dans les fichiers changés.
- Non vérifié sur le Shield ni sur le serveur : la réponse réelle de `/Items/{Id}/Similar` (nombre, types, `ProductionYear`), Bas depuis les boutons et depuis la rangée d'épisodes, le défilement qui montre la rangée entière à 1080p, l'effet du défilement sur la rangée d'épisodes, Retour dans la pile et le focus retrouvé.
