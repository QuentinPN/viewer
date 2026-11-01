# ADR 0011 — Recherche

Statut : Accepté (2026-10-28). Les épisodes sont cherchés depuis l'ADR 0020.

## Contexte
`PRODUCT.md` demande la recherche dans les bibliothèques de films et de séries, et `DESIGN.md` la place avec le tri et les filtres. Emby 4.10.1.0 n'expose pas `/Search/Hints` : ce chemin est absent de son `/emby/openapi.json`. Le moyen documenté est le paramètre `SearchTerm` de `GET /Users/{UserId}/Items`.

Ce qui a été observé sur le serveur, en lecture seule, avec le compte de test, le 2026-10-28 (`Recursive=true`, `IncludeItemTypes=Movie` puis `Series`) :
- La recherche ne tient compte ni des accents ni de la casse : « reincarne » et « réincarne » donnent les mêmes résultats. En revanche, la ligature n'est pas repliée : « coeur » ne trouve pas « Nos cœurs meurtris ».
- Elle porte sur le **début des mots** du titre : « heure » trouve « 24 Heures chrono », « eures » ne trouve rien. « chien flic » trouve « Rex, chien flic ».
- Une seule lettre ne renvoie rien.
- **`TotalRecordCount` n'est pas fiable** : il vaut souvent 0 alors que des éléments sont renvoyés (« the » : 8 films, total 0).
- Temps mesuré : 25 à 250 ms par appel, jusqu'à 570 ms au premier appel.
- L'ordre des résultats est celui du serveur, sans tri par nom.

## Décision
- Une commande Rust `emby_search(query)` lance **deux requêtes en parallèle**, l'une pour les films, l'autre pour les séries. Chacune demande 25 éléments et en affiche au plus 24 : un 25ᵉ indique qu'il y en a d'autres (`moreMovies`, `moreSeries`), puisque le total n'est pas fiable.
- Rust nettoie la requête avant de la passer au serveur :
  - espaces retirés au début et à la fin, et réduits à un seul entre les mots ;
  - caractères de contrôle supprimés ;
  - 100 caractères au plus.

  En dessous de 2 caractères, la commande ne contacte pas le serveur et renvoie un résultat vide. Elle renvoie aussi la requête nettoyée, ce qui permet à l'interface d'ignorer une réponse qui ne correspond plus à la saisie.
- Seuls les types demandés sont gardés (`Movie`, `Series`). Les épisodes ne sont pas cherchés dans cette tranche, car ils n'ont pas encore de fiche à eux.
- Interface :
  - un champ « Rechercher » en tête de la barre latérale (`role="search"`), que `Ctrl+F` ou `/` placent au premier plan ;
  - la recherche part 250 ms après la dernière frappe, ou tout de suite avec Entrée ;
  - les résultats remplacent la vue courante, en deux grilles d'affiches, « Films » puis « Séries ». Chaque carte ouvre la fiche, et le retour rend le focus à la carte ;
  - Échap dans le champ, ou un champ vidé, ramène à la vue précédente (accueil ou bibliothèque) ;
  - les états sont distincts : chargement (squelette), aucun résultat (avec le rappel que la recherche porte sur le début des mots), erreur avec « Réessayer ». Un message annonce le nombre de résultats aux lecteurs d'écran (`aria-live`).

## Alternatives
- **Une seule requête `Movie,Series`** : un seul appel, mais une série très présente pourrait évincer tous les films. Comme le total n'est pas fiable, on ne pourrait pas non plus dire ce qui manque. Écarté.
- **Une recherche dans chaque bibliothèque (`ParentId`)** : un appel par bibliothèque, pour un résultat identique, puisque les droits sont déjà appliqués par utilisateur. Écarté.
- **Filtrer côté client les éléments déjà chargés** : cela ne couvrirait que les pages déjà vues. Écarté.
- **Remplacer « œ » par « oe » dans la requête** : cela casserait la recherche des titres qui contiennent vraiment « œ ». Le serveur fait foi. Écarté.

## Conséquences
- Il n'y a pas d'historique de recherche. Emby a bien `RecentlySearched`, mais ce serait une écriture sur le serveur.
- Les résultats ne sont pas paginés : au-delà de 24 par type, un message invite à affiner la recherche.
- Les épisodes, les personnes et les genres ne sont pas cherchés.
