# ADR 0044 — Grille de bibliothèque (Android TV)

Statut : Accepté (2026-11-09). Complète l'ADR 0039.

## Contexte
L'accueil Android TV ne montrait que les 16 derniers ajouts de chaque bibliothèque. Un film ou une série plus ancienne restait hors d'atteinte depuis la Shield.

## Décision
- **Entrée** : une carte « Tout voir » en fin de chaque rangée « Derniers ajouts ». Elle ouvre toute la bibliothèque en grille d'affiches. Retour ramène à l'accueil, avec le focus sur cette carte.
- **Requête** : la même que la grille Windows. `GET /Users/{id}/Items` avec `ParentId`, `Recursive=true`, `IncludeItemTypes=Movie` ou `Series`, `SortBy=SortName`, `SortOrder=Ascending`, `StartIndex` et `Limit=60` (`EmbyClient.libraryItems`). Seuls des ids de bibliothèque lus dans `/Views` sont envoyés, car le serveur ignore un `ParentId` inconnu et renvoie tout le serveur.
- **Pages au fil du défilement** : `LazyVerticalGrid` à colonnes adaptatives (150 dp minimum, 5 colonnes sur la Shield). La page suivante est demandée quand la dernière carte visible arrive à 18 cartes de la fin de ce qui est chargé (`shouldLoadMore`). Une page vide arrête la pagination. Un id déjà reçu n'est pas affiché deux fois.
- **État gardé** : pages chargées, défilement et carte ouverte vivent dans `LibraryMemory`. Une page de film ou de série ouverte depuis la grille revient sur la même carte.
- **Erreurs** : sans aucune carte, message et « Réessayer ». Si une page suivante échoue, les cartes restent et « Réessayer » s'affiche en bas de la grille.

## Alternatives
- **Tri et filtres** (ADR 0013 sous Windows) : reportés à une tranche suivante ; le titre suffit pour retrouver une affiche.
- **Bibliothèques dans un menu latéral** : il faudrait une navigation propre à la TV. Pour l'instant, la carte de fin de rangée suffit.
- **Tout charger d'un coup** : 606 films, c'est autant d'affiches demandées et d'éléments en mémoire. Écarté.

## Conséquences
- Une bibliothèque sans ajout récent n'a pas de rangée, donc pas de carte « Tout voir ».
- La grille repart de zéro à chaque ouverture depuis l'accueil.
- Pas de marque « vu » sur les affiches de la grille, comme dans les rangées de l'accueil.
