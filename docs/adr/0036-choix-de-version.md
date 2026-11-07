# ADR 0036 — Choix de la version d'un film ou d'un épisode

Statut : Accepté (2026-11-07). Fonction P1 « multi-version selection » de `PRODUCT.md`.

## Contexte
Emby regroupe plusieurs fichiers d'un même film ou épisode (1080p et 4K, montage cinéma et version longue) en un seul élément dont `MediaSources` liste chaque version. Jusqu'ici l'application jouait toujours la première source renvoyée par PlaybackInfo, et la page de détail affichait toutes les versions dans « Informations techniques » sans permettre d'en choisir une.

Mesures sur le serveur de test (Emby 4.10.2.0, lecture seule, compte de test) :
- **Aucun élément à plusieurs versions** : 11 954 films et épisodes parcourus (`/Users/{Id}/Items?Recursive=true&IncludeItemTypes=Movie,Episode&Fields=MediaSources`), aucun n'a plus d'une `MediaSource`.
- Le schéma `openapi.json` du serveur donne un champ `MediaSourceId` dans le corps `PlaybackInfoRequest` de `POST /Items/{Id}/PlaybackInfo`.
- Sur le film 30207 (une seule source, `mediasource_30207`) : sans `MediaSourceId`, la source est renvoyée ; avec son id, la même source ; avec un id inconnu, HTTP 200, `MediaSources` vide et `ErrorCode: NoCompatibleStream`.

## Décision
- **Le choix part de la page de détail** (film et épisode), seulement quand l'élément a au moins deux versions avec un id. Un sélecteur natif « Version » sous les boutons de lecture ; chaque libellé donne le `Name` du serveur (sinon « Version N »), puis résolution, codec et profondeur, HDR (SDR omis), taille ou à défaut débit : « 2160p — 4K · HEVC 10 bits · HDR 10 · 27,9 Go ». Dans « Informations techniques », la version choisie porte l'étiquette « choisie ».
- **Le serveur décide toujours** (ADR 0007) : `emby_play` reçoit un `media_source_id` optionnel, envoyé tel quel dans le corps PlaybackInfo (`MediaSourceId`, jamais dans l'URL). Le plan ne retient que la source de cet id ; si le serveur ne la renvoie pas, la lecture échoue en `NoCompatibleStream` au lieu de jouer une autre version. Sans choix, rien n'est envoyé et le comportement reste celui d'avant.
- **Id validé en Rust** avant tout envoi : non vide, 128 caractères au plus, ASCII alphanumérique, `-` ou `_` ; sinon « version invalide ». Les ids hors de cette forme ne sont pas proposés à l'interface (`MediaInfo.id` vide).
- **Les rapports nomment la source jouée** : `ActivePlayback` prend `plan.media_source_id`, donc start, progress et stopped portent la version choisie. Le repli en transcodage et « Reprendre » après une lecture interrompue gardent la même version.
- **Mémoire locale par élément** : `localStorage` de la WebView, clé `viewer.version.v1:{serveur}:{utilisateur}:{élément}`, des ids seulement. La version par défaut (la première) n'est pas stockée ; un id que le serveur ne propose plus est ignoré.

## Alternatives
- **`MediaSourceId` en paramètre d'URL** : non mesuré ici ; le corps est déjà la requête typée, son champ est documenté par le schéma du serveur et garde l'URL propre. Rejeté.
- **Choisir la source côté client dans la réponse sans rien demander au serveur** : le serveur négocierait alors la première version (transcodage, sous-titres, `PlaySessionId`) et l'application en jouerait une autre. Rejeté.
- **Remplacer par une autre version quand celle demandée manque** : lecture silencieusement différente du choix. Rejeté.
- **Mémoire dans le cache SQLite ou côté serveur** : pas de SQLite de métadonnées à ce jour, et Emby n'a pas de préférence de version par élément. Rejeté.

## Conséquences
- L'accueil, « Reprendre », « À suivre », le héros et l'épisode suivant jouent la version par défaut du serveur : la mémoire ne s'applique que sur la page de détail, où la liste des versions vient d'être lue, ce qui évite d'envoyer un id périmé.
- La forme des ids d'une version fusionnée (`mediasource_…` ou un autre id) est supposée d'après la source unique mesurée ; la validation accepte les deux formes connues.
- Les signatures `EmbyClient::playback_info`, `EmbyService::negotiate` et `playback::plan` gagnent un paramètre ; `prepare_playback` et `prepare_transcode` restent inchangés.

## Validation
- Rust, serveur factice (`src-tauri/src/emby/tests.rs`, élément 30500 à deux versions plus un id invalide) : ids lus dans le détail, pas de `MediaSourceId` sans choix, `MediaSourceId` dans le corps, rapports sur la version choisie, repli en transcodage avec le même id, id inconnu → `NoCompatibleStream`, id invalide jamais envoyé. `playback.rs` : la décision du serveur pour la version demandée est suivie.
- Vitest (`src/lib/detail/versions.test.ts`) : libellés, choix seulement à partir de deux versions, mémoire par serveur, utilisateur et élément, id périmé, erreurs de stockage.
- **Non vérifié** : aucun scénario e2e ni essai réel, faute d'élément à plusieurs versions sur le serveur de test.
