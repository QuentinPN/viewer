# ADR 0070 — Saut à une lettre dans la grille (Android TV)

Statut : Accepté (2026-11-10). Complète les ADR 0044 (grille par pages de 60), 0045 (tri et filtres) et 0067 (contrôles en verre).

## Contexte
Sur 606 films triés par titre, atteindre « M » demande de faire défiler environ 300 affiches à la croix directionnelle. Il faut un saut direct à une lettre, sans casser la grille continue paginée par 60 ni les filtres (visionnage, favoris, genre).

## Contrat Emby vérifié
Documentation officielle, `ItemsService` `GET /Users/{UserId}/Items` (https://dev.emby.media/reference/RestAPI/ItemsService/getUsersByUseridItems.html), consultée le 2026-11-10 :
- `NameStartsWith` : « items whose name is sorted equally than a given input string » ;
- `NameStartsWithOrGreater` : « sorted equally or greater than a given input string » ;
- `NameLessThan` : « equally or lesser than a given input string » ;
- `StartIndex`, `Limit`, et `TotalRecordCount` dans `QueryResult_BaseItemDto` ;
- `Fields` accepte `SortName` ; `BaseItemDto.SortName` est une chaîne ; `EnableImages` et `EnableUserData` existent.

La documentation ne dit pas sur quelle colonne portent les trois filtres de nom, ni avec quelle casse ou quelle collation ils comparent ; la description de `NameLessThan` (« equally or lesser ») contredit même son nom. Le `SortName` est calculé par le serveur (articles « Le », « The »… retirés) et sa casse n'est pas documentée : comparer « M » à « matrix » ou à « Matrix » ne donne pas le même résultat. Ces filtres n'ont pas été essayés sur le serveur réel (pas d'accès accordé pour cette tranche).

## Décision
- **Index des lettres par une seule requête** : `sortNames` relit la requête de la grille (même `ParentId`, types, `SortBy`, `SortOrder`, `Filters`, `Genres`) avec `Fields=SortName`, `EnableImages=false`, `EnableUserData=false`, sans `StartIndex` ni `Limit`. Le client range chaque titre sous la première lettre de son `SortName` (le `Name` s'il est vide), sans accent ni casse : « É » → E, « Œ » → O, chiffres, symboles et autres écritures → « # ». La première occurrence de chaque lettre donne sa position dans l'ordre du serveur et l'id du titre. L'index est gardé jusqu'au prochain changement de tri ou de filtre.
- **Pourquoi pas `NameLessThan` + `Limit=0`** : c'est un aller-retour par saut et le résultat dépend d'une comparaison non documentée (casse, accents, colonne). Avec l'index, la position vient de l'ordre même que la grille affiche : elle est juste quelle que soit la collation du serveur, les filtres s'appliquent d'eux-mêmes, et les lettres sans titre sont connues sans requête de plus.
- **Grille continue** : la position donne la fin de sa page (`pageEnd`, multiple de 60). La collecte des pages reste la seule à charger : elle lit en une requête tout ce qui manque jusqu'à cette page (`pageLimit`), puis reprend par 60 au défilement. Les titres d'avant restent chargés : remonter fonctionne, comme une lettre plus tôt. Le titre visé est retrouvé par son id (par sa position si la liste a bougé), amené à l'écran sans animation et prend le focus.
- **Télécommande : une pastille « Lettre » dans la barre**, présente seulement pour le tri par titre, dans les deux sens. OK ouvre un panneau latéral comme la liste des genres : « Aller à la lettre », puis « # » et A–Z en grille de 6 boutons ronds en verre (`GlassButton`, anneau d'accent, sans agrandissement si les animations sont coupées). Le focus s'ouvre sur la dernière lettre choisie, sinon la première qui a un titre. Une lettre sans titre est estompée et sautée par le focus. Retour ferme le panneau et rend le focus à « Lettre ».
- **Accessibilité** : « Aller à une lettre » sur la pastille ; « Aller à la lettre M », « Aller aux titres commençant par un chiffre ou un symbole », ou « Aucun titre à la lettre Q » sur les lettres.
- **Z → A** : l'index suit l'ordre décroissant ; « M » mène au premier titre en M affiché dans ce sens.

## Alternatives
- **Rail vertical de lettres au bord droit** : 27 lettres sur la hauteur d'un téléviseur font des cibles d'environ 14 dp, et y arriver demande Droite depuis la dernière colonne d'une grille adaptative dont le nombre de colonnes varie. Le panneau suit le modèle déjà connu des genres et atteint toute lettre en quelques appuis.
- **`NameStartsWithOrGreater` pour charger la grille depuis la lettre** : la grille ne commencerait plus au début ; remonter avant la lettre demanderait une pagination à rebours et des positions décalées. Une seule liste est plus simple et sûre.
- **Requête de comptage par saut (`NameLessThan`, `Limit=0`)** : écartée pour les raisons du contrat ci-dessus.

## Conséquences
- La réponse de l'index grossit avec la bibliothèque (un objet court par titre, sans image) ; pour quelques milliers de titres, elle reste raisonnable. Une bibliothèque de dizaines de milliers de titres demanderait de revoir ce choix.
- Un saut vers la fin charge d'un coup toutes les pages qui précèdent (métadonnées seulement ; les affiches ne se chargent qu'à l'écran).
- Le classement suit le `SortName` du serveur : « Le Fabuleux Destin… » est sous F, comme dans l'ordre de la grille. Si le serveur trie « É » après « z », les titres en É restent à leur place dans la grille et E mène au premier titre en E.
- `GlassButton` gagne un paramètre `enabled` (estompé, hors du chemin du focus).
- Non vérifié sur la Shield : rendu du panneau, chemin du focus, temps de chargement sur 606 films.
