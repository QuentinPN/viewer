# ADR 0013 — Tri et filtres de la grille d'une bibliothèque

Statut : Accepté (2026-10-28).

## Contexte
`PRODUCT.md` (P1) et `DESIGN.md` (« Films: poster grid, sort/filter/search ») demandent de trier et de filtrer la grille d'une bibliothèque. Jusqu'ici, la grille est toujours triée par nom (`SortBy=SortName`), page par page, avec un défilement infini (ADR 0004, `pager.ts`).

Le contrat a été vérifié dans `/emby/openapi.json` d'Emby 4.10.1.0 (lecture seule, 2026-10-28). `GET /Users/{UserId}/Items` accepte :
- `SortBy` (plusieurs clés séparées par des virgules : `SortName`, `DateCreated`, `ProductionYear`, `CommunityRating`, …) et `SortOrder` ;
- `Filters`, séparés par des virgules : `IsPlayed`, `IsUnplayed`, `IsFavorite`, `IsResumable`, … ;
- `Genres`, **séparés par `|`**.

`GET /Genres` accepte `ParentId`, `IncludeItemTypes`, `Recursive` et `UserId`. Le comportement réel sur le serveur est consigné dans la preuve (`docs/evidence/gate3-tri-filtres-2026-10-28.md`).

## Décision
- **Le serveur trie et filtre** : la grille reste paginée et le total annoncé reste celui du serveur. Chaque changement d'option repart de la première page. Une réponse arrivée pour d'anciennes options est ignorée, grâce au compteur de génération déjà utilisé lors d'un changement de bibliothèque.
- Options, typées en Rust (`ItemsOptions`) et traduites en paramètres par Rust seulement ; l'interface n'écrit jamais de paramètre Emby :
  - tri : **Titre** (`SortName`), **Date d'ajout** (`DateCreated,SortName`), **Année** (`ProductionYear,SortName`), **Note** (`CommunityRating,SortName`) ; plus un sens, croissant ou décroissant. Le sens par défaut dépend du tri : A→Z pour le titre, du plus récent ou du mieux noté pour les autres ;
  - visionnage : **Tous**, **Non vus** (`IsUnplayed`), **Vus** (`IsPlayed`), et pour les films **En cours** (`IsResumable`) ;
  - **Favoris** (`IsFavorite`) ;
  - **Genre** : un seul à la fois, choisi parmi ceux que renvoie `emby_genres(library_id)` pour cette bibliothèque. Rust refuse un genre vide, trop long (plus de 100 caractères), contenant un caractère de contrôle ou un `|` (le séparateur d'Emby). Les genres contenant `|` ne sont pas proposés.
- Les choix sont gardés **par bibliothèque**, pour le compte et le serveur courants, dans le `localStorage` de la WebView. C'est une préférence d'affichage, sans donnée sensible ; une valeur illisible revient aux valeurs par défaut.
- Interface : une barre d'outils sous le titre de la bibliothèque, avec des `<select>` natifs (clavier, lecteurs d'écran) et des boutons à bascule (`aria-pressed`). Elle comprend :
  - le total filtré ;
  - « Réinitialiser » quand une option diffère des valeurs par défaut ;
  - un état vide propre aux filtres (« Aucun film ne correspond à ces filtres ») avec « Réinitialiser les filtres ».

## Alternatives
- **Trier ou filtrer côté client** : faux dès que toute la bibliothèque n'est pas chargée (707 films sur le NAS de test). Écarté.
- **Plusieurs genres à la fois** : Emby les combine avec un « ou », ce qui surprend dans une interface de filtre. Reporté.
- **Années, décennies, studios, résolution** : utiles mais pas demandés pour cette tranche. Reporté.
- **Préférences dans SQLite ou côté serveur (`DisplayPreferences`)** : SQLite n'existe pas encore, et le serveur demanderait une écriture. Écarté pour l'instant.

## Conséquences
- `emby_items` reçoit un paramètre `options` facultatif ; sans lui, l'ancien comportement (tri par titre) est gardé.
- Nouvelle commande : `emby_genres(library_id)`.
- Les `<select>` reçoivent leur valeur par `value` et non par `selected` sur les `<option>` : une fois la liste modifiée par l'utilisateur, l'attribut `selected` n'est plus pris en compte (défaut trouvé pendant la vérification).
- La pagination dépend toujours de l'ordre du serveur : un élément ajouté ou vu pendant la navigation peut décaler une page. Les doublons sont déjà écartés par `appendPage`.
