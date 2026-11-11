# ADR 0098 — Menu des cartes à la manière d'Emby, et « Ajouter à une playlist » (Android TV)

Statut : Accepté (2026-11-11). Remplace le menu de carte de l'ADR 0097 (une `Popup` centrée avec une colonne de boutons). Les écritures de l'ADR 0097 (vu, favori, « Reprendre ») et la mise à jour des rangées restent les mêmes. S'appuie sur les listes de lecture de l'ADR 0088.

## Contexte
Le propriétaire veut que le menu ouvert par un appui long sur une carte ressemble à celui de l'application officielle Emby pour Android TV. Il veut aussi pouvoir ajouter un titre à une playlist depuis l'accueil. Ce qu'on a observé chez Emby : un panneau sombre et translucide posé sur la carte, avec une petite affiche, le titre et l'année en en-tête. Viennent ensuite des lignes pleine largeur, libellé à gauche et petite icône à droite. La ligne qui a le focus devient une pastille blanche au texte sombre. Aucun élément graphique d'Emby n'est repris.

## Décision
- **Modèle** (`ui/CardMenu.kt`) : `menuActions(item, MenuContext)` est une fonction pure qui donne les lignes dans leur ordre :
  - Film, épisode : « Lire », ou « Reprendre » s'il y a un point de reprise. On prend `resumeTicks`, comme le héros : pas de reprise pour un titre vu, la même règle que les fiches. Puis « Ajouter à une playlist », « Marquer comme vu / non vu », « Ajouter aux / Retirer des favoris ». « Retirer de Reprendre » vient en dernier, seulement sur cette rangée.
  - Série : « Lire le prochain épisode », puis playlist, vu et favori. L'épisode lu est celui du bouton principal du héros : `firstUnplayedEpisode` (le premier épisode non vu, dans l'ordre de la série), repris à son point de reprise s'il est commencé. La fiche série n'a pas de bouton de lecture propre. S'il n'y a plus d'épisode, le panneau reste ouvert avec « Aucun épisode à voir dans cette série. ».
  - Saison : playlist, vu, favori, sans ligne de lecture. Choisir l'épisode d'une saison demanderait une requête de plus.
  - Liste de lecture, dossier : pas de menu, comme avant.
- **Pas de « Tout lire » ni d'« Aléatoire »** : le lecteur lit un seul titre et n'a pas de file d'attente (ADR 0088 : la page d'une liste de lecture n'a pas de « Lire » pour la même raison). Ces lignes viendront avec une file.
- **Lecture** : « Lire » et « Reprendre » appellent le même `onPlay(itemId, startTicks)` que le clic sur une carte, le héros et les fiches. Le menu ne construit aucune URL et ne choisit aucune source : la négociation reste celle du lecteur.
- **Panneau** : une `Popup` focusable de 296 dp, sur un verre sombre (`#EB1C1C1E`) aux coins arrondis. Elle est ancrée sur la `Box` de la carte par un `PopupPositionProvider`. `menuOffset` (fonction pure) centre le panneau sur la carte, son haut sur celui de la carte, puis le déplace juste assez pour rester à 24 dp des bords de la fenêtre. La première carte d'une rangée a donc son panneau au bord gauche, et une carte basse a le sien remonté.
- **En-tête** : la petite image `Primary` de l'élément, chargée par le chargeur d'images existant (jeton dans l'en-tête). C'est une affiche 2:3, ou la vignette 16:9 d'un épisode. À côté, le titre (la série pour un épisode) sur deux lignes au plus, puis l'année ou « S1 · É3 — nom » en couleur secondaire.
- **Lignes** : `Surface` de `tv-material`. Transparente au repos, blanche au texte noir avec le focus, sans agrandissement. Les icônes (lecture, ajout à une liste, coche, cœur, corbeille) sont des `ImageVector` dessinés dans le fichier, au trait, sur une grille de 24, comme celles de la barre d'accueil. Le projet ne dépend pas de `material-icons` : aucune dépendance n'est ajoutée.
- **Ajouter à une playlist** : la ligne remplace le contenu du panneau par « Nouvelle playlist… » puis les playlists vidéo de l'utilisateur, A → Z. On les lit avec la requête de la rangée « Listes de lecture » (`playlists` + `videoPlaylists`, ADR 0088), à chaque ouverture de la page. « Nouvelle playlist… » affiche un champ prérempli avec le nom du titre (la série pour un épisode), puis « Créer ». La touche OK du clavier TV descend sur « Créer ». Un nom vide donne « Saisissez un nom. ». Un nom trop long est coupé à 100 caractères.
  - Ajout : `POST /Playlists/{Id}/Items?Ids={itemId}&UserId={userId}`, sans corps, lu dans `AddToPlaylistResult`.
  - Création : `POST /Playlists?Name=&Ids=&MediaType=Video&UserId=`, sans corps, lu dans `PlaylistCreationResult`. `Id` est obligatoire dans la réponse. `UserId` est toujours envoyé, car le serveur en a besoin en pratique.
  - Le jeton reste dans `X-Emby-Authorization`, jamais dans l'URL. Les identifiants passent par `segment` : une virgule, un `&` ou un `/` sont refusés avant tout envoi. Le nom est un paramètre encodé par OkHttp. Ce n'est pas un secret.
  - Une série s'ajoute par son propre identifiant, comme le fait Emby : le serveur ajoute ses épisodes. L'application ne les énumère pas. `ItemAddedCount` est lu mais n'est pas affiché.
  - Succès : un `Toast` court « Ajouté à <nom> », le panneau se ferme et l'accueil relit ses rangées sur place (`userDataChanged`). Une nouvelle liste apparaît alors dans « Listes de lecture », et le focus reste sur la carte. Échec : « Ajout impossible. » suivi de la cause, et le panneau reste ouvert sur la même page.
- **Navigation** : Retour remonte d'une page (création → playlists → actions) et ferme le panneau depuis les actions (`MenuPage.previous`, via `onDismissRequest`). Chaque page met le focus sur sa première ligne. En revenant des playlists, le focus va sur « Ajouter à une playlist ». À la fermeture, le focus revient sur la carte (un `FocusRequester` par carte). Si la carte a quitté sa rangée, c'est la cible de `focusAfter` qui prend le relais (ADR 0097).
- **Réutilisation** : `CardMenu(item, menuActions(item, contexte), client, userId, images, onDismiss, onPlay, onSessionExpired, onPlaylistChanged, perform)` s'ouvre d'un seul appel dans une `Box` autour de n'importe quelle carte. La grille des bibliothèques pourra l'utiliser sans rien changer ici. `perform` laisse chaque écran mettre à jour sa propre vue après une écriture. Une session expirée renvoie à la connexion, quelle que soit la requête du menu.

## Alternatives
- **Garder la colonne de boutons centrée** : elle fonctionnait, mais ne ressemblait pas au menu Emby demandé et cachait la carte d'où il venait. Rejeté.
- **Dépendre de `material-icons-extended`** : une grosse bibliothèque pour cinq icônes. Rejeté, les icônes sont dessinées sur place.
- **« Tout lire » / « Aléatoire » en lisant le premier épisode** : ce serait mentir sur ce que fait le bouton. Rejeté jusqu'à ce qu'il y ait une file d'attente.
- **Ajouter un par un les épisodes d'une série** : beaucoup de requêtes pour ce qu'Emby fait en une seule. Rejeté.
- **Confirmation dans l'accueil plutôt qu'un `Toast`** : la ligne de notice de l'accueil est souvent hors de l'écran. Un `Toast` se voit partout et sert aussi à la grille. Retenu : le `Toast`.

## Conséquences
- Deuxième écriture de l'app TV sur le serveur : les playlists créées ou complétées le sont pour le compte, sur tous ses appareils.
- Une playlist audio n'est pas proposée (`videoPlaylists`). Un même titre peut être ajouté deux fois, comme dans Emby Web.
- Le menu ne propose rien sur une liste de lecture. Retirer une entrée d'une liste reste à faire.

## Vérification
- `CardMenuTest` (15 tests) : lignes et ordre par type et par rangée (film, épisode, série, saison, liste de lecture, dossier) ; « Reprendre » avec la position, « Lire » pour un titre vu ; marques inverses de l'état ; icônes ; prochain épisode repris à sa position, aucun sinon ; pages et Retour ; nom de playlist nettoyé, coupé et prérempli ; en-tête ; messages en français ; position du panneau au milieu, au bord gauche, au bord droit, remonté, et plus grand que la fenêtre.
- `EmbyClientTest` (4 nouveaux tests) : ajout et création (méthode, chemin, paramètres et leur ordre, nom avec `&` encodé en un seul paramètre, pas de corps, jeton dans l'en-tête et jamais dans l'URL) ; session requise ; identifiants refusés avant tout envoi ; erreurs 401, 403, 404, 500, JSON invalide et réponse de création sans `Id`.
- `ItemActionsTest` : les trois tests de `cardActions`, remplacée par `menuActions`, sont retirés ou fondus dans `CardMenuTest`.
- `testDebugUnitTest` (511 tests, 0 échec), `assembleDebug`, `lintDebug`, `assembleRelease` et `lintRelease` passent.
- **Non vérifié** (ni appareil ni serveur réel dans cette tranche) : le rendu et la position du panneau sur la Shield ; le clavier TV dans la `Popup` ; Retour d'une page à l'autre (et pendant que le clavier est ouvert) ; le défilement au D-pad d'une longue liste de playlists ; le `Toast` ; le retour du focus sur la carte ; les réponses réelles d'Emby 4.9/4.10 à `POST /Playlists` et `POST /Playlists/{Id}/Items` (dont l'ajout d'une série et le besoin de `UserId`).
