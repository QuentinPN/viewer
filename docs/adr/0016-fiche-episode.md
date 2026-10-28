# ADR 0016 — Fiche d'un épisode

Statut : Accepté (2026-10-28).

## Contexte
Jusqu'ici, un épisode n'avait pas de fiche : sa vignette lançait la lecture depuis la liste de la série. L'utilisateur a validé la fiche d'épisode le 2026-10-28, avec ces choix :
- **ouverture** : « Vignette lit, titre ouvre ». La vignette garde la lecture immédiate ; le titre et le texte de l'épisode ouvrent la fiche ;
- **contenu** : « Complète, comme un film » :
  - grande image ;
  - série, saison et numéro ;
  - titre, date de diffusion, durée, résumé ;
  - réalisation, scénario, invités ;
  - informations techniques ;
  - « Lecture » ou « Reprendre » ;
- **navigation** : « Précédent / suivant », y compris d'une saison à l'autre. Échap revient à la série, le focus sur cet épisode ;
- **accueil** : « Non, elles lisent ». Les cartes d'épisode de l'accueil lancent toujours la lecture, et « Plus d'infos » du héros ouvre toujours la série.

Contrat vérifié le 2026-10-28 sur Emby 4.10.1.0, en lecture seule avec le compte de test (sonde PowerShell, puis le test ignoré `real_server_episode_detail`).
- `GET /Users/{Id}/Items/{Id}` d'un épisode renvoie :
  - `SeriesId`, `SeriesName`, `SeasonId`, `ParentIndexNumber`, `IndexNumber` ;
  - `People` de types `Director`, `Writer`, `GuestStar` et `Actor` ;
  - `ImageTags.Primary`, l'image de l'épisode ;
  - `ParentBackdropItemId`/`ParentBackdropImageTags` et `ParentLogoItemId`/`ParentLogoImageTag` de la série, ainsi que `SeriesPrimaryImageTag` ;
  - `MediaSources`.
- `GET /Shows/{Id}/Episodes?AdjacentTo={épisode}` passe bien d'une saison à l'autre. En revanche, il ne renvoie **pas** seulement les deux voisins : il renvoie jusqu'à 5 épisodes avant, puis toute la suite de la série. Exemples : 50 épisodes pour S2É14 de « 9-1-1: Lone Star », 204 pour S1É24 de « 24 Heures chrono ».

## Décision
- **Commande** : `emby_item_detail` accepte aussi un épisode (`DetailKind::Episode`). Pour un épisode, Rust fait une seconde requête, `AdjacentTo`, sans images ni données utilisateur. Il en garde les épisodes de la même série, juste avant et juste après l'épisode demandé (`previous`/`next`). Si l'épisode n'est pas dans la réponse, il n'y a ni précédent ni suivant.
- **`ItemDetail`** reçoit :
  - `writers` (scénario), utile aussi pour les films ;
  - `episode` : série, saison, numéros, précédent et suivant.

  Pour un épisode :
  - `cast` contient les **invités** (`GuestStar`), pas la distribution principale de la série ;
  - `backdrop` est l'image de l'épisode, sinon le fond de la série ;
  - `poster` est l'affiche de la série ;
  - `logo` est le logo de la série.
- **Interface** : `ItemDetail.svelte` affiche aussi l'épisode, avec :
  - au-dessus du titre, la série (son logo ou son nom), puis « Saison 2 · Épisode 14 » ;
  - le titre de l'épisode ;
  - la date, la durée et la note ;
  - Lecture/Reprendre et « Lire depuis le début », comme pour un film ;
  - « Réalisation », « Scénario », « Invités » (la date de diffusion est déjà dans la ligne d'en-tête, elle n'est pas répétée) ;
  - les informations techniques ;
  - les boutons « Épisode précédent » et « Épisode suivant », avec leur numéro et leur titre.

  Il n'y a **pas de bouton Favori** sur un épisode : la rangée « Favoris » ne montre que des films et des séries (ADR 0014).
- **Imbrication** : la fiche de la série ouvre la fiche de l'épisode par-dessus elle. La fiche de la série reste montée mais masquée (`visibility: hidden`, `inert`), ce qui garde la saison choisie et le défilement. Précédent et suivant remplacent la fiche de l'épisode, et le focus reste sur le bouton utilisé.
- **Retour** : Échap, Alt+← ou « Retour » revient à la série. La liste passe à la saison de l'épisode si besoin, puis met le focus sur son titre. Si l'épisode n'est pas dans les pages chargées, le focus va sur le bouton de sa saison.
- **Dans la liste** : le titre de l'épisode devient un bouton qui ouvre la fiche, et sa zone cliquable couvre le texte. La vignette garde son bouton de lecture.

## Alternatives
- **Lire toute la série pour trouver les voisins** : une seule règle, mais 204 épisodes à transférer pour « 24 Heures chrono ». `AdjacentTo` fait mieux pour l'épisode d'avant : au plus 5 épisodes avant lui. Écarté.
- **Voisins seulement dans la saison** : plus simple, mais le passage d'une saison à l'autre était demandé. Écarté.
- **Un composant de fiche séparé** : il aurait dupliqué l'en-tête, les styles et la section technique. Écarté.

## Conséquences
- Ouvrir un épisode coûte deux requêtes, l'une après l'autre : la seconde a besoin de la série de l'épisode. La réponse `AdjacentTo` grandit avec la suite de la série, mais sans images ni données utilisateur.
- Les épisodes spéciaux (saison 0) apparaissent dans l'ordre que donne le serveur. Cet ordre n'a pas été observé sur le serveur réel.
- Les fiches de film affichent désormais aussi « Scénario » quand le serveur l'indique.
