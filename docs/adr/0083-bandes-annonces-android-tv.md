# ADR 0083 — Bandes-annonces sur les fiches film et série (Android TV)

Statut : Accepté (2026-11-10). S'appuie sur la négociation PlaybackInfo (ADR 0007, 0058), les rapports de lecture (ADR 0008, 0040) et les fiches des ADR 0050 et 0060.

## Contexte
Les fiches film et série TV n'offrent aucun moyen de voir la bande-annonce d'un titre, alors qu'Emby en connaît souvent une : un fichier rangé à côté du film sur le serveur, ou une adresse sur le web récupérée par les métadonnées.

Faits établis dans la documentation de l'API Emby (dev.emby.media, consultée le 2026-11-10) :
- `BaseItemDto.RemoteTrailers` : `MediaUrl[]`, « The trailer urls ». `MediaUrl` a deux champs texte, `Url` et `Name`. En pratique ce sont des pages YouTube.
- `BaseItemDto.LocalTrailerCount` : entier, « The local trailer count ».
- `GET /Users/{UserId}/Items/{Id}/LocalTrailers` : liste les bandes-annonces locales, réponse `BaseItemDto[]` (tableau nu, pas de `QueryResult`). Paramètres facultatifs `Fields`, `EnableImages`, `ImageTypeLimit`, `EnableImageTypes`, `EnableUserData`. Chaque bande-annonce est un élément à part, avec **son propre id**.
- `GET /Users/{UserId}/Items/{Id}` (lu par la fiche film avec `fullItem`, par la page série avec `item`) ne documente **aucun** paramètre `Fields` et renvoie le `BaseItemDto` du détail. L'application s'appuie déjà sur ce détail complet (genres, `People`, `MediaSources`, ADR 0050, 0058). Aucun `Fields` n'est donc ajouté. Le `Fields=Overview` que `item` envoie pour la série n'est pas un paramètre documenté de ce point d'accès ; il n'empêche pas les genres et `People` d'arriver.

Non vérifié sur le serveur réel (aucune requête faite pour ce lot) : que les deux champs sont bien présents dans la réponse de détail d'Emby 4.10, et qu'un élément du serveur de test a une bande-annonce locale.

## Décision
- **Bouton « Bande-annonce »** quand l'élément en a une :
  - Film : dans la rangée de lecture, après « Lire » / « Reprendre » et « Lire depuis le début ». Le premier bouton reste « Lire » / « Reprendre », celui qui prend le focus : une relecture de la fiche ne fait pas perdre le focus (ADR 0060).
  - Série : il n'y a pas de bouton de lecture sur la page série. Le bouton a sa propre ligne sous les pastilles de genre, au-dessus des saisons ; il ne prend jamais le premier focus (qui reste sur la carte d'épisode). Sans bande-annonce, la ligne n'existe pas et la rangée d'épisodes ne bouge pas.
- **Ordre** (`trailerChoices`, `emby/Trailers.kt`) : la bande-annonce locale d'abord (`LocalTrailerCount > 0`), puis la première bande-annonce distante dont l'adresse passe la validation. Aucune des deux : pas de bouton.
- **Bande-annonce locale** : à l'appui, `localTrailers` lit la liste, la première dont l'id passe la règle des segments de chemin est jouée dans le lecteur de l'application, par l'extra `itemId` habituel, donc par PlaybackInfo comme tout autre élément (le serveur décide Direct Play, Direct Stream ou transcodage). Si la liste est vide ou la requête échoue, on passe à la bande-annonce distante ; une session expirée ramène à la connexion. La liste n'est lue qu'à l'appui : ouvrir une fiche ne coûte aucune requête de plus.
- **Aucun rapport de lecture pour une bande-annonce** : `PlayerActivity` reçoit l'extra `trailer`. Avec lui, ni `Playing`, ni `Progress`, ni `Stopped` ne partent (`report` ne transmet rien au suivi), pas de recherche d'intro ni de génériques (aucun appel à IntroDB ou TheIntroDB), pas d'épisode suivant. Raison : la bande-annonce a son propre id, donc un rapport ne marquerait pas le film comme vu, mais il marquerait la bande-annonce elle-même, pourrait la placer dans « Reprendre » et déclencherait la relecture des fiches. Rien de tout cela n'est utile. Ne rien envoyer est le choix sans effet de bord.
- **Bande-annonce distante** : ouverte hors de l'application par un `Intent` `ACTION_VIEW` sur l'adresse, seulement si `trailerUrl` l'accepte : schéma `https`, port par défaut, pas d'identifiant ni de mot de passe dans l'adresse, pas d'espace ni de caractère de contrôle, 2048 caractères au plus, et hôte exactement dans la liste `youtube.com`, `www.youtube.com`, `youtu.be`, `m.youtube.com`. L'hôte est vérifié sur l'adresse lue par OkHttp, pas sur le texte. L'adresse est envoyée telle que le serveur l'a donnée : **aucun jeton** n'y est ajouté, et rien d'autre ne part vers ce site.
- **Aucune application pour l'ouvrir** (`ActivityNotFoundException`) : message « Aucune application ne peut ouvrir cette bande-annonce. » sous les boutons. Si aucune bande-annonce n'est jouable au moment de l'appui : « Bande-annonce introuvable sur le serveur. »
- **Logique pure** dans `emby/Trailers.kt` (`trailerChoices`, `trailerUrl`, `localTrailerId`, `trailerAction`), testée par `TrailersTest`. Le bouton est dans `ui/Trailer.kt`, branché par `MainActivity` (`TrailerHandlers`).

## Alternatives
- **Jouer la bande-annonce YouTube dans le lecteur de l'application** : mpv aurait besoin de yt-dlp et d'un accès à YouTube que l'application ne gère pas. Rejeté.
- **Accepter toute adresse `https`** : une métadonnée compromise ouvrirait n'importe quel site depuis la télé. La liste fermée d'hôtes YouTube couvre les cas réels. Rejeté.
- **Lire la liste des bandes-annonces locales en ouvrant la fiche** : une requête par fiche pour un bouton rarement pressé. `LocalTrailerCount` suffit pour décider d'afficher le bouton. Rejeté.
- **Envoyer les rapports de lecture comme pour un film** : sans intérêt pour l'utilisateur et avec des effets sur « Reprendre ». Rejeté.
- **Message par `Toast`** : moins visible sur une télé et différent des autres messages de l'application, affichés dans la page. Rejeté.

## Conséquences
- `BaseItemDto` lit `RemoteTrailers` (`MediaUrlDto`, deux champs facultatifs) et `LocalTrailerCount`.
- `EmbyClient.localTrailers` est ajouté à la fin de la classe. Le jeton reste dans l'en-tête `X-Emby-Authorization`.
- Une bande-annonce sur un autre site que YouTube (Vimeo, site d'un studio) n'a pas de bouton.
- Une bande-annonce locale vue jusqu'au bout ne laisse aucune trace sur le serveur.

## Vérification
- Tests unitaires : `TrailersTest` (adresses YouTube gardées, adresses refusées : `http`, autre hôte, sous-domaine piégé, `music.youtube.com`, identifiants, port, espace, saut de ligne, `javascript:`, `intent:`, `file:`, `plugin:` ; locale avant distante, première distante valide, pas de bouton ; lecture par l'id propre de la bande-annonce, id invalide ignoré, repli sur la distante si la liste est vide ou échoue, session expirée relancée ; lecture des deux champs) et `EmbyClientTest` (chemin `LocalTrailers`, jeton dans l'en-tête seulement, id piégé refusé). `testDebugUnitTest` (387 tests, 0 échec), `assembleDebug` et `lintDebug` passent, sans avertissement de lint dans les fichiers changés.
- **NON VÉRIFIÉ** (aucun essai sur la Shield ni sur le serveur réel pour ce lot) : la présence de `RemoteTrailers` et `LocalTrailerCount` dans le détail renvoyé par Emby 4.10, la lecture d'une bande-annonce locale par PlaybackInfo, l'absence de rapport côté serveur (tableau de bord Emby), l'application qui ouvre un lien YouTube sur la Shield (YouTube pour Android TV ou autre), le message quand aucune ne le peut, et le placement et le focus du bouton sur les deux pages.
