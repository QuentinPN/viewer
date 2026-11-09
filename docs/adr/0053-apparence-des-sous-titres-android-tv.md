# ADR 0053 — Apparence des sous-titres (Android TV)

Statut : Accepté (2026-11-09). Complète l'ADR 0051 et reprend l'apparence des sous-titres de `SubtitleSettings.svelte` (Windows, ADR 0035).

## Contexte
L'ADR 0051 avait laissé de côté l'apparence des sous-titres : sur TV, ils gardaient toujours le style par défaut de mpv. Windows règle la taille, la couleur, le fond, la position, et peut appliquer ces choix aux sous-titres ASS.

## Décision
- **Section « Sous-titres » dans « Réglages »**, entre « Qualité de lecture » et « À propos ». Une ligne par réglage (`ListItem`), OK passe à la valeur suivante, comme pour le débit. Mêmes libellés et mêmes valeurs par défaut que Windows (`src/lib/player/subtitles.ts`) :
  - Taille : Petite, Normale (par défaut), Grande, Très grande.
  - Couleur : Blanc (par défaut), Jaune.
  - Lisibilité : Contour (par défaut), Fond sombre.
  - Position : En bas (par défaut), Plus haut, En haut.
  - « Appliquer aussi aux sous-titres stylés (ASS) » : désactivé par défaut.
- **Propriétés mpv** : les valeurs de `subtitle_options` (`src-tauri/src/player/mpv.rs`), dans `player/SubtitleOptions.kt` : `sub-scale` 0,8 / 1 / 1,25 / 1,5, `sub-color` `#FFFFFF` ou `#FFE14D`, `sub-border-style` `outline-and-shadow` ou `background-box`, `sub-back-color` `#B3000000`, `sub-align-y` `bottom` ou `top`, `sub-ass-override` `yes` ou `force`. Windows met `no` ; sur TV, `no` fait ignorer `sub-pos` aux ASS, qui restaient alors sous les commandes (vu sur le Shield). `yes` garde leur police, leurs couleurs et leur taille, et les laisse monter ; « Plus haut » les remonte donc aussi, et le texte du réglage ASS le dit. Elles sont posées par `setPropertyString` avant chaque `loadfile`, y compris pour l'épisode suivant joué dans le même moteur.
- **Position et commandes** : depuis l'ADR 0042, les sous-titres montent au-dessus des commandes tant qu'elles s'affichent (`sub-pos`). `sub-pos` prend maintenant la plus haute des deux places : celle du réglage (100 en bas, 85 plus haut) et celle qui dégage les commandes. « Plus haut » ne redescend donc pas quand les commandes s'affichent, et les commandes ne couvrent jamais le texte. « En haut » part de 100, comme sous Windows, et suit aussi les commandes : sans « Appliquer aussi aux sous-titres stylés (ASS) », les dialogues ASS restent en bas et doivent encore les éviter. Un sous-titre placé en haut peut donc monter pendant que les commandes s'affichent, comme le notait déjà l'ADR 0042.
- **Stockage** : mêmes `SharedPreferences` « settings », par utilisateur Emby (`<userId>.subtitle.size`, etc.), avec les valeurs de Windows (`small`, `yellow`, `box`, `raised`…). Une valeur inconnue (ancienne ou future version, fichier modifié) est lue comme la valeur par défaut. Aucun secret ; le journal `ViewerTV` écrit le style appliqué.

## Alternatives
- **Options passées à `loadfile`** (comme `start`) : elles ne vaudraient que pour un fichier, mais `sub-pos` doit encore changer pendant la lecture avec les commandes. Les propriétés, reposées à chaque élément, restent plus simples.
- **Options avant `mpv_initialize`** : le moteur n'est créé qu'une fois par séance de lecture ; un changement de réglage ne s'appliquerait pas à l'épisode suivant.
- **Réglage pendant la lecture** (menu des pistes) : Windows ne le propose pas non plus. Prochain lot possible.

## Écarté de ce lot
- **Langues audio et sous-titres par défaut, mode des sous-titres** : même raison que l'ADR 0051, la TV retient la dernière piste choisie (ADR 0042).

## Conséquences
- Avec « Appliquer aussi aux sous-titres stylés (ASS) » désactivé, les sous-titres ASS gardent leur police, leurs couleurs et leur taille : seuls les sous-titres texte (SRT, WebVTT…) changent. Les sous-titres en image (PGS, VobSub) ne changent jamais.
- Non vérifié : l'acceptation de ces propriétés par la libmpv de mpv-android `2026-09-17` (`sub-border-style` demande mpv 0.38 ou plus) et le rendu à l'image. À vérifier sur le Shield.

## Validation
- Tests unitaires : valeurs mpv de chaque réglage, mêmes que le test Windows `applies_subtitle_style_and_track_preferences` ; position combinée avec les commandes (`SubtitleOptionsTest`) ; choix, libellés, valeurs inconnues et pas à pas (`PlaybackSettingsTest`).
