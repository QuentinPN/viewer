# ADR 0079 — Genres ouvrables depuis les fiches film et série (Android TV)

Statut : Accepté (2026-11-10). S'appuie sur le filtre à un seul genre de l'ADR 0045, la ligne de l'année des ADR 0050 et 0074, et la pile de pages des ADR 0063 et 0068.

## Contexte
Les pages film et série affichent jusqu'à trois genres dans la ligne de l'année (« Action, Drame, Fantastique »). Ce texte n'est pas focalisable : impossible, à la télécommande, de passer d'un film à « tous les films d'action ». La grille de bibliothèque sait déjà filtrer sur un genre (ADR 0045) ; il manquait le chemin depuis la fiche.

Ce qui est déjà là, vérifié dans le code :
- `BaseItemDto.genres` est lu par `fullItem` (film) et `item` (série), sans `Fields` de plus.
- Les bibliothèques de films et de séries sont lues par l'accueil (`EmbyClient.views`, `GET /Users/{UserId}/Views`) et gardées dans `HomeMemory.state` (`HomeState.Ready.libraries`), que `MainActivity` possède déjà. Les pages de fiche ne s'ouvrent qu'après l'accueil.
- `BaseItemDto` Android ne lit pas `ParentId`, et le `ParentId` d'un film est son dossier sur le disque, pas forcément la vue de bibliothèque (non vérifié sur le serveur réel). Emby n'expose pas de champ « bibliothèque » dans le détail d'un élément.
- La grille enregistre chaque changement de tri ou de filtre dans `LibraryPreferenceStore` (rappel `onOptionsChanged` de `LibraryScreen`), et la recharge à chaque ouverture depuis la barre de l'accueil.

## Décision
- **Pastilles de genre** : une ligne « Genres » sous la fiche (réalisation, scénario, studio), avec le libellé dans le style des faits (`labelLarge`, colonne de 112 dp) et une pastille de verre par genre, la même que la barre d'outils de la grille (`GlassButton` et `PillText`, ADR 0067). Pas de nouveau style. Au plus trois genres, ceux de la ligne de l'année, dans l'ordre du serveur, sans doublon.
  - Film : la ligne se place entre la fiche et « Lire » ; Haut depuis « Lire » y mène.
  - Série : sous les deux lignes de fiche, au-dessus des saisons ; Haut depuis les saisons y mène.
- **Ligne de l'année** : les genres en disparaissent quand les pastilles les montrent, pour ne pas les lire deux fois à quelques lignes d'écart. Ils y restent quand aucune pastille n'est affichée (aucune bibliothèque connue), pour que l'information ne disparaisse jamais. Notes et « Vu » ne changent pas (ADR 0074).
- **Validation** : un genre n'a de pastille que si `isUsableGenre` l'accepte (ADR 0045) : jamais de nom vide, de plus de 100 caractères, avec `|` ou un caractère de contrôle. `genreOptions` refait le contrôle avant d'ouvrir la grille. Le nom est envoyé tel que le serveur l'a renvoyé, sans le retoucher.
- **Bibliothèque** : celle qu'ouvre l'entrée « Films » ou « Séries » de la barre de l'accueil (`sectionLibrary`, ADR 0064) : la bibliothèque de ce type nommée ainsi, sinon la première de ce type. Aucune requête ni aucun point d'accès nouveau : la liste vient de `HomeState.Ready.libraries`, déjà chargée par `views`. Sans bibliothèque du bon type, pas de pastille.
- **Grille ouverte** : une page `DetailPage.Grid` posée sur la fiche, dans la même pile que les titres similaires et les personnes. Elle porte son `LibraryMemory`, donc ouvrir un titre depuis elle puis revenir retrouve le défilement et la carte. Back depuis la grille rend la fiche, relue, avec le focus sur la pastille pressée (`DetailPage.Movie.genre`, `DetailPage.Series.genre`). Si le titre n'a plus ce genre, le focus va où il irait d'habitude.
- **Choix de la grille** : le tri et le sens enregistrés pour cette bibliothèque, le genre pressé, et aucun filtre de visionnage ni de favoris : un filtre « Non vus » enregistré cacherait justement le film vu d'où l'on vient.
- **Préférences** : cette grille n'enregistre rien. Ni son ouverture, ni les changements faits dans sa barre (`onOptionsChanged = {}`). La grille de la barre de l'accueil garde les choix de l'utilisateur. C'est un écart volontaire avec la grille de l'accueil, qui enregistre tout : ici le genre vient de la fiche, pas d'un choix de l'utilisateur dans la grille.
- **Logique pure** dans `ui/Genres.kt` (`genreChips`, `yearLineGenres`, `genreLibrary`, `genreOptions`, `openGenre`), testée par `GenresTest`. `remembering` et `openFromCollection` oublient la pastille quand on quitte la fiche autrement.

## Alternatives
- **Rendre la ligne de l'année focalisable** : un seul arrêt de focus pour trois genres, il faudrait un menu pour choisir. Et la ligne coupe les genres en « … » (ADR 0074).
- **Pastilles dans la ligne de l'année** : sur 60 % de la largeur, après l'année, la durée, le badge et deux notes, il ne reste pas la place de trois pastilles de 36 dp.
- **Pastilles à côté de « Lire »** : sur une série il n'y a pas de bouton de lecture, et la rangée de boutons du film porte déjà « Lire depuis le début » et la version.
- **Retrouver la bibliothèque exacte de l'élément** (`ParentId`, ancêtres) : il faudrait un appel que `EmbyClient` ne fait pas, et le `ParentId` d'un film n'est pas la vue. Avec une bibliothèque par type, ce qui est le cas courant, le résultat est le même.
- **Garder les filtres enregistrés** : rejeté, un filtre ancien peut vider la grille sans raison visible.
- **Enregistrer le genre comme un choix de grille** : la grille de « Films » s'ouvrirait ensuite filtrée sans que l'utilisateur l'ait voulu.

## Conséquences
- `PillText` passe de `private` à `internal` dans `LibraryScreen.kt` pour être partagé.
- Sur une série, la ligne « Genres » descend la rangée d'épisodes d'une pastille (environ 44 dp). La page défile déjà.
- Avec deux bibliothèques de films, un film de la seconde ouvre la grille de la bibliothèque « Films ». C'est une limite assumée tant qu'aucun appel sûr ne donne la bibliothèque d'un élément.
- Si l'accueil n'a pas chargé ses bibliothèques (échec réseau), il n'y a pas de pastille et les genres restent dans la ligne de l'année.

**NON VÉRIFIÉ** (aucun essai sur la Shield ni sur le serveur réel pour ce lot) : le placement et le focus des pastilles sur l'écran, le retour du focus sur la pastille après Back, la place laissée à la rangée d'épisodes sur une série, et que le filtre `Genres` d'Emby 4.10 accepte tous les noms de genre réels de la bibliothèque (accents, espaces).
