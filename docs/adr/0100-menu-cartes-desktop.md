# ADR 0100 — Menu des cartes et ajout à une playlist (desktop)

Statut : Accepté (2026-11-11).

## Contexte
Les cartes de l'accueil et des grilles ne proposaient qu'une action : lire ou ouvrir. Les actions secondaires (vu, favori, retirer de « Reprendre ») étaient éparpillées : sur la fiche, et deux petits boutons ✓/× sur les seules cartes « Reprendre » (ADR 0096). Les applications Emby officielles offrent, sur chaque carte, un menu ancré sur la carte : un panneau sombre translucide, coiffé de l'affiche, du titre et de l'année, avec une ligne par action, libellé à gauche et icône à droite. Elles permettent aussi d'ajouter un titre à une playlist, ce que l'app ne savait pas faire.

Contrats Emby utilisés, vérifiés dans la documentation de l'API :
- liste : `GET /Users/{UserId}/Items?IncludeItemTypes=Playlist&Recursive=true` (tri par nom) ;
- ajout : `POST /Playlists/{Id}/Items?Ids={ItemId}&UserId={UserId}`, qui répond un `AddToPlaylistResult` (`ItemAddedCount`) ;
- création : `POST /Playlists?Name={Nom}&Ids={ItemId}&MediaType=Video&UserId={UserId}`, qui répond un `PlaylistCreationResult` (`Id`, `Name`, `ItemAddedCount`).

Le prochain épisode d'une série reprend la recherche déjà utilisée par le héros de l'accueil (premier épisode non vu). Aucun autre endpoint n'est utilisé.

## Décision
- **Ouverture** : clic droit sur une carte, touche Menu ou Maj+F10 sur une carte qui a le focus, ou bouton « … » en haut à droite de la carte. Ce bouton apparaît au survol et au focus clavier (`:focus-within`) et prend la place du badge « vu » ou du compteur pendant ce temps. Le clavier et le bouton ancrent le menu sur le « … » ; le clic droit l'ancre sur le pointeur.
- **Actions d'un film ou d'un épisode** : « Lire » ou « Reprendre », « Ajouter à une playlist », « Marquer comme vu » ou « non vu », « Ajouter aux favoris » ou « Retirer des favoris », puis « Retirer de Reprendre » en dernier sur les cartes « Reprendre ».
- **Actions d'une série** : « Lire le prochain épisode », playlist, vu, favori. « Tout lire » et « Aléatoire » des apps Emby sont omis : l'app n'a pas de file de lecture, ces actions ne liraient qu'un épisode.
- **Remplace les boutons ✓/× de l'ADR 0096** : « Reprendre » passe par le même menu. « Marquer comme vu » et « Retirer de Reprendre » y gardent le comportement de l'ADR 0096 : la carte part tout de suite, le focus passe à la voisine, et elle revient en cas d'échec.
- **Clavier et accessibilité** : `role="menu"` et `menuitem`. Les flèches haut et bas parcourent les lignes en boucle, Début et Fin vont aux extrémités, Entrée active. Échap ferme le menu et rend le focus à la carte, Tab aussi. Dans la vue des playlists, flèche gauche revient aux actions ; flèche droite sur « Ajouter à une playlist » l'ouvre. Le focus est visible, et le mouvement d'apparition est coupé si l'utilisateur réduit les animations.
- **Fermeture** : un clic en dehors, un défilement en dehors, un redimensionnement de la fenêtre, ou une action exécutée.
- **Placement** : sous l'ancre et aligné à gauche. Le menu passe au-dessus s'il manque de place, s'aligne à droite près du bord, et reste toujours dans la fenêtre, sous la barre de titre (`placeMenu`, fonction pure testée). Il est mis dans `document.body` pour échapper aux rangées qui défilent.
- **Playlists** : « Ajouter à une playlist » remplace les lignes par une vue avec « Retour », « Nouvelle playlist… », puis les playlists de l'utilisateur, avec des états chargement, erreur (« Réessayer ») et liste vide. « Nouvelle playlist… » ouvre un champ prérempli avec le nom du titre et un bouton « Créer ». Le nom est nettoyé et validé des deux côtés : non vide, 200 caractères au plus, sans caractère de contrôle. Un succès ferme le menu et affiche une courte confirmation. Un échec garde le menu ouvert, le nom tapé et un message en français. Si le serveur répond `ItemAddedCount = 0`, le message dit que le titre y est peut-être déjà.
- **État affiché** : les cartes ne sont pas modifiées (props Svelte). Le menu garde deux tables, l'une des favoris changés, l'autre des états « vu » reçus par `emby://playback-stopped`. Il les applique à la carte à l'ouverture, pour que les libellés suivent une action faite ailleurs.
- **Une instance par écran** : `LibraryBrowser` crée l'état et le passe aux cartes par le contexte Svelte ; `PosterCard` et `WatchCard` n'affichent aucun menu hors de ce contexte.
- **Sécurité** : nouvelles commandes `emby_playlists`, `emby_add_to_playlist`, `emby_create_playlist`, `emby_series_next_episode`. Les ids sont vérifiés avant tout envoi. Le jeton reste dans l'en-tête, jamais dans l'URL ni dans les journaux. Un 401 ferme la session.

## Alternatives
- **Garder les boutons ✓/× et n'ajouter qu'un menu pour les playlists** : deux modèles d'action sur la même carte. Rejeté.
- **Un menu natif (menu contextuel Tauri)** : il ne permet ni l'en-tête avec affiche, ni le champ de création, ni le style de l'app. Rejeté.
- **« Tout lire » / « Aléatoire » sur une série** : sans file de lecture, ce serait une promesse non tenue. Reporté avec la file de lecture.
- **Recopier l'état dans les objets des cartes** : avec Svelte 5, muter des props provoque l'avertissement `ownership_invalid_mutation`. Rejeté au profit des tables de l'état du menu.

## Conséquences
- Le menu n'existe que dans l'écran de bibliothèque (accueil, grilles, recherche), pas dans la fiche, qui a ses propres boutons.
- Une playlist créée ou complétée n'est visible nulle part dans l'app pour l'instant : il n'y a pas encore d'écran des playlists.
- L'ancienne action rapide des cartes « Reprendre » demande maintenant un clic de plus.

## Non vérifié
Rien n'a été essayé sur le vrai serveur ni dans l'app lancée. En particulier :
- le comportement réel d'Emby à l'ajout d'un doublon, et ce que signifie `ItemAddedCount = 0` ;
- que `MediaType=Video` convienne à une playlist qui mélange films et épisodes ;
- l'ordre des événements `keydown` et `contextmenu` de la touche Menu dans WebView2 (le code tolère les deux ordres) ;
- le rendu, le placement près des bords et le parcours au clavier : seuls svelte-check, Biome, Vitest et le build sont passés.

## Vérification
- Tests Rust sur serveur simulé : méthode, chemin, requête et en-tête de jeton pour la liste, l'ajout et la création ; aucun jeton dans l'URL ; noms et ids invalides jamais envoyés ; entrées inutilisables écartées ; fin de session sur 401 ; prochain épisode d'une série.
- Vitest : actions selon le type et le contexte, cible construite depuis une affiche ou une carte, touches d'ouverture, validation du nom, messages, placement (bords, retournement, barre de titre).
