# ADR 0078 — Format de l'image dans le lecteur (Android TV)

Statut : Accepté (2026-11-10). S'ajoute au titre « Lecture » du menu ▼ (ADR 0072, 0073) ; « Informations de lecture » (ADR 0061) reste la dernière ligne.

## Contexte
Demande du propriétaire pour la TV : un film en 2,39:1 ou une vieille série en 4:3 laisse des bandes noires sur l'écran 16:9 ; il veut pouvoir les faire disparaître, quitte à perdre les bords de l'image ou à la déformer.

Ce que dit le manuel de mpv (`DOCS/man/options.rst` et `input.rst`, branche `master`, lus le 2026-11-10) :
- `--panscan=<0.0-1.0>` : rogne les côtés de l'image pour remplir la fenêtre sans bandes noires, la valeur réglant la part rognée ; à 1, l'image est agrandie jusqu'à ce que sa largeur ou sa hauteur remplisse la fenêtre, proportions gardées. « May not work with all video output drivers » ; sans effet avec `--video-unscaled`.
- `--keepaspect=no` : étire toujours l'image à la taille de la fenêtre. La mention « (Ignored in fullscreen mode.) » qui suit porte, d'après le texte, sur les indications de proportions données au gestionnaire de fenêtres ; Android n'en a pas et le lecteur ne pose pas `fullscreen`.
- `--video-unscaled` : désactive la mise à l'échelle, et avec elle `panscan` ; lui-même désactivé par `keepaspect=no`. Valeur par défaut `no`, jamais posée par l'application.
- Propriétés : « Most options can be set at runtime via properties as well », avec les mêmes valeurs que l'option. Dans le code (`video/out/vo.c`, `update_opts`), tout changement d'une option de la VO envoie `VOCTRL_SET_PANSCAN` au pilote, qui recalcule la place de l'image : pas de rechargement du fichier.

Le pont JNI (`MPVLib.setPropertyString`) ne rend pas le code d'erreur de mpv (ADR 0072) : seule une relecture dit si mpv a pris la valeur.

## Décision
- **Une ligne « Format »** dans le menu ▼, titre « Lecture », juste sous « Vitesse » et avant « Mode nuit ». Valeurs : « Original » (défaut), « Remplir l'écran », « Étirer ». ◀ ▶ passent à la valeur précédente ou suivante, bloqués aux deux bouts ; OK revient à Original. Même style, mêmes touches et même focus que « Vitesse » (ADR 0072). Aide : « ◀ ▶ change · OK revient à Original ».
- **Propriétés mpv**, toujours posées ensemble pour qu'aucune valeur du format précédent ne reste :
  - Original : `panscan=0`, `keepaspect=yes` (valeurs par défaut de mpv : proportions gardées, bandes noires) ;
  - Remplir l'écran : `panscan=1`, `keepaspect=yes` (proportions gardées, bords rognés) ;
  - Étirer : `panscan=0`, `keepaspect=no` (tout l'écran, image déformée).
  `video-unscaled` reste à `no` : il annulerait `panscan`.
- **Relecture** : `PlayerEngine.setVideoFit` pose les deux propriétés puis relit `panscan` et `keepaspect` ; la ligne montre le format relu. Une relecture illisible, ou une combinaison qui ne correspond à aucun format proposé, laisse la ligne sur le dernier format confirmé.
- **Pastille** à côté de celle de la vitesse, hors Original : « Remplir » ou « Étiré ».
- **Portée** : la lecture en cours seulement, comme la vitesse. `loadNow` remet `panscan=0` et `keepaspect=yes` avec `speed`, `aid`, `sid` et les décalages ; `PlayerUi.reset()` remet l'affichage à Original. Chaque nouvel élément, épisode suivant compris, repart en Original. Rien n'est enregistré.
- **Frontière** : l'interface passe uniquement par `PlayerEngine.setVideoFit(VideoFit)` ; aucune chaîne de propriété mpv ne vient de l'UI.
- **Logique pure** dans `player/VideoFit.kt` (formats, libellés et pastilles français, pas, correspondance format → propriétés mpv, lecture des valeurs relues), testée par `VideoFitTest`.

## Alternatives
- **`video-zoom`** : zoom arbitraire, avant ou arrière, qui fonctionne même avec `video-unscaled`. Plus souple, mais il faudrait calculer le facteur selon les proportions de l'image et de l'écran ; `panscan=1` le fait déjà. Rejeté.
- **Plusieurs crans de `panscan`** (0,25, 0,5…) : compromis entre bandes et rognage, mais trois valeurs de plus à parcourir à la télécommande pour un usage rare. Rejeté pour l'instant.
- **`video-aspect-override`** (forcer 16:9, 4:3…) : corrige un fichier aux proportions mal annoncées, besoin différent. Hors de cette décision.
- **Format mémorisé dans Réglages** : il dépend du film, pas d'une préférence ; le retrouver sur le suivant surprendrait. Rejeté, comme pour la vitesse.

## Conséquences
- Le menu ▼ gagne une ligne ; l'aide des commandes cite le format.
- « Remplir l'écran » perd une partie de l'image (les côtés d'un 2,39:1 sur un écran 16:9, environ un quart de la largeur) ; des sous-titres placés près d'un bord de l'image (ASS en particulier) peuvent être rognés avec elle, à vérifier.
- Réversible : retirer la ligne, la pastille, la méthode et le fichier ne touche à rien d'autre ; rien n'est stocké.

## Vérification
- Tests unitaires `VideoFitTest` (ordre des formats, noms des propriétés, libellés et pastilles, pas bloqués aux deux bouts et OK qui revient à Original, propriétés exactes de chaque format, relecture sous la forme donnée par mpv, valeurs illisibles et `panscan` partiel). `testDebugUnitTest` (359 tests), `assembleDebug` et `lintDebug` passent, sans avertissement lint dans les fichiers modifiés.
- Constaté sur la documentation et le code source de mpv seulement (branche `master`), pas sur la version exacte embarquée (release mpv-android `2026-09-17`).
- **NON VÉRIFIÉ sur le Shield** :
  - que `panscan` et `keepaspect` changés en cours de lecture s'appliquent à chaud avec `vo=gpu` et `gpu-context=android`, sans saccade ni écran noir, et la forme exacte des valeurs relues (`1.000000`, `yes`) ;
  - le rendu sur un 2,39:1 et un 4:3, avec décodage `mediacodec` comme `mediacodec-copy` ;
  - la place des sous-titres texte et ASS en « Remplir l'écran » et « Étirer » ;
  - le comportement des touches et du focus sur la ligne, la pastille, et le retour à Original à l'épisode suivant.
