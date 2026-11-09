# ADR 0055 — Pistes par défaut du serveur (Android TV)

Statut : Accepté (2026-11-09). Complète l'ADR 0042 ; reprend le mode « Comme sur le serveur Emby » de Windows (`src-tauri/src/emby/tracks.rs`, ADR 0035).

## Contexte
Sur TV, sans choix retenu (ADR 0042), les pistes étaient celles de mpv : la première piste audio marquée par défaut dans le fichier, et des sous-titres français par `slang=fr,fre,fra`. Le relevé du Shield (`docs/evidence/tv-client-2026-11-09.md`) l'a montré : mpv a pris « FR Forced », le premier `fre` trouvé, pas la piste choisie par Emby.

Le serveur calcule pourtant ses propres choix à partir des préférences du compte (`AudioLanguagePreference`, `SubtitleLanguagePreference`, `SubtitleMode` de `Configuration`) : `DefaultAudioStreamIndex` et `DefaultSubtitleStreamIndex` de chaque source de PlaybackInfo, vérifiés sur Emby 4.10.1.0 (ADR 0035). L'ADR 0042 avait reporté leur usage.

## Décision
- **Ordre de priorité, pour l'audio et pour les sous-titres séparément** (`decide`, `player/TrackPreferences.kt`) :
  1. le choix retenu (ADR 0042), s'il trouve une piste de même langue (et de même titre si possible) ; « sous-titres désactivés » compte ;
  2. sinon, la piste choisie par le serveur ;
  3. sinon, le choix de mpv, comme avant.
  C'est l'ordre de Windows : une préférence locale l'emporte, et le choix du serveur sert de repli quand elle ne trouve rien. Le choix retenu de la TV n'a pas de mode « forcés uniquement / toujours » : il garde ses règles de l'ADR 0042.
- **Modèle** : `MediaSourceInfo` lit `DefaultAudioStreamIndex`, `MediaStreamDto` lit `IsExternal`, mêmes champs que `models.rs` sous Windows. Aucun autre champ, aucune requête nouvelle.
- **Choix du serveur gardé seulement s'il peut s'afficher** (`serverTracks`, `emby/PlaybackPlan.kt`), même filtre que Windows : en lecture directe ou Direct Stream, un flux du fichier lui-même (`IsExternal` faux) du bon type ; en transcodage, un sous-titre livré en fichier (`DeliveryMethod` `External` avec `DeliveryUrl`). `-1`, l'absence de valeur ou un index inconnu laissent mpv décider : Windows ne lit pas non plus `-1` comme « sous-titres désactivés ».
- **Index Emby → piste mpv**, comme Windows (`track_with_ff_index`, ADR 0035) : la piste mpv du même type, non externe, dont `ff-index` vaut l'`Index` Emby. `readTracks` lit maintenant `ff-index` et `external`. Les fichiers externes ont leur propre numérotation et ne sont jamais pris.
- **Transcodage** : le flux ne porte que l'audio demandé au serveur, rien à choisir. Le sous-titre du serveur est ajouté avec `sub-add … select`, les autres fichiers avec `auto`, comme Windows (ADR 0009). Avant, chaque fichier était ajouté avec `select`, et le dernier restait affiché même si le serveur n'en choisissait aucun.
- **Remise à zéro à chaque fichier** : `aid=auto` et `sid=auto` avant `loadfile`, comme Windows. Sinon, une piste choisie dans l'épisode précédent (par le serveur ou le menu) restait demandée pour le suivant, joué dans le même moteur (ADR 0043).
- **Journal** `ViewerTV`, une ligne par fichier, sans adresse ni jeton :
  `tracks audio=<index Emby|->→<aid mpv> (server|remembered|mpv) sub=<index Emby|->→<sid mpv> (server|remembered|mpv)`, par exemple `tracks audio=1→1 (server) sub=4→2 (server)` ou `tracks audio=-→1 (mpv) sub=-→no (remembered)`. L'id mpv est relu après le choix.
- **Pas de nouveau réglage.** Windows propose un mode de sous-titres local (« Comme sur le serveur Emby », « Désactivés », « Forcés uniquement », « Toujours ») et des langues locales. Sur TV, le choix retenu du menu joue déjà le rôle des préférences locales ; le mode « serveur » devient simplement le repli. Ajouter le mode et les langues serait un plus gros lot (choix des pistes forcées, texte avant image) pour un besoin que le menu couvre.
- **Lecture seule** : rien n'est écrit sur le serveur (`POST /Users/{Id}/Configuration` n'est pas appelé). Les préférences du compte se changent depuis Emby.

## Alternatives
- **Laisser `alang` / `slang`** : mpv ne connaît ni les préférences du compte, ni les pistes forcées choisies par Emby. Gardé seulement comme dernier repli.
- **Envoyer `AudioStreamIndex` / `SubtitleStreamIndex` dans PlaybackInfo** : sans effet en lecture directe, vérifié sous Windows (ADR 0035).
- **Faire correspondre les pistes par langue plutôt que par `ff-index`** : ambigu dès qu'il y a deux pistes de même langue (forcés et complets). Écarté.
- **Lire `-1` comme « désactivés »** : Windows ne le fait pas ; sans vérification sur le serveur de ce que `-1` signifie pour chaque `SubtitleMode`, on garde le même sens.

## Conséquences
- Sans choix retenu, la TV et Windows (mode par défaut) prennent les mêmes pistes pour une même lecture directe.
- Le choix retenu reste propre à l'appareil, et l'emporte toujours sur le compte quand sa langue existe dans le fichier.
- Différence avec Windows : quand le serveur ne nomme aucun sous-titre, mpv décide ; sur TV, `slang=fr,fre,fra` lui fait encore prendre un sous-titre français s'il y en a un, là où Windows n'en prend que s'il est marqué par défaut ou forcé dans le fichier.
- Hypothèse reprise de Windows : l'`Index` Emby d'une piste intégrée vaut son `ff-index` dans mpv. Si la numérotation diffère (autre libmpv, Direct Stream remuxé), le choix de mpv reste pour cette piste et le journal l'écrit `(mpv)`.

## Vérification
- Tests unitaires : choix du serveur gardé ou non selon la méthode, `-1`, absence, index inconnu ou externe (`PlaybackPlanTest`) ; correspondance par `ff-index`, priorité du choix retenu, repli sur le serveur puis mpv, transcodage, ligne de journal (`TrackPreferencesTest`) ; lecture de `ff-index` et `external` (`TracksTest`). `testDebugUnitTest` (136 tests), `assembleDebug` et `lintDebug` passent, sans nouvel avertissement de lint.
- Non vérifié sur le Shield : que la libmpv de mpv-android `2026-09-17` expose `track-list/N/ff-index` et qu'il vaut l'`Index` Emby ; les pistes choisies à l'écran ; `sub-add … auto` en transcodage ; la remise à zéro de `aid` / `sid` entre deux épisodes. Non vérifié non plus sur le serveur : la valeur de `DefaultSubtitleStreamIndex` pour chaque `SubtitleMode` du compte.
