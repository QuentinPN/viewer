# ADR 0101 — Grille de bibliothèque compacte (desktop)

Statut : Accepté (2026-11-11).

## Contexte
La grille de bibliothèque du desktop ouvrait sur une barre d'outils (ADR 0013, 0031) : listes « Trier par », bouton de sens, liste « Visionnage », bascule « Favoris », liste « Genre » et « Réinitialiser ». Le propriétaire la trouve chargée (« trop de boutons ») et juge la grille d'Emby « + compacte ». L'ADR 0099 a déjà adopté, sur Android TV, le modèle des apps Emby : un en-tête discret, un rail de lettres à gauche, un rail d'icônes rondes à droite et la grille entre les deux. Le desktop n'avait ni saut à une lettre, ni filtre par années, ni tirage aléatoire.

Contrats Emby utilisés, ceux déjà documentés par les ADR 0070, 0077 et 0082 sur `GET /Users/{UserId}/Items` :
- **index des lettres** : la requête de la grille (mêmes filtres, même tri), avec `Fields=SortName`, `EnableImages=false`, `EnableUserData=false`, sans `StartIndex` ni `Limit` ; seuls l'id et le nom de tri sont renvoyés à l'interface ;
- **aléatoire** : la requête de la grille avec `SortBy=Random&Limit=1` ; c'est le serveur qui tire ;
- **années** : `Years=1990,1991,…,1999` pour une décennie ; la liste des décennies vient d'une lecture unique `Fields=ProductionYear`, sans filtre ni pagination, faite à la première ouverture de « Filtres ».

Ces paramètres n'ont pas été rejoués sur le serveur réel pendant cette tranche ; ils sont couverts par le serveur simulé des tests Rust.

## Décision
- **La barre d'outils disparaît** (remplace l'interface des ADR 0013 et 0031 ; les options, leur stockage par serveur, utilisateur et bibliothèque, et leurs valeurs par défaut sont inchangés, plus un champ `decade`).
- **En-tête** : le titre (22 px) et le nombre de titres, aligné sur la grille ; une ligne d'avis courte s'y ajoute quand l'aléatoire ne trouve rien.
- **Rail de lettres à gauche**, de « # » à « Z », affiché seulement pour le tri par titre (dans les deux sens). Les titres sont classés par la première lettre de leur nom de tri, sans accent ni casse (Œ, Æ, ß, Ø compris) ; chiffres, symboles et autres écritures vont sous « # ». Les lettres sans titre sont estompées et désactivées. Un clic charge les pages jusqu'à celle du premier titre de la lettre (au plus 200 éléments par requête), fait défiler la carte au centre et lui donne le focus. Si l'index échoue, toutes les lettres restent cliquables et un clic relit l'index avant de sauter ; s'il échoue encore, rien ne bouge. Un titre écarté par Rust comme inutilisable est remplacé par son voisin dans l'ordre du serveur. La colonne garde sa largeur dans le gabarit quand le rail est caché, pour que la grille ne bouge pas en changeant de tri.
- **Rail d'icônes rondes à droite**, dans cet ordre : Tri, Visionnage, Favoris, Filtres, Aléatoire, Réinitialiser.
  - **Tri** ouvre un panneau (Popover API, ancré sur l'icône, retourné vers le haut s'il manque de place) avec « Trier par » et « Ordre » en boutons radio.
  - **Visionnage** fait défiler les choix (Tous, Non vus, Vus, et En cours pour les films) ; **Favoris** bascule.
  - **Filtres** ouvre un panneau avec « Genre » et « Années » (décennies, les plus récentes d'abord).
  - **Aléatoire** ouvre la fiche d'un titre tiré par le serveur avec les filtres en cours ; sinon « Aucun titre avec ces filtres. » ou « Cette bibliothèque est vide. ».
  - **Réinitialiser** n'apparaît que si les options diffèrent des valeurs par défaut ; un emplacement vide garde sa place pour que les icônes ne bougent jamais.
  - Une icône est dessinée en couleur d'accent quand son choix trie ou filtre. Chaque icône a une info-bulle (survol et focus clavier) et un `aria-label` qui dit l'état, par exemple « Tri : Titre, A → Z », « Visionnage : Non vus », « Favoris seulement : oui », « Filtres : Drame, années 1990 ».
- **Grille** : `repeat(auto-fill, minmax(150px, 1fr))`, soit environ 7 affiches à 1600 px et 3 à 640 px. Pagination par `IntersectionObserver` inchangée, avec « Réessayer » et « Afficher la suite ».
- **Compteur « n | total »** en bas à droite : position de la carte qui a le focus, sinon de la première carte visible (recherche dichotomique sur les rangées, une fois par image d'animation). Purement visuel (`aria-hidden`) : le nombre de titres est déjà dans l'en-tête.
- **Conservés** : états vide, aucun résultat, chargement et erreur ; fiche `ItemDetail` ; menu des cartes de l'ADR 0100 (non déplacé, non modifié).
- **Petites fenêtres** (≤ 720 px) : icônes de 34 px et marges réduites ; le rail de lettres suit la hauteur de la fenêtre et défile si elle est trop basse.
- **Mouvement réduit** : les transitions des lettres, icônes et info-bulles sont coupées ; le saut à une lettre défile sans animation dans tous les cas.
- **Sécurité** : nouvelles commandes `emby_sort_names`, `emby_random_item`, `emby_production_years`. Le genre et la décennie sont validés avant toute requête (« genre invalide », « décennie invalide » : multiple de 10 entre 1880 et 2190) ; l'interface ne construit aucune URL ; le jeton reste dans l'en-tête.

## Chemins clavier
- Tab entre dans le rail de lettres (tabindex itinérant : un seul arrêt) ; Haut et Bas sautent les lettres sans titre, Début et Fin vont aux extrémités ; Entrée ou Espace saute à la lettre et met le focus sur sa première carte.
- Tab parcourt ensuite la grille, puis les icônes ; Haut et Bas passent d'une icône à l'autre.
- Entrée sur Tri ou Filtres ouvre le panneau, le focus va sur le choix en cours ; Échap ou un clic dehors le ferme et rend le focus à l'icône.
- Réinitialiser remet les options par défaut et met le focus sur Tri (l'icône Réinitialiser disparaît).
- Aléatoire ouvre la fiche ; la fermer rend le focus à l'icône Aléatoire.
- Le menu des cartes garde ses raccourcis (ADR 0100).

## Alternatives
- **Garder la barre d'outils et la resserrer** : moins de place gagnée, et un modèle différent de l'Android TV (ADR 0099). Rejeté.
- **Calculer l'index des lettres à partir des seules pages chargées** : les lettres lointaines resteraient inconnues tant que l'on n'a pas défilé. Rejeté au profit d'une requête légère sans images.
- **Tirer l'aléatoire côté client dans les pages chargées** : biaisé vers le début de la liste. Rejeté ; le serveur tire sur toute la bibliothèque filtrée.
- **Menus natifs ou listes déroulantes dans le rail** : les panneaux ancrés gardent le style de l'app et regroupent tri et sens. Retenu.

## Conséquences
- Une requête de plus à chaque changement d'options en tri par titre (l'index des lettres), sans images ni données utilisateur ; une seule lecture des années par bibliothèque.
- Le desktop n'a ni grilles de studios ni grille virtualisée : rien à conserver de ce côté, la pagination reste le garde-fou de la mémoire.
- Le positionnement par ancre CSS demande un WebView2 récent ; sans lui, le panneau s'ouvre au centre (comportement par défaut d'un popover), toujours utilisable.

## Non vérifié
- Aucun lancement de l'app ni connexion au serveur réel dans cette tranche : rendu, positionnement des panneaux, retour du focus à la fermeture d'un panneau, hauteur du rail de lettres à 640×360 et compteur n'ont pas été vus à l'écran.
- La réponse réelle d'Emby à `Years=`, `Fields=SortName` sans pagination et `SortBy=Random&Limit=1` n'a pas été rejouée sur le serveur ; l'ordre de `SortName` pourrait différer de notre classement par lettre pour des titres à articles ignorés par le serveur.
- Lecteurs d'écran non testés.

## Vérification
- Rust : tests `items_refuse_a_decade_the_server_cannot_filter_on`, `sort_names_follow_the_grid_query_without_paging_or_images`, `random_item_is_drawn_by_the_server_with_the_grid_filters`, `production_years_are_read_once_without_filters`, et le test des options complètes étendu à `Years`.
- Vitest : `src/lib/library/rails.test.ts` (lettres, index, pas du rail, limite du saut, libellés et états des icônes, décennies, compteur, première carte visible) et cas de décennie dans `options.test.ts`.
- `npm run -s verify:web` et `npm run verify:rust` passent.
