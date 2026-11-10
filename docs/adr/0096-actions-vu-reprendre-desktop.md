# ADR 0096 — Marquer comme vu et retirer de « Reprendre » (desktop)

Statut : Accepté (2026-11-10).

## Contexte
L'app desktop ne savait changer l'état « vu » qu'en lisant un titre jusqu'au bout. Il n'y avait aucun moyen de marquer un film, un épisode, une saison ou une série comme vu ou non vu, ni de sortir un titre de « Reprendre » sans le regarder. Les contrats Emby utilisés sont vérifiés dans la documentation de l'API :
- `POST /Users/{UserId}/PlayedItems/{Id}`, avec un `DatePlayed` facultatif au format `yyyyMMddHHmmss`, répond un `UserItemDataDto` ;
- `DELETE /Users/{UserId}/PlayedItems/{Id}` répond un `UserItemDataDto` ;
- `POST /Users/{UserId}/Items/{Id}/HideFromResume?Hide=true|false` répond un `UserItemDataDto`.

Aucun autre endpoint n'est utilisé.

## Décision
- **Client** : `EmbyClient::set_played` (déjà utilisé par les tests sur le vrai serveur) devient du code de production. Il envoie `POST` avec une date et `DELETE` sans date. `hide_from_resume` envoie `Hide=true`. Le jeton reste dans l'en-tête `X-Emby-Token`, jamais dans l'URL ni dans les journaux.
- **`DatePlayed`** : on envoie toujours la date courante en UTC. C'est ce que fait l'interface web d'Emby (`markPlayed` lui passe `new Date()`), la valeur reste explicite, et le test réel existant l'utilisait déjà. Marquer comme non vu n'envoie aucune date.
- **État renvoyé** : le `UserItemDataDto` ne contient pas la durée, donc il ne permet pas de calculer la fraction de reprise. Après le changement, l'item est relu avec `GET /Users/{u}/Items/{id}`, comme après une lecture. Si cette relecture échoue, l'état est construit depuis la réponse du changement, sans fraction, et l'échec est journalisé par son type uniquement.
- **Diffusion** : les commandes `emby_set_played` et `emby_hide_from_resume` émettent l'événement existant `emby://playback-stopped` avec le nouvel état. L'accueil, la fiche, la liste des épisodes et les grilles de bibliothèque ou de recherche suivent donc le changement, d'où qu'il vienne. Le nom de l'événement est conservé pour éviter de tout renommer.
- **Fiche** : un bouton « Vu » avec une coche se place avant le cœur. Il utilise `aria-pressed`, et son `title` dit l'action : « Marquer comme vu » ou « Marquer comme non vu ». Le libellé visible ne change pas : un libellé qui change en même temps que l'état pressé se lit mal au lecteur d'écran (même modèle que le favori). Le changement s'affiche tout de suite ; en cas d'échec, l'état précédent revient et un message non bloquant s'affiche. Tant qu'une requête est en cours, on ne peut pas recliquer.
- **Épisodes** : la coche d'un épisode devient un bouton placé à côté de la vignette, pas dedans, pour ne pas lancer la lecture. Elle reste visible quand l'épisode est vu, et apparaît au survol ou au focus clavier sinon. Un bouton « Saison vue » marque toute la saison. Après une saison ou une série, les épisodes affichés et les compteurs de saisons sont relus.
- **« Reprendre »** : chaque carte porte deux petits boutons ronds en verre en haut à droite, qui apparaissent au survol ou au focus (`:focus-within`) : « Marquer comme vu » et « Retirer de Reprendre ». C'est le modèle le plus léger : pas de menu contextuel, pas de confirmation, puisque l'action se défait (relire le titre ou le marquer non vu). La carte disparaît tout de suite et le focus passe à la carte voisine. En cas d'échec, elle revient à sa place et un message s'affiche. Une seule carte à la fois.

## Alternatives
- **Menu contextuel « … » sur les cartes** : plus de code, et un clic de plus pour une action fréquente. Rejeté.
- **Calculer l'état dans le navigateur à partir de la réponse** : la fraction de reprise ne serait pas disponible. Rejeté au profit de la relecture.
- **Un nouvel événement `emby://watched-changed`** : il aurait fallu doubler tous les écouteurs. Rejeté.

## Conséquences
- Une grille filtrée sur « Non vus » garde l'item jusqu'au rechargement. Le compteur « non vus » d'une série dans une grille est effacé plutôt que recalculé.
- Une erreur 401 ferme la session, comme pour les autres appels.

## Non vérifié
Rien n'a été essayé sur le vrai serveur dans ce changement. En particulier :
- que marquer une série ou une saison marque aussi ses épisodes depuis le desktop (la cascade n'est pas documentée ; l'app relit les épisodes après coup). Le même appel depuis Android TV l'a montrée sur le serveur réel, voir l'ADR 0097 ;
- ce que `HideFromResume` fait de la position de reprise, et si l'item quitte bien `/Users/{u}/Items/Resume` ;
- le rendu et le parcours au clavier dans l'app (seuls svelte-check, Biome et Vitest sont passés).

## Vérification
- Tests Rust sur serveur simulé : méthode, chemin, requête et en-tête de jeton pour `POST`/`DELETE` `PlayedItems` et `HideFromResume`, aucun jeton dans l'URL, relecture de l'état et repli quand elle échoue, fin de session sur 401, format de `DatePlayed`.
- Vitest : changement optimiste et retour en arrière, libellés, mise à jour des listes, retrait et remise d'une carte.
