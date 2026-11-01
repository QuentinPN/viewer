# ADR 0020 — Recherche des épisodes

Statut : Accepté (2026-11-01). Complète l'ADR 0011.

## Contexte
L'ADR 0011 ne cherchait pas les épisodes, faute de fiche à ouvrir. Depuis l'ADR 0016, un épisode a sa fiche.

Ce qui a été observé le 2026-11-01 sur Emby 4.10.2.0, en lecture seule avec le compte de test (`IncludeItemTypes=Episode`, test ignoré `real_server_search`) :
- `SearchTerm` trouve les épisodes **par leur titre, mais aussi par le nom de leur série**. Par exemple, « reincarne » renvoie « Shizu » et « Charybde », deux épisodes de « Moi, quand je me réincarne en Slime » dont le titre ne contient pas le mot. Avec 25 épisodes demandés, ce bruit remplit toute la page.
- Le contrat public du serveur (`/emby/openapi.json`) ne propose aucun paramètre qui limite la recherche au titre de l'élément.
- Comme pour les films, la casse et les accents sont ignorés, la recherche porte sur le début des mots, et `TotalRecordCount` n'est pas fiable.
- Les réponses contiennent `SeriesName`, `ParentIndexNumber`, `IndexNumber` et les images de l'épisode ou de sa série, comme pour les rangées de l'accueil.

## Décision
- **Troisième requête** : `emby_search` cherche les épisodes en parallèle des films et des séries. Elle demande **100 épisodes**, avec les champs et les images des cartes de l'accueil (`Primary,Backdrop,Thumb`).
- **Filtre en Rust** : seuls les épisodes dont **le titre lui-même** contient la recherche au début d'un mot sont gardés (`title_matches`). La comparaison ignore la casse et les accents des lettres latines, mais ne remplace pas « œ » par « oe », comme le serveur. On en affiche 24 au plus, dans l'ordre du serveur.
- **« Peut-être plus »** : `moreEpisodes` vaut vrai si plus de 24 épisodes sont gardés, ou si le serveur a rempli ses 100 places. Dans ce second cas, d'autres titres peuvent correspondre plus loin. L'interface dit donc « au moins N épisodes » et « D'autres épisodes peuvent correspondre : précisez la recherche. »
- **Interface** :
  - une section « Épisodes » après « Séries », en cartes 16:9 (`WatchCard`, en mode « ouvrir ») ;
  - sur chaque carte, le titre de l'épisode d'abord, puis « Série · S1 · É4 » ;
  - une carte **ouvre la fiche de l'épisode** au lieu de lancer la lecture, puisqu'on cherche un titre ; la fiche propose « Lecture » ;
  - « Épisode précédent / suivant » remplacent la fiche sur place, le focus restant sur le bouton ;
  - Échap ou « Retour » ramène aux résultats, le focus sur la carte qui a ouvert la fiche, même après plusieurs épisodes voisins ;
  - le message vide devient « Aucun film, série ou épisode pour « … » ».

## Alternatives
- **Afficher ce que renvoie le serveur** : c'est ce que fait Emby, mais une série dont le nom correspond remplit la liste de ses épisodes. Écarté.
- **Retirer les épisodes des séries trouvées** : on garderait le bruit des séries au-delà des 24 affichées, et on perdrait les épisodes de ces séries dont le titre correspond vraiment. Écarté.
- **Une bibliothèque de normalisation Unicode** (`unicode-normalization`) : un repli plus complet, mais une dépendance de plus pour les seules lettres latines accentuées des titres. Écarté pour l'instant ; un tableau de 19 lettres suffit.
- **Une carte qui lit l'épisode**, comme à l'accueil : on cherche un titre, souvent pour vérifier lequel c'est. Ouvrir la fiche montre le résumé et laisse lancer la lecture. Écarté.

## Conséquences
- La requête des épisodes renvoie jusqu'à 100 éléments, sans les résumés. Temps mesuré sur le serveur réel : 28 à 190 ms pour les trois requêtes.
- Si plus de 100 épisodes ne correspondent que par leur série, les vrais résultats placés après eux ne sont pas montrés. Le message « D'autres épisodes peuvent correspondre » le signale.
- Le filtre peut être plus strict que le serveur : un titre avec un accent hors du tableau (lettres non latines, par exemple) n'est trouvé que si la recherche porte le même accent. Un mot placé dans un autre ordre n'est pas trouvé non plus, car la recherche doit apparaître d'un seul tenant.
- Les personnes et les genres ne sont toujours pas cherchés.
