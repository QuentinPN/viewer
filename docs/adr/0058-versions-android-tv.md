# ADR 0058 — Choix de la version d'un film (Android TV)

Statut : Accepté (2026-11-09). Reprend sur TV le choix de version de Windows (`src/lib/detail/versions.ts`, `src-tauri/src/emby/playback.rs`, ADR 0036).

## Contexte
Emby regroupe plusieurs fichiers d'un même film (1080p et 4K, montage cinéma et version longue) en un seul élément dont `MediaSources` liste chaque version. Sur TV, la lecture envoyait PlaybackInfo sans rien préciser et jouait la première source de la réponse : une autre version n'était pas accessible.

Windows le permet depuis l'ADR 0036, sur la fiche d'un film et sur celle d'un épisode. Contrat vérifié par Windows sur Emby 4.10.2.0 : le schéma `openapi.json` donne `MediaSourceId` dans le corps `PlaybackInfoRequest` de `POST /Items/{Id}/PlaybackInfo` ; un id connu renvoie cette source, un id inconnu renvoie `MediaSources` vide avec `ErrorCode: NoCompatibleStream`. Le même relevé n'a trouvé **aucun élément à plusieurs versions** sur le serveur de test (11 954 films et épisodes parcourus) ; aucune preuve plus récente dans `docs/evidence` n'en mentionne un.

## Décision
- **Sur la fiche film seulement**, et seulement quand le film a au moins deux versions avec un id utilisable, comme Windows (`versionChoices`, `ui/Versions.kt`). Sinon rien ne s'affiche et rien n'est envoyé : un film à une version se lit exactement comme avant.
- **Bouton « Version : … »** sous « Lire » / « Reprendre » : OK passe à la version suivante puis revient à la première, comme les choix de Réglages (ADR 0051). Pas de liste déroulante sur TV ; avec deux ou trois versions, faire défiler suffit.
- **Mêmes libellés que Windows** : le `Name` du serveur (sinon « Version N », N selon la place dans la liste du serveur), puis résolution, codec et profondeur, HDR (SDR omis, Dolby Vision avec son profil), taille ou à défaut débit : « 2160p — 4K · HEVC 10 bits · HDR 10 · 27,9 Go ». Tailles en unités binaires et nombres à la française, une décimale au plus.
- **Même défaut que Windows** : la version retenue pour ce film si le serveur la propose encore, sinon la première, celle que le serveur prend quand on ne demande rien. La mémoire est locale (`SharedPreferences` `versions`, clé `{utilisateur}.{élément}`, des ids seulement) ; la première version n'est pas stockée, sa clé est retirée.
- **Modèle** : `BaseItemDto` lit `MediaSources` (`MediaSourceDto` : `Id`, `Name`, `Size`, `Bitrate`, `MediaStreams`), déjà renvoyé par `/Users/{Id}/Items/{Id}` que la fiche lit (Gate 2, ADR 0050) : aucune requête de plus. Les flux n'y lisent que `Type`, `Codec`, `Width`, `Height`, `BitDepth`, `VideoRange`, `ExtendedVideoSubTypeDescription`, mêmes champs que `models.rs` ; tous facultatifs, pour qu'une source incomplète ne fasse pas échouer la fiche.
- **Le serveur décide toujours** (ADR 0007) : l'id choisi part dans le corps PlaybackInfo (`MediaSourceId`, jamais dans l'URL) et `plan` ne retient que la source de cet id. Si le serveur ne la renvoie pas, la lecture échoue en « aucun flux compatible » au lieu de jouer une autre version. Les rapports de lecture portent déjà `plan.mediaSourceId`, donc la version jouée.
- **Id validé avant tout envoi**, même règle que `is_source_id` sous Windows : non vide, 128 caractères au plus, ASCII alphanumérique, `-` ou `_`. Un id hors de cette forme n'est pas proposé, et `playbackInfo` le refuse (« version invalide »).
- **Passage au lecteur** : `MainActivity` ajoute l'extra `mediaSourceId` à l'`Intent` de `PlayerActivity` depuis la fiche film ; « Reprendre » et « Lire depuis le début » envoient la même version. L'accueil, la recherche, les cartes d'épisode et l'épisode suivant n'en envoient pas : le serveur choisit, comme avant et comme sous Windows hors fiche.
- **Épisodes reportés** : Windows propose aussi le choix sur la fiche d'un épisode. La TV n'a pas de fiche épisode, seulement des cartes dans la saison ; y ajouter un choix demande une autre interaction (menu par carte) et n'est pas trivial. Laissé hors de ce lot.

## Alternatives
- **Choisir la source dans la réponse sans rien demander au serveur** : le serveur négocierait la première version (transcodage, sous-titres, `PlaySessionId`) et la TV en jouerait une autre. Rejeté, comme sous Windows.
- **Remplacer par une autre version quand celle demandée manque** : lecture silencieusement différente du choix. Rejeté.
- **Liste des versions dans une boîte de dialogue** : plus d'écrans et de focus à gérer pour un cas rare (aucun sur le serveur de test). Le bouton qui fait défiler reprend un geste déjà connu de Réglages.
- **Pas de mémoire** : plus simple, mais la TV ne reprendrait pas le défaut de Windows. Une petite mémoire locale, sur le modèle des autres préférences, suffit.

## Conséquences
- Une ligne de plus sur la fiche d'un film à plusieurs versions ; aucune différence pour les autres.
- La mémoire est propre à l'appareil : un choix fait sous Windows ne suit pas sur la TV, Emby n'ayant pas de préférence de version par élément.
- Les chapitres et repères d'intro restent ceux de l'élément, donc de sa version par défaut (même limite que l'ADR 0034).
- La forme des ids d'une version fusionnée (`mediasource_…` ou un autre id) est supposée d'après la source unique mesurée sous Windows.

## Vérification
- Tests unitaires : libellés, Dolby Vision, tailles et débits, choix seulement à partir de deux versions avec un id valide, version retenue ou première, passage à la suivante, lecture de `MediaSources` dans l'élément (`VersionsTest`) ; `MediaSourceId` dans le corps et absent sans choix, id invalide jamais envoyé (`EmbyClientTest`) ; source demandée jouée, jamais remplacée, forme des ids (`PlaybackPlanTest`). `testDebugUnitTest` (146 tests), `assembleDebug` et `lintDebug` passent, sans avertissement de lint dans les fichiers changés.
- Non vérifié sur le Shield ni sur le serveur : aucun film à plusieurs versions sur le serveur de test, donc ni l'affichage du bouton, ni la lecture d'une seconde version, ni le rapport de lecture avec son id. Non vérifié non plus que `MediaSources` arrive avec `Fields=Overview` (la requête de la fiche TV) ; Gate 2 l'a mesuré sans `Fields`. Un film à une version a été vérifié seulement par les tests : le corps PlaybackInfo n'a pas de `MediaSourceId`, comme avant.
