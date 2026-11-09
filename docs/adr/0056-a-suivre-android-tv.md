# ADR 0056 — Rangée « À suivre » (Android TV)

Statut : Accepté (2026-11-09). Reprend sur TV la rangée « À suivre » de l'accueil Windows (ADR 0010 et sa révision).

## Contexte
L'accueil TV montrait « Reprendre » puis « Derniers ajouts · {bibliothèque} ». Il manquait la rangée « À suivre » de Windows : l'épisode suivant de chaque série en cours. Sous Windows, cette rangée vient juste après « Reprendre », avant les favoris et les derniers ajouts.

Sous Windows (`src-tauri/src/emby/session.rs`, `next_up`), la rangée ne vient pas que de `/Shows/NextUp` : sur Emby 4.10.1.0, cet appel reste vide une fois un épisode vu, et l'épisode suivant n'apparaît que dans la section d'accueil `resume` déclarée avec `IncludeNextUpInResume` (observé le 2026-10-28, révision de l'ADR 0010).

## Décision
- **Requêtes** : celles de Windows (`src-tauri/src/emby/client.rs`), lues en parallèle :
  - `GET /Shows/NextUp?UserId=&Limit=16&Fields=PrimaryImageAspectRatio&EnableUserData=true&ImageTypeLimit=1&EnableImageTypes=Primary,Backdrop,Thumb` (`EmbyClient::next_up`) ;
  - `GET /Users/{Id}/HomeSections` (tableau nu) ; si une section a `SectionType: "resume"` et `IncludeNextUpInResume: true`, `GET /Users/{Id}/Sections/{Id}/Items` avec les mêmes `Limit`, `Fields`, `EnableUserData`, `ImageTypeLimit` et `EnableImageTypes`. Un serveur sans sections d'accueil (404) garde le seul `/Shows/NextUp`.
- **Règles** (`mergeNextUp`, comme `EmbyService::next_up`) : les éléments de `/Shows/NextUp` d'abord, puis ceux que seule la section donne ; seulement des épisodes ni vus ni commencés ; 16 au plus (`HOME_ROW`). Un épisode déjà dans « Reprendre » est retiré (`withoutDuplicates`, comme `format.ts`). Rangée vide : masquée.
- **Place et titre** : « À suivre », juste après « Reprendre », avant « Derniers ajouts ».
- **Cartes** : celles de « Reprendre » (16:9, nom de la série, « S1 · É6 — titre »). Une carte lance la lecture depuis le début (`onPlay(id, 0)`), comme Windows (`emby_play` sans reprise, l'épisode n'étant pas commencé).
- **Rafraîchissement** : la rangée est relue avec le reste de l'accueil après chaque lecture (`refresh`). La mémoire de l'accueil (`HomeMemory`) la retrouve par son titre : Retour depuis une page ou le lecteur remet le focus sur la même carte si elle est encore là, sinon sur la première de l'accueil.
- **Erreurs** : un échec de la rangée la masque sans toucher au reste de l'accueil. Une session expirée (401) n'est pas masquée : elle mène à la reconnexion, comme pour les autres rangées.

## Alternatives
- **`/Shows/NextUp` seul** : plus simple, mais la rangée resterait vide sur le serveur réel dès qu'un épisode est vu. Écarté, comme sous Windows.
- **Une erreur avec « Réessayer » dans la rangée, comme Windows** : l'accueil TV n'a pas d'état par rangée ; une erreur affichée prendrait le focus de la télécommande. Écarté pour l'instant : la rangée réapparaît au prochain rafraîchissement.
- **Ouvrir la page de la série** : ajoute un clic à l'action la plus fréquente, et Windows lance la lecture. Écarté.

## Conséquences
- Deux à trois requêtes de plus par chargement de l'accueil, en parallèle de « Reprendre ».
- Un épisode lancé depuis « À suivre » puis arrêté en cours passe dans « Reprendre » : au retour, sa carte a quitté « À suivre » et le focus va à la première carte de l'accueil (même conséquence que sous Windows, ADR 0010).
- Une panne passagère de la rangée la fait disparaître jusqu'au rafraîchissement suivant, sans message.

## Vérification
- Tests unitaires `NextUpTest` (serveur simulé, fixtures Windows de `src-tauri/src/emby/tests.rs`) : requête `/Shows/NextUp` exacte, lecture de `HomeSections`, fusion avec la section `resume`, serveur sans sections, session expirée remontée, filtrage (vus, commencés, films, ids invalides, limite) et retrait des doublons de « Reprendre ». `testDebugUnitTest` (144 tests), `assembleDebug` et `lintDebug` passent, sans avertissement dans les fichiers modifiés.
- Non vérifié sur le Shield ni sur le serveur réel : l'affichage de la rangée et de ses images, la lecture depuis le début, le départ de la carte une fois l'épisode vu, le retour du focus sur la carte, et le temps de chargement de l'accueil avec ces requêtes en plus.
