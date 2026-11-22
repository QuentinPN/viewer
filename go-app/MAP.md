# Carte de la migration Go/Wails (ADR 0128)

Règles : `.claude/skills/go-wails-anything/`. Une unité est prise par un seul agent à la fois.

**Commandes non portées : 80 / 80**

## Vagues
| Vague | Unité | Fichiers Rust | Statut |
|---|---|---|---|
| 0 | Spike lecture | spike.rs (abandonné), video_surface.rs (mode parent) | porté (spike, `go-app/cmd/spike`) |
| 1 | emby-models | emby/mod.rs (EmbyError), models.rs, titles.rs, quality.rs, chapters.rs, images.rs, server_url.rs | porté (`internal/emby`, sans commande : voir « Unité emby-models ») |
| 2 | emby-tracks | emby/tracks.rs (dépend de playback) | porté (`internal/emby/tracks.go`, sans commande : voir « Unité emby-playback et emby-tracks »), wave2B |
| 1 | credentials | emby/credentials.rs | porté (`internal/emby/credentials*.go`, sans commande : voir « Unité emby-client et credentials »), wave2A |
| 2 | emby-client | emby/client.rs (tls_roots.rs : unité tls-roots) | porté (`internal/emby/client.go`, `decode.go`, sans commande), wave2A |
| 2 | emby-playback | emby/playback.rs | porté (`internal/emby/playback.go`, types de `internal/player`, sans commande), wave2B |
| 2 | artwork-cache | emby/artwork_cache.rs | porté (`internal/artworkcache`, sans commande : voir « Unité artwork-cache et tls-roots »), wave2C |
| 2 | tls-roots | player/tls_roots.rs (sorti d'emby-client) | porté (`internal/tlsroots`, sans commande), wave2C |
| 2 | library-events | emby/library_events.rs | à faire |
| 3 | player | player/mpv.rs, mod.rs, ffi.rs, video_surface.rs | à faire |
| 3 | app-shell | lib.rs, commands.rs (commandes des vagues 1-3), fenêtre / barre de titre | à faire |
| 4 | session | emby/session.rs, reporting.rs | à faire |
| 4 | segments | segments/* | à faire |
| 5 | sync | sync/* | à faire |
| 6 | request | request/* | à faire |
| 7 | diagnostics | diagnostics.rs, system_info.rs | à faire |
| 7 | release | updater.rs, installeur NSIS, release.yml, publish.ts | à faire |

## Golden à surveiller
- `playback.*` `playback_start` et `engine_request` sont des miroirs faits à la main de `emby_play` (golden.rs). Ils doivent rester alignés sur commands.rs.
- Les messages d'erreur issus des crates `url` et `serde` se comparent sur `kind` seulement (voir index.json).

## Unité emby-models (vague 1, `internal/emby`)
- **Tests Rust portés : 26 / 26, sous le même nom (`Test_<nom>`).** Ils viennent de titles.rs (10), quality.rs (5), chapters.rs (4), server_url.rs (3), images.rs (2) et tests.rs (2 tests sans réseau : `errors_serialize_with_kind_and_french_message`, `options_from_the_ui_default_and_parse`). Le paquet compte 62 tests Go de premier niveau.
- **Goldens produits par du code Go** (fixtures `testdata/fixtures/` extraites de tests.rs par script) :
  - chapters.* (3/3) ;
  - images.* (2/2) ;
  - server_url.normalize (1/1) ;
  - quality.* (4/4, dont les deux `emby_playback_quality` via `QualityStore` + `NewPlaybackQuality`) ;
  - titles.params, similar_params, collection_refs, order et person_id (5/9) ;
  - titles.emby_titles.* (3/9 de plus) : l'ordre des ids et les posters sont calculés en Go ; la conversion en MediaItem est dans session.rs ;
  - errors.emby_error.all_kinds, ainsi que service.invalid_url, insecure_remote_url et quality_not_offered ;
  - models.emby_restore.* (4) : construits avec les constructeurs Go.
- **Goldens vérifiés en aller-retour de forme seulement** (le golden est décodé sans clé inconnue, puis réécrit dans le même ordre) :
  - models.* (39/43), titles.emby_local_trailer ;
  - 11 errors.service.* et errors.playback.*, avec la variante construite à la main ; errors.service.invalid_response est comparé sur `kind` seulement. Avec les 4 produits plus haut, cela fait les 16 goldens EmbyError ;
  - en plus : playback.emby_play.* (8), device_profile et playbackinfo_request (2), events.playback_stopped.* (2), dont les types sont dans models.rs.
  Les valeurs elles-mêmes viennent du code de session.rs, client.rs et playback.rs (vagues 2 et 4).
- **Les 16 goldens EmbyError passent aussi par `bridge.MarshalError`** (`bridge_shape_test.go`), invalid_response sur `kind` seulement.
- Tests Go écrits en même temps que le code : l'échec préalable des tests n'a pas été observé.
- **Différés à la vague 2.** Ces tests de tests.rs touchent cette unité mais demandent le faux serveur :
  - `libraries_keep_films_and_series_with_local_image_urls` ;
  - `items_send_the_chosen_sort_and_filters`, `items_refuse_a_genre_the_server_cannot_filter_on`, `items_refuse_a_decade_the_server_cannot_filter_on` ;
  - `sort_names_follow_the_grid_query_without_paging_or_images`, `random_item_is_drawn_by_the_server_with_the_grid_filters` ;
  - `person_titles_read_with_person_ids_newest_first`, `similar_collection_and_trailer_of_a_movie` ;
  - `chapters_come_from_the_item_in_seconds` : il vérifie aussi le texte littéral `0.0`, que Go écrit `0` ;
  - `playback_quality_caps_the_bitrate_of_a_local_address`, `playback_quality_yields_to_the_debug_override` ;
  - `artwork_is_fetched_with_auth_and_checked`, `malformed_or_foreign_server_responses_are_rejected`.
  Également différés : `From<reqwest::Error> for EmbyError` (vague 2, client) et `date_played_is_utc_yyyymmddhhmmss` (client.rs).
- **Écarts assumés** (`research/server-url.md`) :
  - la règle du sigma final de `to_lowercase` n'est pas reproduite dans le tri des titres (seul `İ` est traité) ;
  - le texte de l'erreur d'E/S de `playback-quality.json` est celui de l'OS vu par Go.

## Unité artwork-cache et tls-roots (vague 2, wave2C)
- **Tests Rust portés : 10 / 10** sous le même nom (`Test_<nom>`) : 6 de artwork_cache.rs et 4 de tls_roots.rs (dont 2 `cfg(windows)`, exécutés ici). Plus des tests de simultanéité (`Test_concurrent_*`), de disposition des fichiers et de forme JSON (`entries`, `bytes`, `quotaBytes`, `hits`, `misses`). `-race` demande cgo sous Windows : non lancé, les tests de simultanéité tournent sans.
- **Disposition sur disque identique** : `{root}/{serverId}/{itemId}/{Type}-{width}-{tag}.img`, temporaires `.{32 hex}.tmp`. Un dossier de cache écrit par Rust reste valable.
- **Écart** : sous Windows, `os.Open` de Go n'a pas `FILE_SHARE_DELETE`, au contraire de la std Rust. `Get` lit donc par `CreateFile` avec ce partage (`readfile_windows.go`), sinon une lecture bloquerait le renommage ou l'éviction du même fichier.
- **Pas de golden** pour ces deux unités (aucune entrée dans `testdata/golden`).
- **tls-roots** : `x/sys/windows` + `crypt32.dll`, pas `crypto/x509` (qui ne sait pas énumérer les racines sous Windows) : voir `research/tls-roots.md`.
- Commandes `artwork_cache_stats` / `artwork_cache_clear` (commands.rs) : à brancher avec la session (vague 4, `Stats()` et `Clear()` renvoient déjà la forme JSON) ; le décompte de commandes non portées ne change pas.

## Unité emby-playback et emby-tracks (vague 2, wave2B)
- **Fichiers.**
  - `internal/emby/playback.go` : `DeviceProfileFor`, `Plan`, `StreamURL`, `MaxStreamingBitrate`, et deux fonctions pures, `NewPlaybackStart` et `EngineRequest`.
  - `internal/emby/tracks.go` : `TrackPreferences` (avec un `UnmarshalJSON` strict), `Choose`, `Choice.Selection`, `Apply` et `AccountTracksOf`.
  - Nouveau paquet `internal/player/types.go`, que la vague 3 complétera : `PlaybackRequest`, `TrackSelection`, `TrackPick`, `ExternalSubtitle`, `SubtitleStyle` et ses quatre enums, `Track`, `TrackKind`, `PlayerDiagnostics`, plus `player.List` (un double d'`emby.List`, car emby importe player).
  - Note de recherche : `research/stream-url.md`.
- **`NewPlaybackStart` et `EngineRequest`.** Ce sont les miroirs de `playback_start` et `engine_request` de golden.rs, donc de la fin de `emby_play` (commands.rs). L'app-shell (vague 3) les appellera.
- **Écart avec la consigne « seulement ces types ».** `Track`, `TrackKind` et `PlayerDiagnostics` sont portés en plus, pour produire les goldens `player.*`. Leur forme est en sérialisation seule, car le moteur les construit. Les types venus de l'UI ou du contrat moteur ont un décodeur strict.
- **Tests Rust portés : 18 / 18, sous le même nom.** playback.rs en a 10 et tracks.rs 8. S'y ajoutent 20 tests Go (`Test_go_*`) pour les branches que Rust ne teste pas : URL de transcodage distincte, `RunTimeTicks` ≤ 0, `ErrorCode` inconnu ou vide, `DeliveryUrl` hors serveur, chaque libellé de raison, ordre et encodage de la requête, ordre du choix audio, rang des sous-titres, `Apply` sur un transcodage, décodage strict, langues du compte.
- **Rouge observé par mutation.** Les tests ont été écrits avec le code. 12 mutations des branches de décision ont été essayées et 9 font échouer de 1 à 17 tests. Les 3 qui passent sont la comparaison du schéma, celle de l'hôte et le refus de `\`. Elles n'ont aucun effet observable, en Rust non plus (voir la note de recherche).
- **Goldens produits par du code Go : 41 / 41 `playback.*`, 3 / 3 `tracks.*`, 4 / 4 `player.*`.** Le détail des 41 : 15 purs, 24 dont les champs de session sont fixés à la main, 2 produits par wave2A.
  - device_profile, plan_fn.* (13) et stream_url viennent des fonctions pures, appelées sur les fixtures des tests Rust.
  - plan.*, emby_play.* et engine_request.* (8 chacun) passent par `Plan` + `Apply` sur la réponse PlaybackInfo que le faux serveur donne pour chaque cas. Ces réponses sont copiées de tests.rs dans `testdata/fixtures/playback_*.json`, avec le jeton écrit `TOKEN_PLACEHOLDER`. Pour transcode_english, c'est la 2e négociation, avec `AudioStreamIndex=2`.
  - **Champs fixés à la main**, lus dans le golden plan.* parce qu'ils viennent de `prepare_with` (session.rs, vague 4) : `title`, `bitrateLimit`, `startTicks`, la raison forcée « échec de la lecture directe » du repli, et l'en-tête `X-Emby-Token` ajouté à `headers`.
- **`playback.playbackinfo_request.*` (2)** : produits par wave2A (`TestGolden_playback_playbackinfo_request`, client_test.go), avec `DeviceProfileFor`.
- **`AccountTracksOf(json.RawMessage)`** prend le `Configuration` du DTO tel quel. Un nil, un null ou un texte qui n'est pas du JSON ne donne rien.
- **Couverture** (`go test -cover`) : toutes les fonctions de playback.go et tracks.go sont à 100 %, sauf deux. `StreamURL` (93 %) : l'échec de `parseURL` sur `server.API(…)` est inatteignable avec un `ServerURL` valide. `subtitleRank` (92 %) : un des cas de l'ordre par drapeau par défaut n'est pas couvert.
- **Différés.**
  - Tests de tests.rs qui passent par le service (`prepare_playback` / `prepare_source`), donc par session.rs (vague 4), même si le faux serveur de wave2A existe :
    - `playback_negotiates_direct_play_with_the_token_in_a_header_only`, `playback_of_one_version_names_it_to_the_server_and_in_reports`, `playback_transcodes_through_hls_under_the_bitrate_limit` ;
    - `track_preferences_pick_the_audio_of_a_transcode_and_its_subtitle_file`, `track_preferences_select_streams_of_the_file_when_playing_it` ;
    - `playback_fallback_refuses_direct_play_and_names_the_reason`, `playback_fallback_does_not_loop_when_the_server_still_offers_the_file` ;
    - `playback_reports_unknown_items_and_bad_ids`, `playback_with_a_revoked_token_ends_the_session` ;
    - `playback_carries_the_title_of_a_film_or_an_episode`, `playback_starts_without_a_title_when_the_item_cannot_be_read` ;
    - et les goldens `errors.playback.*` (3).
  - Les tests player/mod.rs de StallWatch, MediaSource, PlayerCommand et VideoFit vont à la vague 3.
- **Attention.**
  - Les fixtures `playback_*.json` ont été écrites par wave2B, et le faux serveur de wave2A sert aussi `playback_direct` et `playback_transcode`. Elles contiennent `TOKEN_PLACEHOLDER`, pas le `fakeToken` de `fakeserver_test.go`. Un test « le jeton n'est pas dans l'URL » doit donc chercher `TOKEN_PLACEHOLDER`, sinon il passe sans rien vérifier.
  - `client.go` (2A) réutilise `formEncode` de playback.go.
- **Écart assumé.** UTF-8 invalide après décodage d'un paramètre gardé (voir research/stream-url.md).
- Commandes non portées : inchangé. Aucune commande n'est propre à ces unités : `emby_play` est à l'app-shell, `emby_account_tracks` à la session.

## Unité emby-client et credentials (vague 2, wave2A)
- **Fichiers.**
  - `internal/emby/client.go` : `Client` (`EmbyClient`), avec `NewClient`, `NewClientWithTimeouts`, `WithToken` et toutes les méthodes de client.rs sous leur nom PascalCase, `context.Context` en premier argument. S'y ajoutent `AccessToken`, `DeviceIdentity`, `ItemsQuery`/`ItemsShape`, `EpisodesQuery`, `EmbyDate` et `DatePlayedNow`.
  - `internal/emby/decode.go` : décodeur strict des réponses Emby (voir Décisions).
  - `internal/emby/credentials.go` (interface `CredentialStore`, `CredentialAccount`, `MemoryCredentialStore`) et `credentials_windows.go` (`WindowsCredentialStore`, advapi32 par `x/sys/windows`).
  - `internal/emby/fakeserver_test.go` : le faux serveur de tests.rs (`startFakeEmby`, `fakeEmbyLike`, `fakeBrowseLike`, `signedInClient`), à réutiliser par les vagues suivantes. Les 30 réponses fixes sont extraites de tests.rs par script dans `testdata/fixtures/` ; celles à paramètre (`random_answer`, `search_answer`, `playback_two_versions`, `playback_transcode_audio`) sont réécrites en Go.
  - Notes de recherche : `research/http-client.md`, `research/credentials.md`.
- **Compatibilité du coffre : oui, sans reconnexion.** Le format du crate keyring 3.6.3 est lu dans sa source : cible `{serverId}/{userId}.Viewer`, blob UTF-16 LE sans zéro final, `CRED_PERSIST_ENTERPRISE`. Un test relit l'entrée brute écrite par Go et la compare octet par octet. Le croisement avec une entrée écrite par le binaire Rust n'a pas été fait (aucun vrai jeton lu sur cette machine) : à faire au smoke test du propriétaire.
- **Tests Rust : 16 sous le même nom (`Test_<nom>`), dont 15 de tests.rs en moitié client seulement.** Ces tests passent par `EmbyService` (session.rs). Ici, seule leur moitié client est testée : chemin, requête, en-têtes, corps, kind d'erreur, réponse décodée. **La vague 4 portera les vrais tests sous ces mêmes noms, dans ce paquet : elle doit remplacer ces moitiés, pas en ajouter d'autres à côté.**
  - Moitié client de 10 des 13 différés de la vague 1. Ce qui reste au service :
    - `libraries_keep_films_and_series_with_local_image_urls` : le filtre films/séries, les `kind` et les URL `emby-img` ;
    - `items_send_the_chosen_sort_and_filters` : rien, il est entier ;
    - `sort_names_follow_the_grid_query_without_paging_or_images` : le repli sur `Name` et la bibliothèque inconnue ;
    - `random_item_is_drawn_by_the_server_with_the_grid_filters` : la conversion en carte ;
    - `person_titles_read_with_person_ids_newest_first` et `similar_collection_and_trailer_of_a_movie` : les affiches et `item_detail` ;
    - `chapters_come_from_the_item_in_seconds` : le texte `0.0` de serde, que Go écrit `0` ;
    - `playback_quality_caps_the_bitrate_of_a_local_address` : `streaming_limit` et le `QualityStore` (ici, seulement le corps PlaybackInfo pour un débit donné) ;
    - `artwork_is_fetched_with_auth_and_checked` : le refus d'un autre serveur et l'état déconnecté ;
    - `malformed_or_foreign_server_responses_are_rejected` : l'id de serveur différent.
  - **Entièrement différés à la vague 4** : `items_refuse_a_genre_the_server_cannot_filter_on`, `items_refuse_a_decade_the_server_cannot_filter_on` et `playback_quality_yields_to_the_debug_override`. Ils ne testent que le service. Leurs règles sont vérifiées par `TestUnusableGenresAreRefusedByTheRule` et `TestUnusableDecadesSendNoYears`.
  - `date_played_is_utc_yyyymmddhhmmss` : entier.
  - Moitiés client de `sign_in_stores_token_securely_and_sends_auth_in_header`, `wrong_password_is_reported_and_nothing_is_stored`, `unreachable_server_is_reported` et `production_years_are_read_once_without_filters`.
  - `windows_store_round_trip` (credentials.rs) : ignoré par défaut en Rust, exécuté ici sur une entrée jetable.
- **Tests Go en plus : 27, et le test des goldens.** Ils couvrent :
  - `TestEveryEndpointSendsTheRustQuery` : chaque méthode du client contre `fakeBrowseLike`, avec la cible complète dans l'ordre de client.rs, et toutes les réponses fixes passées par le décodeur strict ;
  - les en-têtes `User-Agent`, `Accept`, l'absence d'`Accept-Encoding`, et `Content-Type` envoyé seulement avec un corps (`fakeRequest.Header`) ;
  - le format keyring octet par octet, les blobs refusés, les limites de longueur ;
  - les statuts HTTP, la redirection non suivie, les délais, l'annulation, la connexion coupée sans renvoi ;
  - l'encodage `form_urlencoded` (`*` et `~`) et les corps JSON ;
  - le jeton jamais affiché (`%v`, `%#v`, slog, JSON) et l'URL du socket ;
  - le décodage strict.
- **Rouge observé par mutation.** Les tests ont été écrits avec le code. 4 mutations ont été essayées, et toutes font échouer les tests :
  - `Persist` 2 au lieu de 3 : `TestWindowsStoreWritesTheKeyringFormat` échoue ;
  - cible `{service}.{compte}` : 2 tests du coffre échouent ;
  - contrôle `emby:"required"` coupé : `TestAnswersAreDecodedWithSerdeRules` échoue ;
  - `forceTranscode` inversé dans le golden : `TestGolden_playback_playbackinfo_request` échoue.
- **Goldens produits par du code Go : `playback.playbackinfo_request.*` (2/2).** Le corps est envoyé par `Client.PlaybackInfo` et relu par le faux serveur ; le profil vient de `DeviceProfileFor` (wave2B). Le test de forme seule de models_test.go est retiré. Les autres goldens dépendent de session.rs (vague 4).
- **Pas de nouvelle tentative ni de backoff** : client.rs n'en a pas, le client Go non plus.
- **Modifié hors de mes fichiers.**
  - `models.go` : la balise `emby:"required"` sur les 13 champs requis de serde, et `emby:"option"` sur `RequiredHttpHeaders`, un `Option<HashMap>` en Rust. Un script a comparé, champ par champ, les `Option` de models.rs, playback.rs et tracks.rs aux types Go, et c'est le seul écart trouvé.
  - `models_test.go` perd le test de forme de `playbackinfo_request` (remplacé, voir plus haut).
- **Écart ouvert : le proxy système.** `tauri-plugin-updater` active `system-proxy` de reqwest par unification des fonctions. Rust lit donc aussi le proxy du registre Windows ; Go ne lit que l'environnement (`research/http-client.md`).
- Commandes non portées : inchangé, car client et coffre n'ont pas de commande propre.

## Bugs relevés
- emby/server_url.rs (Rust, non corrigé, porté tel quel) : seul le premier `/web` du chemin est examiné. `http://h/website/web` garde donc `/website/web`, au lieu de s'arrêter au `/web` final. Impact faible : cela ne vise que des adresses copiées depuis le navigateur.
- emby/artwork_cache.rs (Rust, non corrigé, porté tel quel) : l'éviction (`remove_empty_dirs`) peut supprimer le dossier qu'un `put` vient de créer, entre `create_dir_all` et l'écriture : ce `put` échoue (`NotFound`) sans autre effet. Les lecteurs d'une image servie depuis le réseau ignorent l'erreur. Impact faible ; une relance unique dans `put` le corrigerait.
- emby/client.rs (Rust, non corrigé, porté tel quel) : `image()` ne demande pas de jeton (pas de `require_token`). Seul le service vérifie la session avant l'appel. Sans effet aujourd'hui : un client sans jeton envoie l'en-tête sans `Token` et Emby répond 401.

## Décisions
- 2026-11-22 : Wails v3.1.0, sans cgo, chargement de libmpv par syscall (spike validé).
- 2026-11-22 : squelette `cmd/viewer` + `internal/bridge`. Une seule méthode liée, `Bridge.Invoke(cmd, args)` (FQN `viewer/internal/bridge.Bridge.Invoke`), qui distribue par nom de commande Tauri ; chaque vague enregistre ses commandes dans `cmd/viewer/commands.go`. Forme d'erreur : `bridge.MarshalError` (option `MarshalError` du service) donne la valeur que Tauri rejette. Une erreur qui implémente `json.Marshaler` est sérialisée telle quelle (`{kind, message}` pour EmbyError, `null` pour `bridge.ErrUnit`), toute autre erreur devient une chaîne nue (`Result<_, String>`). Wails la place dans `cause` du `RuntimeError` JS, et `src/lib/native/wails.ts` la relance seule. Commande inconnue : `{kind: "unknownCommand", message}`. Les erreurs de domaine doivent donc implémenter `MarshalJSON`.
- 2026-11-22 : opérations de fenêtre côté JS (runtime Wails `Window`, événement `common:WindowDidResize`), sans commande Go. Front embarqué depuis `cmd/viewer/frontend/dist` (`npm run build:wails`, mode Vite `wails`) ; `frontend/.gitkeep` est versionné pour que `go:embed all:frontend` compile sur un clone neuf (Vite vide `dist` à chaque build, donc pas de `.gitkeep` dedans).
- 2026-11-22 (emby-models) : découpage de MAP.md revu. L'unité emby-models de la vague 1 prend aussi server_url.rs et l'EmbyError de mod.rs. tracks.rs, qui dépend de playback, devient l'unité emby-tracks de la vague 2.
- 2026-11-22 (emby-models) : la sérialisation se fait avec encoding/json v1, l'encodeur de Wails v3.1.0 (`pkg/application/bindings.go`). Conséquences pour le code :
  - un `Vec` envoyé à l'UI est un `emby.List[T]`, qui s'écrit `[]` même nil. Les commandes qui renvoient `Vec<…>` doivent renvoyer `List[…]` ;
  - un `Option` sans skip est un pointeur sans `omitempty`, donc écrit `null` ;
  - `skip_serializing_if = "Option::is_none"` devient `omitzero` sur un pointeur ;
  - un enum à variantes unité devient une chaîne typée, ou un int avec `UnmarshalJSON` strict (`SortKey`, `WatchFilter`) ;
  - un enum taggé (`SessionStatus`) a un `MarshalJSON` à la main.
- 2026-11-22 (emby-models) : comparaison des goldens. Pour un golden typé, les jetons sont comparés dans l'ordre et les nombres par valeur, car serde écrit `0.0` là où Go écrit `0` : l'égalité octet par octet est impossible sans encodeur de flottants maison. Les tables `json!` sont comparées sans tenir compte de l'ordre.
- 2026-11-22 (emby-models) : `EmbyError` est un type valeur `{Kind, Detail, Status}`.
  - `MarshalJSON` a un récepteur valeur et produit `{kind, message}`.
  - `errors.Is` compare le kind. Les variantes sans charge sont des valeurs `Err…`, les autres se construisent avec `New…(detail)`.
  - Les fonctions renvoient `error`.
- 2026-11-22 (emby-models) : décodage des réponses Emby, à décider en vague 2. Les DTO portent des tags PascalCase, mais encoding/json v1 ignore la casse des clés et lit null comme valeur zéro. serde, lui, respecte la casse, refuse null pour un champ non-Option et refuse un champ requis absent (`Id`, `PublicSystemInfo`…). `encoding/json/v2` est stable dans Go 1.27 et respecte la casse par défaut. En attendant, les entrées venues de l'UI (`ItemsOptions`, `TitlesSource`, `QualityCaps`) ont un `UnmarshalJSON` strict, conforme à serde.
- 2026-11-22 (emby-models) : dépendance `golang.org/x/net` (paquet `idna`) ajoutée, justifiée : la bibliothèque standard n'a pas d'UTS 46, et sans elle un hôte comme `émby.local` serait refusé alors que le crate `url` le convertit en punycode.
- 2026-11-22 (wave2A) : décodage des réponses Emby. `decode.go` écrit un décodeur strict par réflexion, qui suit les règles de serde :
  - la casse des clés est exacte, les clés inconnues sont ignorées et une clé en double est refusée ;
  - un champ requis absent est refusé (balise `emby:"required"` : un champ non `Option` sans `#[serde(default)]`, listé par script dans models.rs) ;
  - null est refusé, sauf pour un pointeur, un `json.RawMessage` ou un champ balisé `emby:"option"` (un `Option<Vec>` ou `Option<HashMap>` gardé en slice ou map) ;
  - aucune donnée n'est acceptée après la valeur.
  Les DTO des vagues suivantes doivent porter la même balise. encoding/json/v2 a été écarté : il lit null comme zéro et n'a pas de champ requis.
- 2026-11-22 (wave2A) : `context.Context` sur chaque appel du client. Un contexte annulé par l'appelant donne `cancelled`, un délai dépassé `timeout`. Toute autre erreur d'envoi donne `unreachable`, avec le texte de la cause la plus profonde, jamais l'URL. Une erreur de lecture du corps donne `invalidResponse("JSON illisible")`, comme le `Decode` de reqwest.
- 2026-11-22 (wave2A) : le nom du service du coffre (`Viewer`, ou `Viewer-debug` en debug, ADR 0105) est un argument de `NewWindowsCredentialStore`, car Go n'a pas de `cfg!(debug_assertions)`. **À trancher par l'app-shell** : tag de build ou drapeau `-ldflags`.
- 2026-11-22 (wave2A) : `AccessToken` est une struct à champ privé. `String`, `GoString` et `LogValue` sont masqués, et `MarshalJSON` échoue.

## Checklist smoke pour le propriétaire (avant le basculement)
- Clavier réel : Espace, flèches et Échap dans le lecteur, focus après le plein écran.
- Son audible, lecture d'un vrai MKV et d'un vrai flux Emby (avec jeton).
- Échelle d'affichage à 125-150 %, deuxième écran.
- Coffre : après une connexion faite avec l'app Tauri, la build Go doit rouvrir la session sans redemander le mot de passe (même entrée `{serverId}/{userId}.Viewer`).
