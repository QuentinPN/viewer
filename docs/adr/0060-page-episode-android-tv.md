# ADR 0060 — Fiche d'un épisode (Android TV)

Statut : Accepté (2026-11-09). Reprend sur TV la fiche d'épisode de Windows (ADR 0016) ; complète les ADR 0050, 0054 et 0058.

## Contexte
Sur TV, une carte d'épisode de la page série lançait la lecture dès OK. Il n'y avait aucun moyen de lire le résumé complet, de voir la réalisation, le scénario, les invités ou la date de diffusion, ni de choisir la version d'un épisode qui en a deux. L'ADR 0058 avait reporté le choix de version des épisodes faute de fiche ; l'ADR 0054 citait la fiche d'épisode comme prochain lot possible.

Sous Windows (ADR 0016), « la vignette lit, le titre ouvre » : deux cibles dans une même ligne. À la télécommande, une carte n'a qu'une cible de focus. La fiche film TV, elle, s'ouvre par OK sur l'affiche, et c'est son bouton « Lire » qui lit.

## Décision
- **Ouverture** : OK sur une carte d'épisode ouvre sa fiche, comme OK sur un film ouvre la sienne. Un **appui long sur OK** lit l'épisode tout de suite (`Card.onLongClick`), au point de reprise comme avant. Une ligne d'aide sous la rangée le dit : « OK : fiche de l'épisode · appui long : lecture ».
- **Inchangé** : les cartes d'épisode de l'accueil, « Reprendre », « À suivre » et la carte « Épisode suivant » du lecteur lisent toujours directement, comme sous Windows (« Non, elles lisent »). Un épisode trouvé par la recherche ouvre toujours sa série sur la bonne saison (ADR 0054).
- **Contenu** (`ui/EpisodeScreen.kt`) :
  - l'image de l'épisode (`ImageTags.Primary`), sinon le fond de la série (`ParentBackdropItemId`/`ParentBackdropImageTags`), fondue dans le noir ;
  - le nom de la série et « Saison 2 · Épisode 14 » (« Épisodes 2–3 » pour un épisode double, « Spécial 3 » en saison 0), mêmes règles que `episodePosition` de Windows ;
  - le titre, la date de diffusion (« 5 juillet 2024 », dans le fuseau de l'appareil comme Windows), la durée, la marque « Vu » ;
  - le résumé (3 lignes), puis « Réalisation », « Scénario », « Invités » (3 lignes au plus) : `Credits.kt` prend les `GuestStar` d'un épisode sous « Invités » au lieu des `Actor` de la série, comme Windows ;
  - « Lire » ou « Reprendre à … » et « Lire depuis le début », comme la fiche film ;
  - « Version : … » quand l'épisode a au moins deux versions avec un id valide (ADR 0058, `Versions.kt` réutilisé tel quel, même mémoire `versions` par élément) ; l'id part dans le corps PlaybackInfo par l'extra `mediaSourceId`, comme pour un film ;
  - « Épisode précédent » / « Épisode suivant », avec « S1 · É3 · Titre ».
- **Requêtes** : `fullItem` (`/Users/{Id}/Items/{Id}` sans `Fields`), qui porte `MediaSources`, `People`, `PremiereDate` et les images de la série (contrat ADR 0016), puis `adjacentEpisodes` (`AdjacentTo`, déjà utilisé par le lecteur, ADR 0043). Les voisins sont les épisodes juste avant et juste après dans cette réponse, autres séries écartées, d'une saison à l'autre (`neighbours`, même règle que `nextEpisode`). Si `AdjacentTo` échoue, la fiche s'affiche sans voisins ; une session expirée ramène toujours à la connexion.
- **Modèle** : `BaseItemDto` lit `PremiereDate` et `IndexNumberEnd`, deux champs facultatifs déjà lus par Windows (`models.rs`, fixtures de `tests.rs`).
- **Précédent / suivant** : la fiche affichée reste à l'écran pendant que la suivante se charge, puis elle est remplacée. Le focus reste sur le bouton utilisé ; si le nouvel épisode n'en a pas (dernier ou premier épisode), il passe à l'autre voisin, sinon sur « Lire ».
- **Retour** : la fiche remplace la page série dans la navigation de `MainActivity`. Retour rouvre la page série avec un `SeriesTarget` (ADR 0054) : la saison de la carte d'où la fiche a été ouverte, ou, après précédent/suivant, celle du nouvel épisode (`SeasonId`, sinon `ParentIndexNumber`), et le focus sur la carte de l'épisode (`initialEpisodeIndex`). Retour depuis la série revient ensuite là d'où la série avait été ouverte.
- **Après une lecture** : la fiche relit l'épisode en place (`refresh`), comme la fiche film ; si « Lire depuis le début » disparaît, le focus revient sur le premier bouton.

## Alternatives
- **Garder OK = lecture et ouvrir la fiche par appui long ou touche Menu** : moins de changement d'habitude, mais l'inverse de la fiche film, et une fiche cachée derrière un geste ou une touche que toutes les télécommandes n'ont pas. Le contenu complet mérite le geste principal ; la lecture immédiate reste à l'appui long. Écarté.
- **Deux cibles par carte (image qui lit, titre qui ouvre)**, calque de Windows : deux arrêts de focus par épisode doublent les appuis pour parcourir une saison. Écarté.
- **Garder la page série montée sous la fiche** (Windows la masque sans la démonter) : demanderait de cacher un écran Compose tout en lui retirant le focus. Le `SeriesTarget` de l'ADR 0054 suffit pour revenir sur la bonne saison et la bonne carte, au prix d'un rechargement. Retenu plus simple.
- **Lire toute la série pour trouver les voisins** : même raison que l'ADR 0016. Écarté.

## Conséquences
- Lire un épisode depuis la page série demande désormais OK puis OK (ou un appui long) au lieu d'un seul OK.
- Ouvrir une fiche coûte deux requêtes l'une après l'autre ; le retour à la série relit la série, ses saisons et la saison ouverte (un bref « Chargement… »).
- Le choix de version est maintenant possible pour un épisode, avec les mêmes limites que l'ADR 0058.
- Les informations techniques de la fiche Windows ne sont pas reprises sur TV, pas plus que sur la fiche film TV.
- Une lecture lancée depuis la fiche qui enchaîne sur les épisodes suivants (ADR 0043) laisse, au retour, la fiche de l'épisode de départ, relue.

## Vérification
- Tests unitaires `EpisodePageTest` : voisins d'une saison à l'autre, autres séries écartées, premier et dernier épisodes, épisode absent de la réponse ; « Saison · Épisode », épisodes doubles et spéciaux ; libellés des boutons précédent/suivant ; date de diffusion dans le fuseau (UTC 22 h = lendemain à Paris), date sans fuseau, date illisible ; image de l'épisode sinon fond de la série ; saison du retour ; cible d'une carte ; « Invités » pour un épisode et « Avec » pour un film ; lecture de `PremiereDate` et `IndexNumberEnd`. `testDebugUnitTest` (173 tests, 0 échec), `assembleDebug` et `lintDebug` passent, sans avertissement de lint dans les fichiers changés.
- Non vérifié sur le Shield ni sur le serveur : l'appui long d'une carte, la mise en page de la fiche à 1080p (hauteur avec un long titre, une version et les voisins), la ligne d'aide sous la rangée d'épisodes, le focus gardé après précédent/suivant, le retour sur la bonne saison et la bonne carte, la relecture après une lecture. Non vérifié non plus que `AdjacentTo` renvoie `SeasonId` (sans lui, le numéro de saison sert de repli, ADR 0054) ni qu'un épisode à deux versions existe sur le serveur de test (aucun trouvé, ADR 0058).
