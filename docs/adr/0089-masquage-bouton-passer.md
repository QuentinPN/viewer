# ADR 0089 — Masquage du bouton « Passer » au bout de 10 s

Statut : Accepté (2026-11-10). Complète les ADR 0030 (Windows), 0048 et 0051 (Android TV).

## Contexte
Les boutons « Passer l'intro » et « Passer le générique » restent affichés pendant tout le segment. Quand le propriétaire choisit de regarder l'intro, le bouton reste sur l'image jusqu'à la fin : « que le bouton "passer l'intro" disparaisse au bout de 10s après son apparition par ex (par exemple si je ne veux pas le skip, il reste tout du long de l'intro actuellement) ». Le changement vaut pour Windows et Android TV.

## Décision
- **Même règle sur les deux clients**, en logique pure : `trackSkipShown` et `skipShown` dans `src/lib/player/segments.ts` (Windows) et `android-tv/…/player/Skip.kt` (Android TV), `SKIP_SHOWN_MS = 10 000`.
- **Apparition :** le bouton s'affiche quand son segment commence et reste 10 s, commandes affichées ou non.
- **Ensuite :** il ne revient qu'avec les commandes (mouvement de souris ou touche sous Windows, touche de la télécommande sur la TV, et toujours pendant la pause), jusqu'à la fin du segment. C'est le comportement courant des lecteurs (Netflix, Infuse) : le bouton suit les commandes une fois sa présentation passée.
- **Nouveau départ :** le compte à rebours repart à l'entrée de chaque segment. Sortir du segment, par sa fin ou par un retour avant son début, l'efface ; le générique après l'intro a donc ses 10 s. Sous Windows la clé est l'élément et le segment (`itemId:intro@50`) ; sur la TV c'est le bouton proposé (libellé et fin), nul hors segment et pendant la carte « Épisode suivant ». Sur la TV, le générique dont la carte a été fermée a donc 10 s de bouton à partir de la fermeture.
- **Sous Windows**, le compte suit la position seule : un panneau ou la carte « Épisode suivant » au-dessus du segment ne le relancent pas. Un minuteur réveille l'affichage à l'échéance.
- **Sur la TV**, l'échéance est lue à chaque position (`time-pos` arrive chaque seconde) : le bouton disparaît au plus 1 s après les 10 s.
- **Touche OK / Entrée et focus :** tant que le bouton est visible, OK (TV) ou Entrée (Windows) passe le segment, comme avant. Une fois masqué, la touche ne passe rien et ne met pas en pause : elle affiche seulement les commandes, et le bouton avec. Sur la TV, le bouton focalisé qui disparaît rend le focus au lecteur (rien n'est focalisable commandes masquées), puis reprend le focus à son retour comme à son apparition. Un appui qui arrive juste après l'échéance ne fait donc que réafficher le bouton : un second appui passe le segment.
- **Passage automatique** (ADR 0030, 0051) inchangé : le segment est passé à son début, avant toute question d'affichage. Après un retour dans un segment déjà passé, le bouton réapparaît avec ses 10 s.

## Alternatives
- **Masquer pour tout le segment après 10 s**, même commandes affichées : le bouton deviendrait introuvable si le propriétaire change d'avis au milieu de l'intro. Écarté.
- **Garder la touche OK active sur le bouton masqué** : une action invisible ; un appui pour mettre en pause passerait l'intro. Écarté.
- **Laisser OK reprendre son rôle (pause) dès le masquage** : un appui tardif, pensé pour passer l'intro, mettrait en pause. Afficher les commandes est sans effet sur la lecture. Écarté.
- **Durée réglable** : pas demandé ; une constante par client suffit.

## Conséquences
- Pendant un segment dont le bouton est masqué, OK (TV) ou Entrée (Windows) ne met plus en pause au premier appui : il réaffiche les commandes et le bouton. Les touches lecture / pause et Espace mettent toujours en pause.
- La règle est dupliquée dans deux langages ; les tests des deux côtés décrivent les mêmes cas.

## Vérification
- Windows : Biome, svelte-check, tsc, Vitest (`segments.test.ts`, 4 nouveaux cas), build Vite. Rust inchangé.
- Android TV : `testDebugUnitTest` (`SkipTest`, 5 nouveaux cas), `assembleDebug`, `lintDebug`.
- **Non vérifié sur appareil :** ni dans l'application Windows (utilisée par le propriétaire pendant ce changement), ni sur la Shield TV (occupée par une autre session). À vérifier : intro regardée sans la passer, bouton disparu après 10 s, revenu avec les commandes, passage par OK après son retour, générique avec ses 10 s, retour avant le début de l'intro.
