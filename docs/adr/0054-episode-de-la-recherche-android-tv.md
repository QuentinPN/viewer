# ADR 0054 — Épisode trouvé par la recherche (Android TV)

Statut : Accepté (2026-11-09). Complète la recherche TV et l'ADR 0049 ; reprend le retour d'une fiche d'épisode de Windows (`EpisodeList.reveal`, ADR 0016).

## Contexte
Sur TV, un épisode choisi dans les résultats de recherche ouvrait la page de sa série sur la saison par défaut (ADR 0049) : il fallait ensuite chercher la bonne saison, puis l'épisode. Il n'y a pas encore de fiche d'épisode sur TV.

## Décision
- **Cible** : la page série reçoit un `SeriesTarget` (série, et pour un épisode : `SeasonId`, `ParentIndexNumber`, id de l'épisode) au lieu du seul id de série. L'accueil et la bibliothèque passent la série seule : rien ne change pour eux. Les cartes d'épisode de l'accueil lancent toujours la lecture.
- **Saison ouverte** (`openingSeason`) : la saison dont l'id est le `SeasonId` de l'épisode, sinon celle dont le numéro est son `ParentIndexNumber`, sinon la règle de l'ADR 0049. Un épisode spécial diffusé pendant une saison porte le `SeasonId` des spéciaux (gate 2) : il ouvre donc les « Épisodes spéciaux ».
- **Focus** (`initialEpisodeIndex`) : la carte de l'épisode cherché, sinon, comme avant, le premier épisode non vu. La rangée démarre défilée jusqu'à cette carte. Choisir une autre saison oublie l'épisode cherché.
- **Modèle** : `BaseItemDto` lit `SeasonId`, champ vérifié sur Emby 4.10.1.0 pour un épisode (ADR 0016, fixtures de `src-tauri/src/emby/tests.rs`). La requête de recherche ne change pas.
- **Retour** : Retour depuis la série revient aux résultats, le focus sur la carte de l'épisode, comme avant (`SearchMemory.opened`).

## Alternatives
- **Fiche d'épisode, comme sous Windows** : plus complète, mais c'est un écran entier. Prochain lot possible.
- **Ajouter un champ à `Fields` pour obtenir `SeasonId`** : aucun champ de `Fields` connu ne le contrôle ; le numéro de saison sert de repli à la place.

## Conséquences
- Aucune requête nouvelle, aucun texte nouveau.
- Si l'épisode n'est pas dans la saison chargée, ou si la saison n'existe plus, la page s'ouvre comme avant, sans message.

## Vérification
- Tests unitaires `SeriesTargetTest` : lecture de `SeasonId` sur la forme Windows, choix de la saison (id, numéro, repli), choix de la carte. `testDebugUnitTest`, `assembleDebug` et `lintDebug` passent.
- Non vérifié : que `/Users/{Id}/Items?SearchTerm=…&IncludeItemTypes=Episode` renvoie `SeasonId` sur le serveur réel (vérifié seulement pour `/Users/{Id}/Items/{Id}`) ; sans lui, le repli par `ParentIndexNumber` s'applique. Non vérifié non plus sur le Shield : l'ouverture sur la bonne saison, le focus sur la carte et le retour aux résultats.
