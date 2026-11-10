# ADR 0072 — Vitesse de lecture et mode nuit dans le lecteur (Android TV)

Statut : Accepté (2026-11-10). S'ajoute au menu ▼ des pistes (ADR 0042, 0062) ; « Informations de lecture » (ADR 0061) reste la dernière ligne. Le mode nuit rejoint les Réglages (ADR 0051).

## Contexte
Deux demandes du propriétaire pour la TV : accélérer un documentaire ou ralentir une scène, et regarder le soir à faible volume sans que les explosions réveillent la maison ni que les dialogues deviennent inaudibles.

Ce que fournit la libmpv embarquée (release mpv-android `2026-09-17`, ADR 0038), constaté en lisant les chaînes des `.so` arm64 (pas en exécutant) :
- `libmpv.so` contient `audio-pitch-correction` et le filtre interne `scaletempo2` : à une vitesse ≠ 1, mpv insère `scaletempo2` (option activée par défaut) pour garder la hauteur des voix ;
- `libavfilter.so` (4,3 Mo, jeu de filtres complet) contient `acompressor`, `dynaudnorm`, `speechnorm`, `loudnorm` et `compand` : `af=lavfi=[…]` a donc de quoi s'appuyer ;
- `libmpv.so` connaît `audio-spdif` et les formats `spdif-ac3`, `spdif-eac3`, `spdif-dtshd`, `spdif-truehd`. L'application ne pose pas `audio-spdif` : aujourd'hui l'audio est toujours décodé en PCM (sortie `audiotrack`), le passthrough n'arrive pas en pratique.

Le pont JNI (`MPVLib.setPropertyString`) ne rend pas le code d'erreur de mpv : seule une relecture de la propriété dit si mpv a pris la valeur.

## Décision
- **Deux lignes dans le menu ▼**, sous un titre « Lecture », après « Synchronisation » et avant « Informations de lecture » :
  - « Vitesse » : 0,75×, Normale (1×), 1,25×, 1,5×, 2×. ◀ plus lent, ▶ plus rapide, bloqué aux deux bouts ; OK revient à Normale. Mêmes touches que les lignes de décalage (ADR 0062), répétition comprise. Aide : « ◀ ▶ change · OK revient à Normale ».
  - « Mode nuit » : OK active ou désactive ; valeur « Activé » / « Désactivé », avec une ligne d'état dessous.
- **Vitesse** : propriété mpv `speed` (double), posée par `PlayerEngine.setSpeed` puis relue ; la ligne montre la valeur relue, ramenée à la vitesse proposée la plus proche. **Lecture en cours seulement** : `loadNow` remet `speed` à 1 avec `aid`, `sid` et les décalages, et `PlayerUi.reset()` remet l'affichage à Normale (épisode suivant compris). Hors 1×, une pastille « 1,5× » s'affiche à côté du temps dans les commandes. `audio-pitch-correction=yes` est posé explicitement dans les options d'init (c'est la valeur par défaut de mpv, rendue visible) : la hauteur des voix ne change pas.
- **Mode nuit** : propriété mpv `af`, posée par `PlayerEngine.setNightMode` à
  `@night:lavfi=[acompressor=threshold=0.063:ratio=4:attack=20:release=250:makeup=2]`, ou vidée à l'arrêt. Au-dessus d'environ −24 dBFS (0,063 en linéaire), compression 4:1 ; le gain de rattrapage ×2 (≈ +6 dB) relève les passages calmes. Un pic à 0 dBFS ressort vers −12 dBFS, une voix sous le seuil gagne 6 dB : l'écart se réduit d'environ 18 dB, sans écrêtage puisque le gain net au-dessus du seuil est négatif. Valeurs linéaires, sans suffixe « dB », pour ne pas dépendre de l'analyse des unités par FFmpeg. `af` n'a pas d'autre filtre dans l'application : on peut le remplacer entièrement.
  - **Persistant** : `PlaybackSettings.nightMode` (défaut : désactivé), clé `audio.nightMode` par utilisateur Emby, à côté des autres réglages. Ligne « Mode nuit » dans Réglages, section « Qualité de lecture ».
  - **À chaque élément**, avant `loadfile`, le lecteur pose `af` selon le réglage : la chaîne audio du fichier démarre avec.
  - **Pendant la lecture**, OK enregistre le choix tout de suite, puis pose `af` et le relit. Si le compresseur y est (ou n'y est plus, à l'arrêt), l'état reste normal (« Réduit l'écart entre dialogues et explosions »). Sinon : « Enregistré : s'appliquera à la prochaine lecture ».
  - **Passthrough** : si `audio-params/format` ou `audio-out-params/format` commence par `spdif`, l'audio part non décodé vers l'ampli et aucun filtre ne peut le toucher. La ligne l'indique (« Indisponible : le son part tel quel vers l'ampli ») dès l'ouverture du menu ; OK enregistre alors le choix sans toucher à mpv.
- **Frontière** : l'interface passe uniquement par `PlayerEngine` (`setSpeed`, `setNightMode`, `audioPassthrough`) ; aucune propriété mpv n'est posée depuis l'UI.
- **Logique pure** dans `player/AudioModes.kt` (vitesses, pas, libellés français, chaîne de filtre, lecture de `af`, détection `spdif`), testée par `AudioModesTest` ; persistance testée par `PlaybackSettingsTest`.

## Alternatives
- **`dynaudnorm`** : normalisation dynamique efficace, mais à large anticipation (fenêtre de 500 ms × 31 par défaut) : retard audio que mpv ne compense pas forcément, et coût CPU plus élevé. Rejeté au profit d'`acompressor`, sans anticipation.
- **`loudnorm`** : vise une intensité EBU R128 ; en temps réel, il passe en mode dynamique à fort rééchantillonnage interne. Trop lourd pour l'usage.
- **`speechnorm`** : pensé pour la parole seule ; sur un film, il pomperait la musique. Rejeté.
- **`audio-normalize-downmix=yes`** : évite l'écrêtage au mixage 5.1 → stéréo mais baisse tout, voix comprises (le canal central perd du niveau). Laissé à la valeur par défaut (`no`) ; à reconsidérer si un test montre de l'écrêtage en mode nuit sur du 5.1.
- **`af add` / `af remove @night`** : équivalent, mais deux commandes et un retrait qui peut échouer sans filtre présent. La propriété entière est plus simple tant que le mode nuit est le seul filtre.
- **Vitesse mémorisée** : changer de vitesse est ponctuel ; la garder pour l'épisode suivant surprendrait. Rejeté.
- **Mode nuit pour la lecture seulement** : le besoin dépend de l'heure, pas du fichier ; le garder évite de le réactiver à chaque épisode. Rejeté.

## Conséquences
- Le menu ▼ gagne un titre et deux lignes ; le reste ne change pas.
- À une vitesse ≠ 1, mpv rééchantillonne l'audio (`scaletempo2`) ; en passthrough (non utilisé aujourd'hui), mpv ne peut pas corriger la hauteur et la vitesse est déconseillée.
- Le mode nuit traite l'audio après décodage : un léger coût CPU, aucun effet en passthrough.
- Réversible : retirer les lignes, les trois méthodes et la clé de réglage ne touche à rien d'autre ; une clé `audio.nightMode` restée dans les préférences est ignorée.

## Vérification
- Tests unitaires `AudioModesTest` (liste des vitesses, pas et bornes, relecture inexacte ramenée à la vitesse proposée, valeurs invalides, libellés « Normale », « 0,75× », « 1,5× », « 2× », pastille absente à 1×, chaîne exacte du filtre, lecture de `af` dans les deux sens, formats `spdif-*`, textes du mode nuit) et `PlaybackSettingsTest` (mode nuit désactivé par défaut et conservé par `sanitized()`). `testDebugUnitTest`, `assembleDebug` et `lintDebug` passent, sans avertissement lint dans les fichiers modifiés.
- Constaté statiquement seulement : la présence des noms `acompressor`, `scaletempo2`, `audio-pitch-correction` et `spdif-*` dans les `.so` embarqués.
- **NON VÉRIFIÉ sur le Shield** :
  - que mpv-android crée réellement le graphe `lavfi` `acompressor` (aucune exécution sur l'appareil) et que `af` relu contienne bien `acompressor` ;
  - que le changement de `af` en cours de lecture s'applique à chaud, sans coupure audible ni désynchronisation, et la forme exacte de `af` relue ;
  - le rendu sonore : réglages du compresseur (seuil, ratio, gain) jugés à l'oreille, pompage éventuel, écrêtage sur du 5.1 mixé en stéréo ;
  - le coût CPU sur le Shield (Tegra X1), en particulier combiné à `scaletempo2` à 2× ;
  - la hauteur des voix à 0,75× et 2×, la synchronisation des sous-titres ASS à ces vitesses ;
  - la détection du passthrough (jamais déclenchée : `audio-spdif` n'est pas posé) ;
  - le comportement des touches et du focus sur les deux lignes, la pastille de vitesse, la remise à 1× à l'épisode suivant, et la ligne de Réglages.
