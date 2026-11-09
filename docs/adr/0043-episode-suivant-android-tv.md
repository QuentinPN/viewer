# ADR 0043 — Épisode suivant (Android TV)

Statut : Accepté (2026-11-09). Reprend l'ADR 0018 du client Windows ; complète les ADR 0040 et 0041.

## Contexte
À la fin d'un épisode, le lecteur Android TV se fermait et ramenait à l'accueil. Pour regarder une série, il fallait rouvrir chaque épisode à la main.

## Décision
- **Épisode suivant demandé au serveur** au lancement de chaque épisode : `GET /Shows/{seriesId}/Episodes?AdjacentTo={episodeId}`, avec `UserId`, sans images et avec `UserData`. La réponse franchit les saisons. On garde l'élément qui suit l'épisode courant dans la même série (`nextEpisode` dans `emby/NextEpisode.kt`). Il démarre à sa position de reprise, sauf s'il est déjà vu.
- **Carte seulement à la fin naturelle** : il reste au plus 30 s quand mpv atteint la fin du fichier, et un épisode suivant est connu. La carte « Épisode suivant » montre la série, « S1 · É3 — titre », « Lecture dans N s » et une jauge qui se vide. « Lire maintenant » a le focus ; « Annuler » et Retour ferment le lecteur. Après 10 s, l'épisode suivant démarre seul. Un film, le dernier épisode ou une sortie avant la fin ferment le lecteur comme avant.
- **Même lecteur, fichier remplacé** : `loadfile replace` dans le même mpv, lecture relancée (`pause=no`). Le rapport `Stopped` de l'épisode fini part avant la négociation PlaybackInfo du suivant. La fin du fichier remplacé, qu'mpv signale aussi, est ignorée. Les réponses tardives de l'épisode précédent sont écartées.
- **Accueil** : au retour, l'épisode vu quitte « Reprendre ». Le focus attend une frame avant d'aller sur la carte cible ; sans cela, il tombait sur « Déconnecter ».

## Alternatives
- **`/Shows/NextUp`** : vide sur Emby 4.10.1.0 pour ce cas (constat de l'ADR 0010). Écarté.
- **Nouvelle activité par épisode** : recrée la surface et mpv, avec un écran noir entre deux épisodes. Écarté.
- **Marqueurs de générique Emby** (intro/credits) : non lus pour l'instant ; la carte n'apparaît qu'à la fin du fichier.

## Conséquences
- Les en-têtes HTTP de mpv restent ceux du premier fichier ; ils ne changent pas d'un épisode à l'autre sur un même serveur.
- Pas de réglage pour couper l'enchaînement automatique.
- Chaque épisode enchaîné est marqué vu sur le serveur, comme une fin normale.
