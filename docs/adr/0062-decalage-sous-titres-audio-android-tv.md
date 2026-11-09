# ADR 0062 — Décalage des sous-titres et de l'audio dans le lecteur (Android TV)

Statut : Accepté (2026-11-09). S'ajoute au menu des pistes (ADR 0042, 0055) ; la ligne « Informations de lecture » (ADR 0061) reste la dernière.

## Contexte
Le projet est né d'un défaut de l'application Emby de la TV LG : des sous-titres ASS affichés avec plusieurs minutes de retard. mpv les place correctement dans les cas connus, mais un fichier mal synchronisé à la source (sous-titres externes d'une autre version, audio décalé par un remux) reste possible, et rien sur TV ne permettait de le rattraper.

libmpv a deux propriétés pour cela, en secondes (double) : `sub-delay` (positif : sous-titres plus tard) et `audio-delay` (positif : audio plus tard). Ce sont aussi des options globales : comme `aid` et `sid`, une valeur posée pour un fichier resterait pour le suivant chargé dans le même moteur (épisode suivant, ADR 0043).

Touches du lecteur : ▼ et Menu ouvrent le menu des pistes ; ◀ ▶ y étaient sans effet, le menu n'ayant qu'une colonne.

## Décision
- **Deux lignes dans le menu ▼**, sous un titre « Synchronisation », après les pistes audio et avant « Informations de lecture » :
  - « Décalage des sous-titres », **seulement quand une piste de sous-titres est affichée** : décaler des sous-titres invisibles ne changerait rien à l'écran ;
  - « Décalage audio », toujours.
- **Touches sur ces lignes** : ◀ avance de 0,1 s (valeur plus petite), ▶ retarde de 0,1 s ; maintenues, elles se répètent. OK remet à zéro. ▲ ▼ passent d'une ligne à l'autre comme ailleurs ; Retour ferme le menu. Une aide sous le libellé le rappelle : « ◀ ▶ 0,1 s · OK remet à zéro ».
- **Bornes** : ±10 s. Au-delà, le fichier lui-même est à corriger. Les pas se font en dixièmes entiers, sans dérive de virgule flottante.
- **Affichage** : « +0,3 s », « −1,2 s » (vrai signe moins, virgule décimale, toujours un chiffre après la virgule pour que la valeur ne saute pas d'une largeur à l'autre), « Aucun » à zéro.
- **Application** : `PlayerEngine.setDelay` pose la propriété mpv en double puis la relit ; la ligne montre la valeur relue. Si mpv ne la rend pas, la ligne garde la dernière valeur connue.
- **Durée : la lecture en cours seulement.** Les deux décalages reviennent à zéro à chaque nouveau fichier, y compris l'épisode suivant joué dans le même moteur : `loadNow` remet `sub-delay` et `audio-delay` à 0 avec `aid` et `sid`, et `PlayerUi.reset()` remet l'affichage à « Aucun ». Changer de piste de sous-titres pendant la lecture garde le décalage.
- **Logique pure** dans `player/Delay.kt` (pas, bornes, format, remise à zéro), testée par `DelayTest`.

## Alternatives
- **Mémoriser le décalage par élément ou par série** : un décalage tient à un fichier précis ; l'appliquer à l'épisode suivant, ou à une autre version du même élément, le désynchroniserait. Reporté, à revoir si un même fichier demande souvent le même réglage.
- **Réglage global dans Réglages** : même défaut, en pire. Rejeté.
- **Pas de 0,05 s ou de 0,5 s** : 0,1 s suffit à l'œil pour les sous-titres ; 100 pressions maximum pour la borne, la répétition de touche y supplée. Pour un décalage de plusieurs minutes, ±10 s ne suffit pas : c'est le fichier qu'il faut corriger.
- **Touches directes pendant la lecture** (comme `z` / `x` de mpv) : ◀ ▶ servent déjà au saut de 10 s. Rejeté.
- **Écran séparé avec curseur** : une étape de plus pour la même chose. Rejeté.

## Conséquences
- Le menu ▼ a deux lignes de plus et un titre ; la sélection des pistes et la ligne « Informations de lecture » ne changent pas.
- Le décalage audio retarde ou avance le son par rapport à l'image ; en sortie numérique (bitstream), le comportement dépend de la sortie audio de mpv-android.
- Réversible : retirer les lignes et `setDelay` ne touche à rien d'autre ; la remise à zéro par fichier est sans effet tant que rien ne pose de décalage.

## Vérification
- Tests unitaires `DelayTest` : pas de 0,1 s dans les deux sens, trente pas exacts sans dérive, valeur relue légèrement inexacte, bornes ±10 s (y compris une valeur hors bornes), remise à zéro, format français (« Aucun », « +0,3 s », « −1,2 s », arrondi au dixième), et état par défaut de `Delays`. `testDebugUnitTest`, `assembleDebug` et `lintDebug` passent, sans avertissement lint dans les fichiers modifiés.
- Non vérifié sur le Shield : ◀ ▶ et leur répétition sur les lignes, OK qui remet à zéro, l'apparition de la ligne des sous-titres en activant une piste, l'effet réel de `sub-delay` sur des ASS et des sous-titres externes, celui de `audio-delay` en sortie PCM et en bitstream, la valeur relue par mpv-android, et la remise à zéro à l'épisode suivant.
