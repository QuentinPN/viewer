# ADR 0049 — Marques des saisons (Android TV)

Statut : Accepté (2026-11-09). Reprend `EpisodeList.svelte` et `defaultSeason` (Windows, ADR 0031).

## Contexte
Sur la page série TV, les boutons de saison ne disaient pas où en était la série, et la première saison s'ouvrait même quand elle était finie.

## Décision
- **Bouton de saison** : le nom, puis le nombre d'épisodes non vus (`UserData.UnplayedItemCount`) en couleur d'accent, ou une coche si la saison est vue.
- **Saison ouverte** : la première saison régulière avec des épisodes non vus, sinon la première régulière, sinon les épisodes spéciaux, comme `defaultSeason` sous Windows.
- **Après une lecture** : les saisons sont relues sur place, la saison affichée reste la même.

## Alternatives
- **Ouvrir la saison de l'épisode « Reprendre »** : demande une requête de plus ; la règle Windows donne le même résultat dans le cas courant.

## Conséquences
- Pas de requête nouvelle : `/Shows/{id}/Seasons` était déjà demandé avec `EnableUserData=true`.
