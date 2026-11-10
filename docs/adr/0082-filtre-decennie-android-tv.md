# ADR 0082 — Filtre par décennie dans les grilles (Android TV)

Statut : Accepté (2026-11-10). Complète les ADR 0044 (grille), 0045 (tri et filtres), 0067 (contrôles en verre), 0070 (saut à une lettre), 0077 (« Au hasard ») et 0079 (grille d'un genre).

## Contexte
Trier par année (ADR 0045) range la bibliothèque, mais ne permet pas de dire « des films des années 1990 ». Il faut une pastille « Années » à côté de « Genre », qui ouvre une liste latérale des décennies présentes dans la bibliothèque et restreint la grille à l'une d'elles.

## Contrat Emby vérifié
- Documentation officielle, `ItemsService` `GET /Users/{UserId}/Items` (https://dev.emby.media/reference/RestAPI/ItemsService/getUsersByUseridItems.html), consultée le 2026-11-10 : `Years` est un paramètre de requête « Optional. If specified, results will be filtered based on production year. This allows multiple, comma delimeted. » ; la réponse est un `QueryResult_BaseItemDto` dont `BaseItemDto.ProductionYear` est « The production year ».
- **Pas de liste d'années documentée** : l'index https://dev.emby.media/reference/RestAPI.html ne contient pas de `YearsService`, et https://dev.emby.media/reference/RestAPI/YearsService.html répond 404 (même date). `GET /Years` existe peut-être sur le serveur, mais il n'est pas documenté : il n'est pas employé.
- Non vérifié contre le serveur réel (Emby 4.10.2.0) pour cette tranche.

## Décision
- **Modèle** : `ItemsOptions.decade` (première année, ex. `1990`, ou `null` pour toutes). Fonctions pures dans `emby/Decades.kt` : `decadeOf` (1987 → 1980), `decadeYears` (`1990,1991,…,1999`), `isUsableDecade` (multiple de 10 entre 1880 et 2190), `decadesOf` (décennies d'une liste d'années, la plus récente d'abord, sans doublon). `ItemsOptions.years()` donne la valeur de `Years`, ou `null`.
- **Requête** : `libraryQuery` ajoute `Years` quand une décennie valide filtre. Comme la requête est partagée, les pages de la grille, l'index des lettres (ADR 0070) et « Au hasard » (ADR 0077, `randomPick` garde `Years`) suivent tous la décennie. Elle se combine avec `Filters` et `Genres`. Une décennie invalide (préférence abîmée) n'est jamais envoyée.
- **Liste des décennies** : `EmbyClient.decades` lit une fois, à la première ouverture de la liste, l'année de production de tous les titres de la bibliothèque (`/Users/{UserId}/Items` avec `ParentId`, `Recursive=true`, `IncludeItemTypes`, `Fields=ProductionYear`, `EnableImages=false`, `EnableUserData=false`, sans pagination), puis `decadesOf` en tire les décennies. Seule route documentée qui donne les années ; même coût que l'index des lettres (une réponse légère pour ~600 titres). La liste couvre toute la bibliothèque, sans les autres filtres, comme celle des genres ; elle est gardée dans `LibraryMemory.decades` et n'est pas relue à chaque changement. Un titre sans année n'apparaît dans aucune décennie.
- **Pastille** : « Années : Toutes » ou « Années : 1990 », juste après « Genre ». Pleine quand elle filtre, comme Genre. Elle ouvre une liste latérale (« Toutes les années », puis « Années 2020 », « Années 2010 »… la plus récente d'abord), le même composant que la liste des genres (`SidePicker`), focus sur le choix en cours. Retour ferme sans changement ; à la fermeture, le focus revient sur la pastille.
- **Mémoire** : enregistrée avec les autres choix de la grille de l'accueil (`LibraryPreferenceStore`, clé `decade`, `-1` pour toutes). La grille d'un genre (ADR 0079) n'enregistre rien et part sans décennie (`genreOptions` ne la reprend pas). « Réinitialiser » revient à toutes les années.
- **État vide** : une décennie compte comme filtre (`narrowed`) : « Aucun titre ne correspond à ces filtres. » et, pour « Au hasard », « Aucun titre avec ces filtres. ».
- **Largeur** : la barre compte maintenant jusqu'à neuf pastilles et dépasse la largeur de la Shield (960 dp) ; elle défile déjà horizontalement et le focus amène la pastille visée à l'écran. Rien ne change dans ce comportement.

## Alternatives
- **`GET /Years`** : non documenté sur dev.emby.media ; écarté tant qu'il ne l'est pas.
- **Décennies fixes de 1920 à aujourd'hui** : aucune requête, mais des choix vides ; gardé comme repli si la lecture des années devenait trop lourde.
- **« Avant 1970 » regroupé** : une décennie par ligne reste lisible avec une bibliothèque de quelques décennies ; pas de regroupement.
- **`MinPremiereDate`/`MaxPremiereDate`** : documentés, mais filtrent la date de sortie, pas l'année de production que montre la grille et que trie « Année ».

## Conséquences
- Une requête de plus, une fois par ouverture de bibliothèque, à la première ouverture de la liste.
- Non vérifié sur la Shield ni contre le serveur réel : rendu et défilement de la barre, chemin du focus, réponse du serveur à `Years`, temps de lecture des années.
