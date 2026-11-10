# ADR 0092 — Toutes les bibliothèques dans la barre d'accueil (Android TV)

Statut : Accepté (2026-11-10). Modifie la barre de l'ADR 0064.

## Contexte
La barre d'accueil n'avait que deux entrées, « Films » et « Séries ». Chacune ouvrait une seule bibliothèque de son type : celle qui porte ce nom, sinon la première. Sur le serveur du propriétaire, il y a quatre bibliothèques (Anime, Films, Films Anime, Séries TV). « Séries » ouvrait donc « Anime », et « Films Anime » et « Séries TV » n'étaient accessibles depuis aucune entrée. La version bureau, elle, liste toutes les bibliothèques de films et de séries.

## Décision
- La barre montre une entrée par bibliothèque de films ou de séries, sous son nom et dans l'ordre du serveur, avec le même filtre que la version bureau (`CollectionType` `movies` ou `tvshows`). Le tri se fait dans `barLibraries`.
- La bibliothèque ouverte en dernier reprend le focus au retour (`HomeMemory.focusLibrary`).
- Si les entrées dépassent la largeur de l'écran, la barre défile horizontalement.
- Un genre ou un studio d'une fiche ouvre toujours la bibliothèque choisie par `sectionLibrary` (ADR 0079 et 0087).

## Alternatives
- **Un menu « Bibliothèques »** : un appui de plus pour chaque bibliothèque, sans gain avec quatre bibliothèques. Rejeté.
- **Garder « Films » et « Séries » en ajoutant les autres** : les mêmes bibliothèques seraient affichées deux fois, sous deux noms différents. Rejeté.

## Conséquences
- Toutes les bibliothèques de films et de séries s'ouvrent depuis l'accueil. Les autres types (musique, photos) restent absents, comme sur la version bureau.
- Un genre de série ouvre toujours une seule des bibliothèques de séries.

## Vérification
- `HomeBarTest` : la barre liste chaque bibliothèque de films et de séries, dans l'ordre, sans doublon ni autre type. `testDebugUnitTest` (460 tests, 0 échec), `assembleDebug` et `lintDebug` passent.
- Sur la Shield, la barre affiche Accueil, Anime, Films, Films Anime, Séries TV, la recherche, les réglages et l'avatar. « Films Anime » ouvre la grille « Films Anime · 15 titres », et Retour rend le focus à « Films Anime ».
