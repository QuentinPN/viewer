# ADR 0050 — Fiche du film et de la série (Android TV)

Statut : Accepté (2026-11-09). Reprend la liste de faits de `ItemDetail.svelte` (Windows).

## Contexte
Les pages film et série TV ne montraient que l'année, la durée et le résumé. Windows affiche aussi la réalisation, la distribution, le studio, les genres et les notes.

## Décision
- **Ligne de l'année** : classification, trois genres au plus, note du public (« ★ 7,4 »).
- **Fiche** : Réalisation, Scénario (films seulement), Avec (6 acteurs), Studio ou Diffusion pour une série. Une ligne par fait, coupée par des points de suspension.
- **Page série** : deux lignes au plus, pour laisser la place à la rangée d'épisodes.
- Les données viennent de `/Users/{Id}/Items/{Id}`, qui les renvoie sans `Fields` : aucune requête de plus.

## Alternatives
- **Rangée de portraits des acteurs** : demande des images en plus et une navigation de plus à la télécommande ; écartée pour l'instant.

## Conséquences
- `Studios[].Id` étant un nombre sur Emby 4.10, seul le nom est lu.
