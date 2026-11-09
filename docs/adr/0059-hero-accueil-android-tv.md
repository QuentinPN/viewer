# ADR 0059 — Héros de l'accueil (Android TV)

Statut : Accepté (2026-11-09). Reprend sur TV le héros de l'accueil Windows (ADR 0015), adapté à la télécommande.

## Contexte
L'accueil TV commençait directement par les rangées (« Reprendre », « À suivre », « Derniers ajouts »). Sous Windows, un héros les précède : un seul titre à la fois, sur son fond, avec « Lecture » ou « Reprendre » et « Plus d'infos », sans rotation automatique. Les flèches et les points de Windows se cliquent à la souris ; sur TV, tout passe par la croix directionnelle, et le héros doit s'insérer dans le parcours du focus de l'accueil (en-tête au-dessus, rangées en dessous) sans casser la mémoire du focus au retour d'une page ou du lecteur.

## Décision
- **Requêtes** : uniquement celles du client Windows (`src-tauri/src/emby/client.rs`), vérifiées sur Emby 4.10.1.0 par l'ADR 0015 :
  - `GET /Users/{Id}/Items/Resume` : la requête existante de « Reprendre », à laquelle on ajoute `Fields=Overview,ProductionYear,PrimaryImageAspectRatio` (`HERO_FIELDS` de Windows). Une seule requête sert la rangée et le héros ;
  - `GET /Users/{Id}/Items?Recursive=true&IncludeItemTypes=Movie,Series&IsPlayed=false&ImageTypes=Backdrop&SortBy=DateCreated&SortOrder=Descending&Limit=10` avec les mêmes `Fields` (`hero_latest`). `EnableImageTypes=Primary,Backdrop` : Windows demande aussi `Logo`, que la TV n'affiche pas ;
  - `GET /Shows/{Id}/Episodes?UserId=&IsPlayed=false&Limit=1` (`first_unplayed_episode`), une par série récente, lancées en même temps (5 au plus).
- **Règles** (`heroTitles`, `resumedHero`, `latestHero`, comme `EmbyService::hero`) : au plus 5 titres, chacun avec un fond ; d'abord ceux de « Reprendre » (un épisode représente sa série, avec le fond de la série), puis les films et séries non vus, du plus récent au plus ancien ; un film ou une série n'apparaît qu'une fois ; titres sans fond ou à identifiant invalide écartés.
- **Bouton principal** : un film se lit lui-même ; une série reprise lit l'épisode en cours ; une série récente lit son premier épisode non vu. Libellé « Reprendre à 12:34 » s'il y a une position (même format que la page film), sinon « Lecture ». Sans rien à lire (série entièrement vue, ou épisode illisible), seul « Plus d'infos » reste. La lecture passe par le `onPlay(itemId, startTicks)` existant.
- **Plus d'infos** ouvre la page du film ou de la série par les callbacks existants (`onOpenMovie`, `onOpenSeries`).
- **Affichage** : fond plein largeur de 320 dp, voile à gauche et en bas qui se fond dans les rangées, « Reprendre » en surtitre pour un titre repris, nom, ligne de faits (année · durée d'un film · classification, ou « S4 · É1 · titre » pour un épisode), résumé sur trois lignes. Image par `EmbyClient.imageUrl` et le `ImageLoader` de Coil : le jeton reste dans l'en-tête `X-Emby-Authorization` (ADR 0039), jamais dans l'URL.
- **Télécommande** : changer de titre se fait **au bord de la rangée de boutons** : Gauche sur le premier bouton montre le titre précédent, Droite sur le dernier bouton (« Plus d'infos ») le suivant, en boucle comme sous Windows. Le focus reste sur le même bouton ; si le nouveau titre n'a pas de bouton principal, il passe sur « Plus d'infos ». À droite des boutons, des points et « 2 sur 5 » encadrés de ◀ ▶ disent où l'on est ; ils ne prennent pas le focus. **Aucun changement automatique.**
- **Parcours du focus** : le héros est le premier élément de la colonne de l'accueil. Bas mène à la première rangée, Haut depuis celle-ci revient au héros, Haut depuis le héros mène à l'en-tête (Rechercher, Réglages, Déconnecter), par la recherche de focus géométrique de Compose. À l'ouverture, le focus va sur le bouton principal du héros (sur la première carte s'il n'y a pas de héros).
- **Mémoire** : `HomeMemory` garde le titre affiché (`heroId`, `heroIndex`) et, dans `opened`, le bouton utilisé. Au retour d'une page ou du lecteur, le focus revient sur ce bouton. Après un rafraîchissement (fin de lecture), le titre affiché est gardé s'il est encore là, sinon la même place (`keptIndex`).
- **Mouvement** : fondu de 200 ms entre deux titres, sur le fond et le texte seulement (les boutons ne sont pas recréés, pour garder le focus). Il est supprimé quand l'échelle d'animation du système (`Settings.Global.ANIMATOR_DURATION_SCALE`) vaut 0, faute d'autre notion de « mouvement réduit » sur TV.
- **États** : le héros est chargé avec le reste de l'accueil ; **caché s'il est vide**. Un échec le cache sans toucher aux rangées ; une session expirée (401) n'est pas masquée et mène à la reconnexion.

## Alternatives
- **Contrôles ◀ ▶ focalisables de part et d'autre des boutons** : plus explicites, mais deux arrêts de focus de plus à chaque passage, et un contrôle « précédent » à gauche de « Lecture » prendrait le focus par défaut de la rangée. Écarté au profit du bord de rangée, signalé par les points et ◀ ▶.
- **Gauche/Droite sur n'importe quel bouton** : changerait de titre en voulant aller de « Lecture » à « Plus d'infos ». Écarté.
- **Rotation automatique** : écartée comme sous Windows (choix de l'utilisateur, ADR 0015).
- **Logo du titre à la place du nom** : Windows l'affiche ; demande deux champs de plus (`ParentLogoItemId`, `ParentLogoImageTag`) et un repli si l'image échoue. Reporté : le nom en texte reste lisible à 3 m.
- **Une requête « Reprendre » propre au héros, comme Windows** : une requête de plus pour les mêmes éléments. Écarté : la requête de la rangée porte désormais les champs du héros.
- **Erreur avec « Réessayer » dans le héros** : prendrait le focus de la télécommande ; écarté comme pour « À suivre » (ADR 0056).

## Conséquences
- Une requête de plus à l'accueil (les derniers ajouts non vus), plus une par série récente du héros, en parallèle. Le chargement de l'accueil attend le héros.
- La réponse de `/Items/Resume` est un peu plus lourde (résumé et année de chaque élément).
- `SortBy=DateCreated` date l'ajout de la série, pas celui de son dernier épisode (même limite que Windows, ADR 0015).
- Le héros occupe le haut de l'écran : au chargement, seul le haut de la première rangée est visible ; Bas y descend.
- Le changement de titre au bord ne se voit pas tant qu'on ne l'a pas essayé ; les ◀ ▶ près des points sont le seul indice.

## Vérification
- Tests unitaires `HeroTest` (règles : épisode repris représentant sa série, film repris, titres sans fond ou à id invalide écartés, série récente en attente de son épisode, ordre « Reprendre » puis récents, dédoublonnage, limite de 5, héros vide ; serveur simulé : requêtes exactes de Windows, une requête d'épisode par série et aucune par film, aucun `api_key` dans les URL, épisode illisible, session expirée remontée), `HeroLabelsTest` (pas en boucle, titre gardé après rafraîchissement, libellés « Lecture » / « Reprendre à … », ligne de faits) et `EmbyClientTest` (`Fields` de `/Items/Resume`). `testDebugUnitTest` (176 tests), `assembleDebug` et `lintDebug` passent, sans avertissement dans les fichiers modifiés.
- Non vérifié sur le Shield ni sur le serveur réel : l'affichage du héros et de ses fonds, le parcours du focus (Bas, Haut, en-tête), le changement de titre au bord, la reprise du focus après une page ou le lecteur, le fondu et sa suppression, la lecture et la reprise depuis le héros, et le temps de chargement de l'accueil.
