# ADR 0084 — Pistes d'un transcodage (Android TV)

Statut : Accepté (2026-11-10). Corrige la qualité pendant la lecture (ADR 0081) ; reprend sur TV l'`AudioStreamIndex` de Windows (ADR 0035) ; complète les pistes par défaut du serveur (ADR 0055) et le choix retenu (ADR 0042).

## Contexte
Relevé du propriétaire sur le Shield, serveur réel : Overgeared S1E6 en lecture directe, audio japonais (choix retenu), sous-titres désactivés. Dans le menu ▼, « Qualité » à 1,5 Mbit/s : le serveur répond par un transcodage (`quality method=Transcode ... from=DirectPlay newSession=true startInUrl=true`). Après le rechargement, la position est bonne, mais :
1. le menu audio ne propose plus que « Français · AAC stéréo » : un transcodage HLS ne porte qu'une piste audio, celle que la requête PlaybackInfo demande, sinon celle du serveur, et la requête n'en demandait aucune ;
2. les trois sous-titres s'appellent « Stream.ass · ASS (externe) » au lieu de « Français — Forced · ASS », « Français · ASS », « Français — CC · ASS » : les fichiers de sous-titres d'un transcodage sont ajoutés par `sub-add <adresse> select|auto`, sans titre ni langue, et mpv les nomme alors d'après le nom du fichier.

Ce que disent les sources (lues le 2026-11-10) :
- Référence de l'API Emby (`dev.emby.media`, `MediaInfoService`, `POST /Items/{Id}/PlaybackInfo`) : le corps `PlaybackInfoRequest` comprend `AudioStreamIndex` et `SubtitleStreamIndex` (entiers), à côté de `MediaSourceId`, `StartTimeTicks` et `MaxStreamingBitrate`, sans description de leur effet. Dans la réponse, chaque `MediaStream` porte `Index`, `Type`, `Title`, `Language`, `DisplayTitle`, `IsForced`, `IsHearingImpaired`, `IsExternal`, `DeliveryMethod` et `DeliveryUrl`.
- Mesuré sous Windows sur Emby 4.10.1.0 (ADR 0035) : `AudioStreamIndex` est ignoré en lecture directe, et n'est pris en compte en transcodage qu'avec `MediaSourceId` ; il choisit alors l'audio du flux. Les sous-titres texte d'un transcodage arrivent en fichiers (`DeliveryMethod` `External`).
- Mesuré sous Windows sur ce serveur (ADR 0009) : l'incrustation de sous-titres dans l'image échoue (polices introuvables, erreur 500).
- Le client Windows passe déjà le titre et la langue à `sub-add <adresse> <drapeau> <titre> <langue>` (`src-tauri/src/player/mpv.rs`).

## Décision
- **Changement de qualité** : la requête PlaybackInfo porte `AudioStreamIndex`, l'`Index` Emby de l'audio entendu (`playingAudioIndex`, `player/TranscodeAudio.kt`). En lecture directe ou flux direct, c'est la piste audio choisie du fichier, par son `ff-index` (ADR 0055) ; en transcodage, l'audio que le serveur a mis dans le flux (`DefaultAudioStreamIndex` de sa réponse). L'index n'est envoyé que s'il est l'une des pistes audio de la source (non externes), et toujours avec `MediaSourceId`, déjà envoyé (ADR 0081). Lu au moment de OK, sur le fil de l'interface.
- **Première lecture** déjà en transcodage (plafond de Réglages, codec) : comme Windows (ADR 0035), si le choix retenu désigne une autre piste audio de la source que celle du serveur (même langue et même titre, sinon la première de cette langue, comme `decide`), une seconde requête PlaybackInfo est envoyée avec `MediaSourceId` et `AudioStreamIndex` (`rememberedAudioIndex`). Sa réponse n'est gardée que si elle reste un transcodage de la même version (`keepsAudioRequest`) ; un refus ou une autre réponse gardent la première. En lecture directe, rien ne change : le menu choisit parmi les pistes du fichier.
- **`SubtitleStreamIndex` n'est pas envoyé.** Tous les sous-titres texte arrivent de toute façon en fichiers, et le choix retenu (ou « désactivés ») est remis par `applyTracks` après le rechargement. Nommer un sous-titre image demanderait au serveur de l'incruster, ce qui échoue sur ce serveur (ADR 0009).
- **Noms des sous-titres d'un transcodage** tirés des `MediaStreams` du serveur, comme en lecture directe :
  - `PlaybackPlan` passe pour chaque fichier le `Title` et la `Language` du flux ; `subtitleTitle` ajoute « Forcés » quand `IsForced` est vrai et « SDH » quand `IsHearingImpaired` l'est, si le titre ne le dit pas déjà (« Forced », « forcé », « SDH », « CC », « malentendant »). Sans titre ni drapeau, rien : le libellé est la langue seule, comme une piste sans titre du fichier.
  - `addSubtitle` du moteur prend un titre et une langue, envoyés à `sub-add <adresse> <drapeau> <titre> <langue>` comme sous Windows.
  - `readTracks` lit `external-filename` et oublie le titre d'un fichier externe quand il n'est que le nom de ce fichier (« Stream.ass ») : c'est ce que mpv met à défaut de titre.
  - Les libellés deviennent « Français — Forced · ASS (externe) », « Français · ASS (externe) », « Français — CC · ASS (externe) ». « (externe) » reste : le fichier est bien livré à part. Le titre « Forcés » ou « SDH » laisse aussi le choix du compte (ADR 0065) reconnaître ces pistes, `isForced` et `isHearingImpaired` lisant le titre.
- **Secrets** : ni adresse ni jeton dans les journaux ; l'index va dans le corps, le jeton reste dans l'en-tête `X-Emby-Authorization`. L'interface ne construit aucune adresse.
- **Journal** `ViewerTV` :
  - `quality method=… from=… newSession=… startInUrl=… audio=<index demandé|-> answered=<DefaultAudioStreamIndex de la réponse|->` ;
  - à la première lecture, quand une seconde requête part : `remembered audio=<index> server=<index du serveur> kept=<true|false> answered=<index>`, ou `remembered audio=<index> refused: …`.

## Alternatives
- **Choisir l'audio après coup dans le flux HLS** : il n'en porte qu'une. Impossible.
- **Envoyer `AudioStreamIndex` à chaque première requête** : inconnu avant la réponse (les index viennent des `MediaStreams`), et ignoré sans `MediaSourceId` (ADR 0035). La seconde requête n'a lieu que pour un transcodage dont l'audio diffère.
- **Envoyer aussi `SubtitleStreamIndex`** : effet non documenté, risque d'incrustation qui échoue sur ce serveur (ADR 0009). Rejeté.
- **Faire correspondre chaque piste externe à son fichier par `external-filename` et recopier les drapeaux `IsForced` / `IsHearingImpaired` sur la piste** : plus exact pour les drapeaux, mais suppose que mpv-android rende l'adresse telle qu'elle a été donnée, non vérifié. Le titre composé suffit à l'affichage et au choix du compte.
- **Retirer « (externe) »** pour ces pistes : changerait le libellé de toute piste externe ; hors du problème relevé. Reporté.

## Conséquences
- Un changement de qualité vers un transcodage garde l'audio entendu ; le retour en lecture directe le retrouve par le choix retenu, comme avant.
- Une première lecture en transcodage avec un audio retenu différent de celui du serveur coûte une requête PlaybackInfo de plus ; l'adresse de la première réponse n'est jamais ouverte, et un transcodage ne démarre a priori qu'à la lecture de son adresse (non vérifié).
- Les drapeaux `forced` et `hearing-impaired` des pistes externes restent faux côté mpv ; seul le titre les dit.
- Hypothèse reprise de Windows : en réponse à une requête avec `AudioStreamIndex`, le serveur renvoie cet index en `DefaultAudioStreamIndex` (simulé ainsi dans les tests Windows, ADR 0035). Si ce n'est pas le cas, un second changement de qualité depuis un transcodage demanderait l'audio par défaut ; `answered=` dans le journal le dira.
- Réversible : retirer `AudioStreamIndex`, `TranscodeAudio.kt`, `subtitleTitle` et les deux arguments de `addSubtitle` ne touche à rien d'autre.

## Vérification
- Tests unitaires : `EmbyClientTest` sur MockWebServer (`AudioStreamIndex` dans le corps avec `MediaSourceId`, jamais dans l'adresse, jamais sans version ni négatif, absent par défaut, `SubtitleStreamIndex` jamais envoyé, jeton dans l'en-tête seulement) ; `PlaybackPlanTest` (titres et langues des fichiers d'un transcodage tirés des `MediaStreams`, pistes audio de la source sans les externes, `subtitleTitle` selon `Title`, `IsForced` et `IsHearingImpaired`) ; `TracksTest` (« Stream.ass » oublié pour un fichier externe, libellés « Français — Forced · ASS (externe) » et « Français — CC · ASS (externe) », titre gardé pour une piste du fichier) ; `TranscodeAudioTest` (audio entendu en lecture directe, en flux direct et en transcodage, index inconnu, externe ou sans `ff-index` jamais envoyé, choix retenu par langue et titre, rien quand le serveur porte déjà cette piste, seconde réponse gardée seulement en transcodage de la même version). `testDebugUnitTest` (422 tests), `assembleDebug` et `lintDebug` passent, sans avertissement lint dans les fichiers modifiés.
- **NON VÉRIFIÉ sur le Shield et le serveur** :
  - que le transcodage obtenu avec `AudioStreamIndex` porte bien l'audio japonais (menu audio, « Informations de lecture ») ;
  - que `DefaultAudioStreamIndex` de la réponse vaut l'index demandé (`answered=` du journal) ;
  - que mpv-android prend le titre et la langue de `sub-add` et rend `external-filename` ;
  - le libellé réel des trois sous-titres d'Overgeared S1E6, qui dépend du `Title` que le serveur donne à chacun ;
  - la seconde requête d'une première lecture en transcodage (`remembered audio=…`).
