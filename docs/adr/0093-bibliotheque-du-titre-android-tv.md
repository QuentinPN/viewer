# ADR 0093 — Genre et studio dans la bibliothèque du titre (Android TV)

Statut : Accepté (2026-11-10). Modifie les ADR 0079, 0087 et 0092.

## Contexte
Un genre ou un studio d'une fiche ouvrait la grille de la bibliothèque choisie par `sectionLibrary` : celle nommée « Films » ou « Séries », sinon la première de son type. Le propriétaire a quatre bibliothèques : Anime et Séries TV (séries), Films et Films Anime (films). Un genre d'une série d'anime ouvrait donc « Anime » ou « Séries TV » au hasard de l'ordre, parfois une bibliothèque qui ne contient pas le titre.

## Décision
- La fiche lit les parents du titre avec `GET /Items/{Id}/Ancestors?UserId={userId}`. La documentation Emby (`dev.emby.media/reference/RestAPI/LibraryService/getItemsByIdAncestors.html`) décrit cet appel : « Gets all parents of an item », `Id` dans le chemin, `UserId` en paramètre facultatif, réponse `BaseItemDto[]`, authentification requise. Le jeton reste dans l'en-tête `X-Emby-Authorization`.
- `titleLibrary` choisit, parmi les bibliothèques de l'accueil du même type, celle dont l'id est un parent du titre.
- Si les parents sont illisibles (erreur réseau, 404, JSON invalide, session expirée), pas encore reçus, ou si aucun ne correspond, le choix de `sectionLibrary` reste. Une session expirée est laissée à la fiche, qui la détecte déjà.
- Les parents sont lus avec la fiche (`produceState`), et la lecture est annulée quand on quitte la fiche. Les pastilles restent utilisables pendant la lecture : rien n'attend la réponse.

## Alternatives
- **Lire les parents au moment de l'appui** : l'appui attendrait le réseau, et il faudrait vérifier au retour que la fiche est toujours affichée. Rejeté.
- **Demander la grille sans `ParentId`, sur tout le serveur** : le titre de la grille et ses réglages enregistrés sont ceux d'une bibliothèque. Rejeté.
- **Le champ `ParentId` du titre** : pour une série ou un film rangé dans un dossier, c'est ce dossier et non la bibliothèque. Rejeté.

## Conséquences
- Une requête de plus par fiche de film ou de série. Elle est légère et sans images.
- Ce correctif suppose que les ids des vues de l'utilisateur (`/Users/{Id}/Views`) se retrouvent parmi les parents du titre. Sinon, le comportement de l'ADR 0079 reste.

## Vérification
- `EmbyClientTest` : l'URL est `/emby/Items/{Id}/Ancestors`, avec `UserId` comme seul paramètre ; le jeton est dans l'en-tête et absent de l'URL ; un id qui changerait le chemin est refusé ; une erreur 500, 404 ou 401, ou un JSON invalide, ne donne aucun parent.
- `GenresTest` : avec les quatre bibliothèques du propriétaire, une série d'Anime ouvre « Anime » et un film de Films Anime ouvre « Films Anime » ; sans parent connu, sans parent correspondant ou avec une bibliothèque de l'autre type, le choix de `sectionLibrary` reste.
- `testDebugUnitTest` (465 tests, 0 échec), `assembleDebug` et `lintDebug` passent.
