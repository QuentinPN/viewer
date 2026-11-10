# ADR 0077 — « Au hasard » dans une bibliothèque (Android TV)

Statut : Accepté (2026-11-10). Complète les ADR 0044 (grille), 0045 (tri et filtres), 0067 (contrôles en verre) et 0070 (saut à une lettre).

## Contexte
Devant 606 films, choisir quoi regarder prend du temps. Une pastille « Au hasard » doit ouvrir la page d'un titre tiré parmi ceux que la grille montre, en respectant les filtres en cours (visionnage, favoris, genre).

## Contrat Emby vérifié
Documentation officielle, `ItemsService` `GET /Users/{UserId}/Items` (https://dev.emby.media/reference/RestAPI/ItemsService/getUsersByUseridItems.html), consultée le 2026-11-10 : `SortBy` accepte « Album, AlbumArtist, Artist, Budget, CommunityRating, CriticRating, DateCreated, DatePlayed, PlayCount, PremiereDate, ProductionYear, SortName, Random, Revenue, Runtime » ; `Limit` est « The maximum number of records to return ». Le serveur réel (Emby 4.10.2.0) a déjà répondu à `SortBy=Random` sur cette route (`docs/evidence/intros-generiques-2026-11-07.md`). Le client Windows n'emploie pas encore ce tri.

## Décision
- **Même requête que la grille** : `EmbyClient.randomLibraryItem` reprend `libraryQuery` (même `ParentId`, `Recursive`, `IncludeItemTypes`, `Filters`, `Genres`), puis la fonction pure `randomPick` (`emby/RandomPick.kt`) remplace `SortBy` par `Random`, retire `SortOrder` et `StartIndex`, et demande `Limit=1`, `EnableImages=false`, `EnableUserData=false`. Le tirage se fait côté serveur : pas besoin de connaître le total ni de charger la bibliothèque.
- **Ouverture** : le titre tiré s'ouvre comme une affiche de la grille (`Series` → page de série, sinon page de film). Rien n'est lu, rien n'est écrit sur le serveur : une seule requête `GET`.
- **Retour** : Retour revient à la grille, focus sur « Au hasard » (`LibraryMemory.focusRandom`), sans changer la carte retenue par la grille. Un nouvel appui tire un autre titre ; le même peut revenir, le tirage n'a pas de mémoire.
- **Pastille** : en fin de barre, après « Réinitialiser », toujours présente quel que soit le tri. Libellé « Au hasard », description « Ouvrir un titre au hasard ».
- **Chargement et erreurs** : pendant la requête, les appuis suivants sont ignorés. Une réponse vide affiche « Aucun titre avec ces filtres. » (« Cette bibliothèque est vide. » sans filtre) à côté du nombre de titres, comme la notice de l'accueil ; une erreur affiche son message, une session expirée renvoie à la connexion. La notice s'efface au prochain tirage ou changement de filtre. Un tirage parti avant un changement de filtre est abandonné.

## Alternatives
- **Tirer côté client dans les éléments chargés** : ne verrait que les pages déjà lues, donc surtout le début de l'ordre alphabétique.
- **`StartIndex` aléatoire avec le total** : deux requêtes, et un total qui peut changer entre les deux.
- **Lancer la lecture directement** : écarté, la pastille n'ouvre qu'une page ; le choix de version et la reprise restent sur la page du titre.

## Conséquences
- Une requête courte par appui ; le coût du tri aléatoire est côté serveur.
- Non vérifié sur la Shield ni contre le serveur réel pour cette tranche : rendu de la pastille, chemin du focus au retour, temps de réponse.
