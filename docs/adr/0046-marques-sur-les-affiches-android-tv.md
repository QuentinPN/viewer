# ADR 0046 — Marques sur les affiches (Android TV)

Statut : Accepté (2026-11-09). Reprend `PosterCard.svelte` (Windows).

## Contexte
Les affiches de l'accueil et de la grille ne disaient pas ce qui avait été vu. Avec le filtre « Non vus » (ADR 0045), il manquait le repère sur l'affiche elle-même.

## Décision
- **Coche** en haut à droite quand `UserData.Played` est vrai, film ou série.
- **Nombre d'épisodes non vus** (`UserData.UnplayedItemCount`) sur une série pas encore vue, s'il est supérieur à 0 ; « 99+ » au-delà de 99.
- **Barre de progression** en bas de l'affiche d'un film commencé, comme sur les vignettes « Reprendre ».
- **Style** : pastille ronde noire à 60 %, texte blanc, sur toutes les affiches (rangées de l'accueil et grille), pas sur les vignettes 16:9.

## Alternatives
- **Un triangle de coin « non vu »** (style Infuse) : plus visible, mais pas le choix fait sous Windows. Écarté pour garder les deux clients cohérents.

## Conséquences
- Les données viennent des réponses déjà demandées avec `EnableUserData=true` : aucune requête de plus.
- Après une lecture, la grille ne se met à jour qu'au rechargement de la page.
