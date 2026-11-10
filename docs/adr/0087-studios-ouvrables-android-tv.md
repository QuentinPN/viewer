# ADR 0087 — Studios ouvrables depuis les fiches film et série (Android TV)

Statut : Accepté (2026-11-10). Reprend le modèle des genres ouvrables de l'ADR 0079, la fiche des ADR 0050 et 0068, et la grille de l'ADR 0013.

## Contexte
Les pages film et série montrent leurs studios dans la fiche : « Studio : Legendary » pour un film, « Diffusion : HBO » pour une série. Ce texte n'est pas focalisable : impossible, à la télécommande, de passer d'un film A24 à « tous les films A24 ». Les genres le permettent depuis l'ADR 0079 ; les studios suivent le même chemin.

Ce qui est déjà là, vérifié dans le code :
- `BaseItemDto.studios` est lu par `fullItem` (film) et `item` (série), sans `Fields` de plus.
- `NameDto` ne lit que le nom : `Studios[].Id` est un nombre sur Emby 4.10 (ADR 0050, fixtures Windows), contrairement aux autres identifiants.
- La documentation d'Emby (`GET /Users/{UserId}/Items`, dev.emby.media) décrit deux filtres : `Studios` (« filtered based on studio », plusieurs valeurs séparées par `|`) et `StudioIds` (même description, par identifiant). Aucun moyen d'échapper un `|` n'est documenté.
- La grille passe toutes ses requêtes (pages, index des lettres, « Au hasard ») par `libraryQuery` de `EmbyClient`, qui lit `ItemsOptions`.

## Décision
- **Filtre par nom** : la grille d'un studio envoie `Studios=<nom>`, le nom tel que le serveur l'a renvoyé. Pas `StudioIds` : il faudrait lire `Studios[].Id`, un nombre que l'application ne garde pas, et sa forme exacte attendue par le filtre n'est pas documentée. Les genres sont déjà filtrés par nom (`Genres`, ADR 0045).
- **Validation** : un studio n'a de pastille que si `isUsableStudio` l'accepte, la même règle que les genres : jamais de nom vide, de plus de 100 caractères, avec `|` ou un caractère de contrôle. Un nom avec `|` serait lu par le serveur comme deux studios et ne peut pas être échappé : il est ignoré, il reste lisible dans la fiche s'il est le seul. `studioOptions` et `libraryQuery` refont le contrôle avant d'envoyer.
- **Pastilles** : une ligne « Studio » (film) ou « Diffusion » (série) juste sous la ligne « Genres », mêmes pastilles de verre (`ChipLine`, partagée avec les genres). Au plus trois studios, dans l'ordre du serveur, sans doublon.
- **Fiche** : la ligne « Studio » / « Diffusion » des faits disparaît quand les pastilles la montrent, comme les genres de la ligne de l'année ; elle reste quand aucune pastille n'est affichée (aucune bibliothèque connue, aucun nom utilisable).
- **Bibliothèque** : celle des genres (`genreLibrary`, ADR 0079) : « Films » pour un film, « Séries » pour une série. Sans bibliothèque du bon type, pas de pastille.
- **Grille ouverte** : une `DetailPage.Grid` sur la fiche, avec le tri et le sens enregistrés, aucun autre filtre, et le studio dans `ItemsOptions.studio`. Son titre est le nom du studio (`gridTitle`) au lieu de celui de la bibliothèque. Pages, tri, filtres, lettres, « Au hasard » et marques des affiches marchent comme ailleurs, puisque tout passe par `libraryQuery`. « Réinitialiser » vide les choix de la barre mais garde le studio (`resetOptions`) : la grille resterait sinon titrée « A24 » en montrant toute la bibliothèque.
- **Préférences** : rien n'est enregistré (`onOptionsChanged = {}`), et `LibraryPreferenceStore` ne connaît pas le studio : la grille « Films » de l'accueil ne s'ouvre jamais filtrée sur un studio.
- **Retour** : Back depuis la grille rend la fiche avec le focus sur la pastille pressée (`DetailPage.Movie.studio`, `DetailPage.Series.studio`). Ouvrir un genre oublie la pastille de studio, et inversement ; `remembering` et `openFromCollection` oublient les deux.
- **Logique pure** dans `ui/Studios.kt` (`studioChips`, `studioOptions`, `gridTitle`, `resetOptions`, `openStudio`), testée par `StudiosTest` ; la requête par `EmbyClientTest`.

## Alternatives
- **`StudioIds`** : plus robuste face aux noms ambigus, mais il faudrait lire `Studios[].Id` en nombre, et rien ne dit, sans essai sur le serveur réel, que le filtre accepte cet identifiant tel quel. Gardé pour plus tard si le filtre par nom se montre faux.
- **Échapper `|`** : aucun échappement documenté. Rejeté.
- **Une page « studio » dédiée** (logo, toutes bibliothèques) : plus de requêtes et un nouvel écran pour un usage rare.
- **Toute la médiathèque, films et séries mêlés** : la grille ne sait montrer qu'une bibliothèque et un type ; un réseau de séries a rarement des films.
- **Garder la ligne des faits avec les pastilles** : le même nom lu deux fois à une ligne d'écart.

## Conséquences
- `ItemsOptions` gagne `studio`, compté dans `narrowed` : une grille vide affiche « Aucun titre ne correspond à ces filtres. ».
- `facts` et `Facts` prennent `studios = false` pour laisser la ligne aux pastilles ; `studioLabel` donne « Studio » ou « Diffusion ».
- `GenreChips` passe par `ChipLine`, partagé avec les studios ; `MovieScreen` et `SeriesScreen` reçoivent les deux lignes dans `ChipLines`.
- Sur une série, la ligne « Diffusion » descend la rangée d'épisodes d'une pastille (environ 44 dp) en remplaçant une ligne de faits d'environ 20 dp.
- Le choix de bibliothèque a la même limite que les genres : avec deux bibliothèques de films, la seconde n'est jamais ouverte.

## Vérification
Vérifié :
- `.\gradlew.bat -q testDebugUnitTest assembleDebug lintDebug` passe, 433 tests, sans nouvel avertissement lint dans les fichiers changés.
- `StudiosTest` : pastilles (ordre, limite de trois, doublons, noms refusés dont `|`), options de la grille, titre, « Réinitialiser », libellés, faits, retour du focus et oubli de la pastille.
- `EmbyClientTest` : `Studios=<nom>` sur les pages, les lettres et « Au hasard », jamais `StudioIds`, rien d'envoyé pour un nom avec `|`, et un élément dont `Studios[].Id` est un nombre reste lisible.
- Le paramètre `Studios` et sa séparation par `|` sont lus dans la documentation d'Emby (`getUsersByUseridItems`).

**Non vérifié sur le serveur réel** : aucune requête n'a été faite au serveur pour ce lot (pas d'identifiants de test disponibles dans l'environnement). Que le filtre `Studios` d'Emby 4.10 filtre bien par nom, accepte les accents, espaces, `&` et `+` des noms réels, et ne renvoie pas toute la bibliothèque pour un nom inconnu.

**Non vérifié sur le Shield** : le placement des pastilles « Studio » et « Diffusion » sous les genres, leur focus (Haut depuis « Lire » sur un film, Haut depuis les saisons sur une série), le titre de la grille, le retour du focus sur la pastille après Back, « Réinitialiser » qui garde le studio, et la place laissée à la rangée d'épisodes.
