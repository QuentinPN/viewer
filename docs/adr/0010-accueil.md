# ADR 0010 — Page d'accueil

Statut : Accepté (2026-10-28).

## Contexte
`DESIGN.md` décrit l'accueil ainsi : un hero cinématographique, puis « Reprendre », « À suivre », « Ajoutés récemment » et « Favoris ». Jusqu'ici, l'application ouvrait directement la grille de la première bibliothèque. La reprise n'était donc accessible qu'en retrouvant l'élément dans sa bibliothèque.

Ce qui a été observé sur Emby 4.10.1.0 (QNAP), en lecture seule, avec le compte de test, le 2026-10-28 :
- `GET /Users/{UserId}/Items/Resume` renvoie un `QueryResult`. Les éléments sont triés du plus récent au plus ancien, et chacun a son `UserData.PlaybackPositionTicks`. Temps mesuré : 242 ms.
- `GET /Shows/NextUp?UserId=` renvoie un `QueryResult` d'épisodes (3 ms). La liste est vide sur ce compte, qui n'a aucun épisode vu.
- `GET /Users/{UserId}/Items/Latest?ParentId=` renvoie un **tableau nu**, pas un `QueryResult`. Les épisodes y sont regroupés par série : chaque série n'apparaît qu'une fois, avec son nombre d'épisodes non vus. Un `ParentId` inconnu donne une erreur **500**, pas une 404. Temps mesuré : 5 à 63 ms à chaud, 2,3 s au premier appel.
- Les épisodes ont une image `Primary` au format 16:9. Les films n'en ont souvent qu'une `Thumb` ou une `Backdrop`. Quand l'épisode n'a pas d'image, `ParentThumbItemId` et `ParentBackdropItemId` désignent celles de la série.

## Décision
- Une fois connecté, l'accueil est la vue par défaut. Une entrée « Accueil » est ajoutée en tête de la barre latérale ; une bibliothèque n'est plus sélectionnée automatiquement.
- Trois commandes Rust, sans paramètre fourni par l'UI hormis l'identifiant de bibliothèque : `emby_continue_watching`, `emby_next_up` et `emby_latest(library_id)`. Chaque rangée contient au plus 16 éléments.
- « Reprendre » et « À suivre » utilisent des cartes 16:9 (`WatchItem`).
  - Activer une carte **lance la lecture tout de suite**, depuis le point de reprise s'il y en a un. Cela passe par la négociation habituelle (`emby_play`, ADR 0007). L'intention, c'est « continuer », et la fiche ajouterait un clic.
  - Choix de l'image : pour un épisode, son image `Primary`, sinon la `Thumb` de la série, sinon sa `Backdrop`. Pour un film, sa `Thumb`, sinon sa `Backdrop`.
  - Un épisode déjà présent dans « Reprendre » est retiré de « À suivre ».
  - Les deux rangées sont relues après chaque arrêt de lecture (`emby://playback-stopped`). Une réponse arrivée après une relecture plus récente est ignorée.
- « Ajoutés récemment · {bibliothèque} » affiche une rangée d'affiches par bibliothèque.
  - Ces cartes ouvrent la fiche, comme dans la grille.
  - « Tout voir » ouvre la grille de la bibliothèque.
  - Avant de passer le `ParentId` au serveur, Rust vérifie qu'il appartient bien à la liste des bibliothèques. Un identifiant inconnu donne ainsi `notFound`, et non une erreur 500. Seuls les types de la bibliothèque sont gardés : `Movie` ou `Series`.
- Les rangées se chargent en parallèle, et chacune a son propre état : chargement (squelette), erreur avec « Réessayer », ou vide. Une rangée vide est masquée. Si tout est vide, un état explicite s'affiche.
- Navigation dans une rangée :
  - les flèches gauche/droite du clavier passent d'une carte à l'autre, Tab sort de la rangée ;
  - des boutons « Faire défiler… », toujours visibles, défilent d'environ une largeur de fenêtre ;
  - la barre de défilement native est masquée ;
  - le défilement est instantané quand l'utilisateur préfère réduire les animations.
- **Pas de hero ni de « Favoris » dans cette tranche** : ils seront décidés plus tard, avec des données réelles.

## Révision (2026-10-28) : source de « À suivre »
Observé avec le compte de test, après avoir marqué comme vu le premier épisode d'une série (autorisé, puis annulé) :
- `GET /Shows/NextUp?UserId=` reste **vide**, avec ou sans `Limit`, `Fields` ou `ParentId`, et même 20 s plus tard. C'est pareil quand l'épisode est marqué vu par un rapport d'arrêt de lecture plutôt que par `PlayedItems`.
- Avec `SeriesId`, le même appel renvoie bien l'épisode 2, puis tous les épisodes non vus de la série (203).
- `GET /Users/{UserId}/HomeSections` renvoie un tableau nu de sections. La section `resume` porte `IncludeNextUpInResume: true`, et `GET /Users/{UserId}/Sections/resume/Items` contient l'épisode 2. C'est par là que l'accueil web d'Emby 4.10 obtient ses épisodes suivants.

Décision :
- `emby_next_up` lit **en parallèle** `/Shows/NextUp` et la section `resume`, à condition qu'elle soit déclarée avec `IncludeNextUpInResume`.
- Les éléments de `/Shows/NextUp` viennent d'abord, puis ceux que seule la section donne. On ne garde que les épisodes non vus et non commencés : les éléments en cours restent dans « Reprendre ».
- Un serveur sans sections d'accueil (404) garde le comportement d'origine.
- Mesure : 99 ms pour la rangée, contre 1,9 s quand les appels étaient faits l'un après l'autre à froid.

Écarté : appeler `/Shows/NextUp?SeriesId=` pour chaque série récemment lue. Cela demanderait de deviner ces séries, et ferait un appel par série.

## Alternatives
- **Une carte « Reprendre » qui ouvre la fiche** : cohérent avec les affiches, mais cela ajoute un clic à l'action la plus fréquente. Écarté. La fiche reste accessible depuis la bibliothèque.
- **`/Items?SortBy=DateCreated` au lieu de `/Items/Latest`** : renverrait chaque épisode séparément et inonderait la rangée d'une même série. Écarté.
- **Une seule commande pour tout l'accueil** : moins d'appels IPC, mais une rangée lente ou en erreur bloquerait toutes les autres. Écarté.
- **Cache local des rangées** : il reviendra avec le cache SQLite. Ces rangées dépendent de l'état de lecture, dont Emby reste la source de vérité.

## Conséquences
- Si la carte jouée quitte « Reprendre » après la lecture (lue jusqu'au bout ou remise à zéro), le focus clavier revient au début du document.
- Pendant le chargement, le squelette porte le titre « Reprendre », même quand la rangée se révèle vide ensuite.
- Le hero, les « Favoris », la recherche, le tri et les filtres restent à faire (Gate 3).
