# Carte de la migration Go/Wails (ADR 0128)

Règles : `.claude/skills/go-wails-anything/`. Une unité est prise par un seul agent à la fois.

**Commandes non portées : 0 / 80** (vérifié par `TestRegisteredCommandsAreTheRustInvokeHandler`, qui lit `generate_handler!` dans lib.rs)

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
| 2 | library-events | emby/library_events.rs | porté (`internal/libraryevents`, hors `internal/emby` : voir « Unité app-shell »), app-shell |
| 3 | player | player/mpv.rs, mod.rs, ffi.rs, video_surface.rs (mode parent seul) | porté (`internal/player`, binding `internal/mpv` étendu, sans commande : voir « Unité player »), wave3-player |
| 3 | app-shell | lib.rs, commands.rs (commandes des vagues 1-3), fenêtre / barre de titre | porté (`internal/app`, `cmd/viewer`, 80 commandes ; illustrations servies sur `wails.localhost` : voir « Unité app-shell »), app-shell |
| 4 | session | emby/session.rs, reporting.rs | porté (`internal/emby/session*.go`, `reporting.go`, sans commande : voir « Unité session et segments »), wave4 |
| 4 | segments | segments/* | porté (`internal/segments`, sans commande), wave4 |
| 5 | sync-core | sync/envelope.rs, leader.rs, mod.rs (parties pures : SyncDevice, tv_devices, own_session_id) | porté (`internal/devsync`, sans commande : voir « Unité sync-core »), wave5-sync-core |
| 5 | sync-service | sync/service.rs (attend session et player) | porté (`internal/devsync/service.go`), app-shell |
| 6 | request | request/* (mod, models, client, service, images, trailer, commands) | porté (`internal/request`, 1 commande branchée sur 17 : voir « Unité request »), wave6-request |
| 7 | diagnostics | diagnostics.rs, system_info.rs (`redact` et ses 5 tests : déjà dans `internal/redact`, vague 3) | porté (`internal/diagnostics`, `internal/sysinfo`, 2 commandes non branchées : voir « Unité diagnostics et system-info »), wave7-diag |
| 7 | updater | updater.rs | porté (`internal/updater`, 3 commandes non branchées : voir « Unité updater »), wave7-updater |
| 7 | release-pipeline | installeur NSIS, release.yml, publish.ts (basculement) | à faire (chemin décrit dans « Unité updater », sous-partie release-pipeline) ; l'installeur NSIS est sorti dans l'unité `installer` |
| 7 | installer | `tauri.conf.json` (bundle.nsis), installer.nsi généré par Tauri | porté (non exécuté : smoke propriétaire) (`go-app/build/windows/`, `scripts/build-wails-installer.ps1` : voir « Unité installer »), wave7-installer |

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
  - models.emby_restore.* (4) : construits avec les constructeurs Go. **Fait en vague 4** (voir « Unité session et segments »).
- **Goldens vérifiés en aller-retour de forme seulement** (le golden est décodé sans clé inconnue, puis réécrit dans le même ordre) :
  - models.* (39/43), titles.emby_local_trailer ;
  - 11 errors.service.* et errors.playback.*, avec la variante construite à la main ; errors.service.invalid_response est comparé sur `kind` seulement. Avec les 4 produits plus haut, cela fait les 16 goldens EmbyError ;
  - en plus : playback.emby_play.* (8), device_profile et playbackinfo_request (2), events.playback_stopped.* (2), dont les types sont dans models.rs.
  Les valeurs elles-mêmes viennent du code de session.rs, client.rs et playback.rs (vagues 2 et 4). **Fait en vague 4** (voir « Unité session et segments »).
- **Les 16 goldens EmbyError passent aussi par `bridge.MarshalError`** (`bridge_shape_test.go`), invalid_response sur `kind` seulement.
- Tests Go écrits en même temps que le code : l'échec préalable des tests n'a pas été observé.
- **Différés à la vague 2.** Ces tests de tests.rs touchent cette unité mais demandent le faux serveur : **Fait en vague 4** (voir « Unité session et segments »).
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
- Commandes `artwork_cache_stats` / `artwork_cache_clear` (commands.rs) : à brancher avec la session (vague 4, `Stats()` et `Clear()` renvoient déjà la forme JSON) ; le décompte de commandes non portées ne change pas. Vague 4 : le service ne les porte pas, l'app-shell appelle `Stats` / `Clear` sur le cache.

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
  - **Champs fixés à la main**, lus dans le golden plan.* parce qu'ils viennent de `prepare_with` (session.rs, vague 4) : `title`, `bitrateLimit`, `startTicks`, la raison forcée « échec de la lecture directe » du repli, et l'en-tête `X-Emby-Token` ajouté à `headers`. **Fait en vague 4** (voir « Unité session et segments »).
- **`playback.playbackinfo_request.*` (2)** : produits par wave2A (`TestGolden_playback_playbackinfo_request`, client_test.go), avec `DeviceProfileFor`.
- **`AccountTracksOf(json.RawMessage)`** prend le `Configuration` du DTO tel quel. Un nil, un null ou un texte qui n'est pas du JSON ne donne rien.
- **Couverture** (`go test -cover`) : toutes les fonctions de playback.go et tracks.go sont à 100 %, sauf deux. `StreamURL` (93 %) : l'échec de `parseURL` sur `server.API(…)` est inatteignable avec un `ServerURL` valide. `subtitleRank` (92 %) : un des cas de l'ordre par drapeau par défaut n'est pas couvert.
- **Différés.**
  - Tests de tests.rs qui passent par le service (`prepare_playback` / `prepare_source`), donc par session.rs (vague 4), même si le faux serveur de wave2A existe : **Fait en vague 4** (voir « Unité session et segments »).
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
- **Tests Rust : 16 sous le même nom (`Test_<nom>`), dont 15 de tests.rs en moitié client seulement.** Ces tests passent par `EmbyService` (session.rs). Ici, seule leur moitié client est testée : chemin, requête, en-têtes, corps, kind d'erreur, réponse décodée. **La vague 4 portera les vrais tests sous ces mêmes noms, dans ce paquet : elle doit remplacer ces moitiés, pas en ajouter d'autres à côté.** **Fait en vague 4** (voir « Unité session et segments »).
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
  - **Entièrement différés à la vague 4** : `items_refuse_a_genre_the_server_cannot_filter_on`, `items_refuse_a_decade_the_server_cannot_filter_on` et `playback_quality_yields_to_the_debug_override`. Ils ne testent que le service. Leurs règles sont vérifiées par `TestUnusableGenresAreRefusedByTheRule` et `TestUnusableDecadesSendNoYears`. **Fait en vague 4** (voir « Unité session et segments »).
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

## Unité sync-core (vague 5, wave5-sync-core)
- **Paquet `internal/devsync`**, pas `sync`, pour ne pas masquer le paquet `sync` de la bibliothèque standard. Il importe `internal/emby` (pour `SessionInfoDto` et `List`). Aucun fichier du paquet `emby` ne peut donc importer devsync : ce serait un cycle emby → devsync → emby. **Point ouvert pour l'orchestrateur** : library_events.rs appelle `own_session_id`. L'unité library-events doit donc soit vivre hors de `internal/emby`, soit recevoir `OwnSessionID` par injection. Le choix n'est pas fait ici.
- **Fichiers.**
  - `envelope.go` : `Body` est une interface aux 9 variantes (`Join`, `Load`, `Ready`, `State`, `Cmd`, `Buffering`, `Ping`, `Pong`, `Bye`). S'y ajoutent `Label`, `IsID`, `LinkView`, `Accepts` (le `accept` de Rust) et `Reject`, qui est aussi l'`error` de `Decode`.
  - `wire.go` : `Envelope.Encode` et `Decode`.
  - `leader.go` : `Leader.Handle(now, *Snapshot, Input) []Effect`, plus `Status` et `IsActive`. Les effets sont `Send`, `PlayerAction`, `UIAction`, `Notice` et `StatusChanged` (l'`Effect::Status` de Rust). Le temps et l'id du lien sont injectés comme en Rust.
  - `devices.go` : `SyncDevice`, `TVDevices` (renvoie un `emby.List`, donc `[]` si vide) et `OwnSessionID` (renvoie `(string, bool)`).
- **Format sur le fil, octet par octet comme serde_json 1.0.154.** Ce crate est compilé sans `preserve_order` (pas d'indexmap dans Cargo.lock), sans `arbitrary_precision` et sans `float_roundtrip`.
  - Encodage : les clés sont triées, puisque la `Map` est un BTreeMap. Il n'y a pas d'espace. Les chaînes suivent les échappements de serde_json : `<`, `>`, `&` et U+2028 restent tels quels, au contraire d'encoding/json.
  - **Vérifié par exécution le 2026-11-22.** Un projet cargo jetable (hors ligne, copie d'envelope.rs, `serde_json = "=1.0.154"`, supprimé après usage) a passé les cas suivants. Les 11 textes attendus de `Test_go_encode_*` sont identiques octet par octet. 52 des 53 cas limites du décodeur sont identiques. Le 53e, « invalid UTF-8 », ne peut pas arriver à un `&str` Rust. Le détail est dans `research/serde-json-envelope.md`.
  - Les `raw` de vectors.json suivent l'ordre d'insertion de Kotlin. Seul l'aller-retour `decode(encode(e)) == e` est testé, comme en Rust.
  - Décodage : encoding/json vérifie la syntaxe (dernière clé en double gagnante, comme serde). Ensuite, `serdeReadable` refuse en `json` ce que serde_json refuse aussi : 128 niveaux d'imbrication, une séquence `\u` de surrogate isolé, un nombre hors de la plage f64, de l'UTF-8 invalide.
  - Les nombres sont classés d'après leur texte littéral, comme le fait serde_json. Un entier positif est un u64, un entier négatif un i64, et le reste est un f64 : `1.0`, `1e0` et `-0` sont donc refusés là où un entier est attendu.
- **Vecteurs.** `docs/sync/vectors.json` est lu sur place, sans copie dans `testdata/`. C'est le fichier que lisent aussi `envelope.rs` et `SyncVectorsTest.kt` (Android TV) : une copie finirait par diverger du contrat partagé. Ses sections `rtt`, `projection` et `decide` concernent la TV : aucun test Rust ne les lit, et elles ne sont pas lues ici.
- **Tests Rust portés : 32 / 32, sous le même nom (`Test_<nom>`).** leader.rs en a 25, envelope.rs 5 et mod.rs 2. Un script a comparé les deux listes de noms.
- **Tests Go en plus : 11 `Test_go_*`.** Ils couvrent :
  - l'ordre des clés et les échappements ;
  - 53 cas limites du décodeur : profondeur 127/128, surrogates, `1e999`, `-0`, bornes de seq, t et driftMs, null, 1024/1025 octets, ordre des refus ;
  - `IsID` et les codes de `Reject` ;
  - l'horloge qui recule (`saturating_sub`) ;
  - une invitation reçue pendant un lien ;
  - la copie de la dérive ;
  - un nom de TV vide ;
  - une liste vide écrite `[]`.
- **Goldens produits par du code Go : 12 / 12 `events.sync_*`.**
  - status (4) vient de l'automate, après une invitation, un `ready` puis un `ping`.
  - notice (4) et action (3) sont les effets de l'automate.
  - devices vient de `TVDevices`.
  - Ils sont comparés octet par octet au golden compacté, puisqu'aucun ne contient de flottant.
- **Rouge observé.** Les tests ont d'abord tourné sur des fonctions vides : 36 échouaient. Ensuite, 7 mutations ont été essayées et 6 sont détectées : profondeur, `-0`, tri des clés, `>=` du silence, surrogate isolé, ordre des seq. La 7e (`announced` remis à false dans `ready`) est sans effet observable, en Rust aussi : `first` est déjà faux à chaque rechargement.
- **Écarts assumés.**
  - Débordement d'un flottant tout près de `f64::MAX` : serde_json sans `float_roundtrip` multiplie par une puissance de 10, alors que Go arrondit exactement. Un littéral au voisinage de `1.7976931348623158e308` peut donc être refusé d'un seul côté. Ce cas ne peut venir que d'un champ inconnu.
  - À l'encodage, un octet hors UTF-8 d'une chaîne Go est écrit U+FFFD, octet par octet. Une `String` Rust ne peut pas en contenir.
- Commandes non portées : inchangé. Les quatre commandes sync (`sync_devices`, `sync_invite`, `sync_unlink`, `sync_status`) relèvent de l'unité sync-service.

## Unité player (vague 3, wave3-player)
- **Fichiers (`internal/player`, en plus de `types.go` de wave2B, seulement complété en commentaire).**
  - Portables : `errors.go` (`PlayerError`), `source.go` (`ParseSource`, `MediaSource`), `command.go` (`PlayerCommand`, `VideoFit`), `event.go` (`PlayerEvent`, `EndReason`), `stall.go` (`StallWatch`), `engine.go` (interface `PlayerEngine`, `EventSink`, `SurfaceMode`), `mpvprops.go` (parties pures de mpv.rs : options, valeurs, lecture de `track-list`).
  - Windows : `mpv_engine_windows.go` (`MpvEngine`, `MpvConfig`, `DefaultMpvConfig`, plus `Property`, `PositionSeconds`, `SelectedStreams` hors interface, comme en Rust).
  - `internal/mpv` (binding du spike), étendu sans rien changer de l'existant : `CommandAsync`, `EventCommandReply`, constantes `EndFile*`, champs `Format`, `Flag`, `Double`, `LogPrefix` ajoutés en fin d'`Event` (`HasValue` vaut aussi pour un drapeau ou un double), erreur typée `*UnsupportedAPIError` dans `Load`. Le `lint:ignore` du champ `prefix` est retiré, puisqu'il sert.
  - `internal/redact` : `redact` de diagnostics.rs, porté maintenant parce que mpv.rs journalise les messages de mpv au travers. L'unité diagnostics le réutilise.
  - Note de recherche : `research/libmpv.md` (client.h du dépôt, API 2.6).
- **Tests Rust portés : 29 / 29, sous le même nom (`Test_<nom>`).** mod.rs en a 14 (8 StallWatch, 4 MediaSource, `command_json_contract`, `video_fit_sets_both_properties`) et mpv.rs 15 (7 purs, 7 sur la vraie libmpv, `missing_library_is_reported`). S'y ajoutent les 5 tests de `redact` de diagnostics.rs, dans `internal/redact`.
- **Tests sur la vraie libmpv** : ils sont sautés sans `VIEWER_MPV_DLL` (Rust : `VIEWER_LIBMPV`), et ils échouent si `VIEWER_REQUIRE_LIBMPV` est posé. Ils sont headless (`vo=null`, `ao=null`), sans fenêtre, sans son ni frappe. Lancés le 2026-11-22 avec `vendor/mpv/libmpv-2.dll` : tous verts, en 9 s.
- **Tests Go en plus : 7 `Test_go_*` dans player, 1 dans redact, et 3 tests de goldens.**
  - Le média Gate 1 (`spike.exe -gen <dir>`, dossier passé par `VIEWER_TEST_MEDIA`) est lu par `ParseSource` et le `Load` public, ce que les tests Rust ne font jamais. Le test vérifie la reprise à 2 s, le sous-titre ASS externe, la durée, les diagnostics (H.264 1280, `mkv`, hwdec), chaque commande lue en retour par mpv, `Seeked`, `TogglePause`, les refus (NaN, en-tête, NUL), puis un teardown depuis une autre goroutine : 23 à 31 ms, un seul `Shutdown`, en dernier, puis `NotOpen` et aucune lecture de propriété.
  - Autres cas : variantes serde de `PlayerCommand`, messages des erreurs de source, règles `as_i64`/`as_str` de serde_json sur `track-list`, style de sous-titres, format des flottants (`to_string` de Rust), StallWatch (Shutdown, temps passé, NaN).
- **Goldens produits par du code Go : `events.player_event.*` (12/12), `events.end_reason.all`, `errors.player_error.all_kinds`.** Les 4 `player.*` l'étaient déjà par wave2B. Le comparateur est copié de `internal/emby/golden_test.go`, car les aides de test d'un autre paquet ne s'importent pas. À regrouper dans un paquet de test commun si une 3e copie apparaît.
- **Écarts et choix.**
  - **`Shutdown` est émis par le moteur quand il est démantelé** (`MpvEngine.Shutdown`, après `mpv_terminate_destroy`, ou la boucle d'événements si mpv quitte seul : deux fois possible, comme en Rust). En Rust, c'est `AppState::close_player` (lib.rs) qui l'envoie, au reporter, au sync et à l'UI. **L'app-shell ne doit pas l'émettre une seconde fois** : son puits d'événements fait la diffusion. `Shutdown()` bloque jusqu'à la fin de mpv : il ne doit pas tourner sur le fil de l'UI (ADR 0003), donc ni dans un crochet de fenêtre synchrone. Go n'a pas de `Drop` : qui crée le moteur doit appeler `Shutdown`.
  - **Pas d'automate d'état sérialisé** (loading/playing/…) : player/mod.rs n'en a pas. L'état est la suite des `PlayerEvent` et la `StallWatch`. Le `player:event` à état du spike n'est pas le contrat.
  - **video_surface.rs** : seul le mode `parent` est porté, en `SurfaceMode` (`"parent"`, pour `app_info.surfaceMode`). `MpvConfig.WID` est le HWND fourni par l'appelant (`uintptr`, 0 = aucun) ; `internal/player` n'importe pas Wails. `ChildSurface`, les modes `child` et `separate` et `VIEWER_VIDEO_MODE` sont abandonnés (rejetés par l'ADR 0003).
  - Formes Go : `PlayerEvent` est une interface de types valeur (`FileLoaded{}`, `TimePos{…}`…), avec un `MarshalJSON` par variante. `PlayerCommand` est une struct à champ `Type`, décodable comme champ des arguments de `player_command`, avec un `UnmarshalJSON` strict (même règles que serde). `PlayerError` s'écrit en chaîne nue (`MarshalJSON`), et `errors.Is` compare le kind.
  - Contexte des erreurs `Mpv` : le nom que donne Rust (option, propriété, 1er argument), et le message vient de `mpv_error_string`. Un appel fait après le teardown donne `NotOpen` (cas impossible en Rust). Un NUL est refusé avant libmpv (`caractère NUL`, comme `CString::new`).
  - Texte de `LibraryUnavailable` : le message du binding, pas celui de libloading. Le kind est le même.
  - Flottants non finis : écrits `null` dans les événements (comme serde_json), et `nil` dans `Diagnostics`, ce qui donne le même JSON.
  - Journal : `log/slog` (`MpvConfig.Logger`, défaut `slog.Default()`). Les lignes de mpv passent par `redact.Redact`, avec l'attribut `target=mpv`. Le fichier de journal tournant reste à la vague 7.
- **Rouge observé par mutation.** Les tests ont été écrits avec le code. 4 mutations du moteur ont été essayées, et toutes font échouer de 1 à 2 tests : `Shutdown` non émis, `stream-lavf-o` réduit, raison `stop` mal lue, drapeau `select` de `sub-add` inversé.
- **Bugs Rust relevés** : aucun.
- Commandes non portées : inchangé. `player_open`, `player_command`, `player_tracks`, `player_diagnostics` et `player_close` sont à l'app-shell.

## Unité request (vague 6, wave6-request)
- **Fichiers (`internal/request`).** `request.go` (mod.rs : `RequestError`, `Account`, `NormalizeAddress`, `ensureCompatible`), `models.go`, `client.go`, `service.go`, `images.go`, `trailer.go`, `commands.go` (`Handlers(svc, identity)` : les 16 `ur_*` de commands.rs, de forme `bridge.Handler`), `decode.go`. Note de recherche : `research/request.md`.
- **Commandes : 1 branchée, `ur_open_trailer`** (trailer.rs, sans session ; lanceur `explorer.exe` injectable, jamais lancé par les tests). Décompte : −1.
- **Les 16 autres `ur_*` sont prêtes mais non branchées.** Chacune lit d'abord `identity(&emby)`, c'est-à-dire `EmbyService::exchange_identity` (session.rs, vague 4). `registerCommands` n'a encore ni session Emby, ni coffre (nom de service debug/release à trancher par l'app-shell), ni dossier de configuration (`app_config_dir` = `%APPDATA%\io.github.quentinpn.viewer`), ni version. **À brancher par la vague 4 / l'app-shell** : `request.NewService(store, filepath.Join(configDir, "request.json"), deviceName, version).WithArtworkCache(cache)`, puis `Handlers(svc, identitySource)` enregistrées une à une ; le décompte baissera alors de 16. Le protocole `requestimg` (lib.rs `request_image` : `ParseUrImage` puis `Service.Image`, statuts 400/401/403/404/429/502, `Cache-Control: max-age=31536000, immutable`) est aussi à l'app-shell. La session devra aussi appeler `RevocationFor` avant `emby_sign_out` / `emby_forget_server` (commands.rs) et supprimer `Account(serverId, userId)` du coffre (`forget_request`).
- **Tests Rust portés : 37 / 37 sous le même nom (`Test_<nom>`)**, 35 de tests.rs et 2 de trailer.rs ; listes comparées par script. Deux sont `t.Skip` avec la raison : `real_server_probe` (`#[ignore]` en Rust, vrais serveurs) et `emby_forget_drops_request_token` (passe par `EmbyService`, **à porter par la vague 4** sous ce nom). Les 3 lignes `diagnostics::redact` de `token_never_in_logged_text` passent par `internal/redact` (wave3-player).
- **Tests Go en plus : 11 `Test_go_*`** : chaque `ur_*` une fois contre le faux serveur (méthode, cible, corps), forme JSON de chaque kind d'erreur, `From<EmbyError>`, images du serveur jamais lues (`skip_deserializing`), clés et `null` envoyés à l'UI (flatten de `RequestDetail`, `Option<Vec>` null contre `[]`, `Blocked` taggé), règles serde du décodage, 3xx/204/`Retry-After`/double `Accept`, cas limites de l'adresse, fichier `request.json` octet par octet, arguments des commandes (erreur en chaîne nue, recherche blanche sans session, `embySignedOut`), plus `TestUrOpenTrailerIsRegistered` dans `cmd/viewer`.
- **Rouge observé par mutation** (tests écrits avec le code) : 7 mutations, 7 détectées chacune par un test nommé (émetteur du jeton, single-flight après 401, verrou de refus, blocage 429, `SessionToken.String`, texte d'erreur blanc, `ur:"skip"`). `concurrent_401s_exchange_once` a une barrière dans le faux serveur (le 1er appel refusé attend le 2e) : `stale >= 2` est déterministe ; `-count=20` vert.
- **Forme d'erreur, sans golden** (aucun `request.*` dans `testdata/golden`) : `RequestError.MarshalJSON` donne `{kind, message}`, message = texte `#[error]`, comparé à la main aux attributs serde de mod.rs. `TrailerError` n'a pas de `MarshalJSON` : chaîne nue, comme son `serialize_str`. Une erreur d'argument est une chaîne nue, comme Tauri.
- **Écarts assumés** (`research/request.md`) : contexte annulé → `unreachable` ; attente après 429 au plafond (horloge Windows de Go) ; chemins d'adresse exotiques ; texte des erreurs d'E/S du fichier de réglages.
- **Fixtures** : lues en place dans `contracts/request/fixtures/` (le `include_str!` de Rust, validées par le test de contrat Vitest), pas copiées dans `testdata/`.
- **Doublons locaux, faute d'export dans `internal/emby` (non modifié)** : `decode.go` (copie adaptée de `decodeStrict`, avec struct embarquée = `flatten`, `ur:"skip"` et champ requis par défaut) et `encodeQuery` (copie de `formEncode`). **Demande à l'unité emby** : exporter un décodeur strict commun (avec `flatten`/`skip`) et l'encodeur `form_urlencoded`, puis supprimer ces copies. Attention : `ur_genres` et `ur_sessions` renvoient des slices simples (pas `emby.List`), écrites `[]` seulement parce que ce décodeur rend requis tout champ non pointeur ; un décodeur commun à `required` explicite devra les baliser, sinon une liste absente partirait en `null`.
- **Relevé dans `internal/emby` (non corrigé, hors unité)** : `MemoryCredentialStore` a `String` mais pas `GoString` : `%#v` affiche les jetons. Sans effet dans l'app (le magasin Windows est utilisé), mais à ajouter.

## Unité diagnostics et system-info (vague 7, wave7-diag)
- **Paquets.** `internal/diagnostics` (diagnostics.rs hors `redact`, qui reste dans `internal/redact`) et `internal/sysinfo` (system_info.rs). Note de recherche : `research/dxgi.md`. Aucun fichier de `cmd/` n'est modifié.
- **Commandes prêtes, non branchées (à enregistrer par l'app-shell, décompte inchangé : 79).** Les deux ont la forme `func(ctx, json.RawMessage) (any, error)` de `bridge.Handler`.
  - `system_report` : `reg.Register("system_report", sysinfo.SystemReportHandler(debugBuild))`. Sans argument ; `debugBuild` = le drapeau de la build debug (ADR 0105), à fournir par l'app-shell (Go n'a pas `debug_assertions`).
  - `diagnostics_open_log_dir` : `reg.Register("diagnostics_open_log_dir", diagnostics.OpenLogDirHandler(logDir, diagnostics.OpenInExplorer))`. `logDir` vient de `diagnostics.DefaultLogDir("io.github.quentinpn.viewer" ou "io.github.quentinpn.viewer.debug", debugBuild)` (= `app_log_dir` de Tauri : `%LOCALAPPDATA%\{identifiant}\logs`, plus `VIEWER_E2E_LOG_DIR` en debug). Erreur : chaîne nue, comme `DiagnosticsError`.
  - Au démarrage, avant tout le reste (lib.rs `setup`) : `logger, err := diagnostics.Init(logDir, debugBuild, version)` (installe le slog par défaut, écrit « Viewer X started (… build) »), puis `defer logger.Close()`. Les goroutines à couvrir commencent par `defer logger.RecoverAndRepanic()` (équivalent du panic hook : écrit la panique et la pile sous la cible `panic`, puis la relance). Go n'a pas de hook global.
- **JSON de `system_report`** : champs et ordre de `SystemReport` (`debugBuild`, `os`, `gpus`, `decoders`, `displays`), camelCase, `Option` sans skip écrits `null`, listes jamais `null` sauf `decoders` (`Option<Vec>`). Pas de golden pour ces commandes (aucun dans `index.json`) : forme comparée à la main au serde du Rust et à `src/lib/settings/about.ts` (`Test_go_report_json_shape`).
- **Tests Rust portés : 12 / 12, sous le même nom (`Test_<nom>`)** : 6 de diagnostics.rs hors redact (`records_are_redacted_and_bounded`, `dependencies_log_warnings_only`, `formats_utc_timestamps`, `rotates_by_size_and_keeps_a_bounded_number_of_files`, `appends_to_the_existing_file_and_counts_its_size`, `a_line_longer_than_the_limit_is_still_written`) et 6 de system_info.rs (`windows_11_is_named_from_its_build`, `build_carries_the_update_revision`, `driver_version_splits_the_umd_parts`, `wide_strings_stop_at_nul`, `decoder_profiles_map_to_codecs`, `report_is_read_on_this_pc`) ; `print_report` (`#[ignore]`) devient `Test_print_report`, sauté sans `VIEWER_PRINT_REPORT`. Les 5 tests de `redact` restent dans `internal/redact` (vague 3).
- **Tests Go en plus** (`Test_go_*`) : probe DXGI réel sur ce PC (au moins 1 adaptateur, description non vide, GPU, décodeurs, écrans ; les noms ne sont écrits que dans la sortie du test), registre, tailles et décalages des structures C, forme JSON, ordre des codecs, cas limites (Edition, DriverVersion, UTF-16, parse u32), handler slog (ligne exacte, cibles, niveaux, groupes, écho, 800 lignes concurrentes, jeton jamais écrit), `Init` et panique relancée, fichier supprimable pendant l'écriture, rotation à 1 fichier, coupe sur une frontière de rune, commande d'ouverture du dossier (doubles, jamais l'explorateur), `DefaultLogDir`.
- **Rouge observé par mutation** (tests écrits avec le code) : 2 mutations, 2 détectées (ordre des renommages de la rotation ; slot `GetDesc1` d'IDXGIOutput6 27 -> 28).
- **Écarts assumés.**
  - `log` (Rust) devient `log/slog` : la cible d'un enregistrement est l'attribut `target` (défaut `viewer`, donc niveau complet ; toute autre cible est limitée à Warn, comme les dépendances en Rust). Les autres attributs sont ajoutés en fin de ligne `clé=valeur` (le Rust n'en a pas). La ligne entière passe par `redact.Redact`.
  - Pas de `Trace` en slog : tout ce qui est sous `Debug` est `TRACE`.
  - Le fichier est ouvert par `CreateFile` avec `FILE_SHARE_DELETE` (comme la std Rust), pas par `os.OpenFile`.
  - `Timestamp` utilise `time` au lieu de l'algorithme de Hinnant, avec les mêmes sorties (3 cas du test Rust).
  - `open_in_explorer` est aussi dans `internal/request/trailer.go` (`OpenInExplorer`, wave6). **Doublon** : à faire pointer sur `diagnostics.OpenInExplorer` par l'unité request, qui n'a pas été touchée.
  - Texte des erreurs d'E/S de `diagnostics_open_log_dir` : le `Display` de l'`io::ErrorKind` n'est reproduit que pour introuvable, refus, existe déjà et pas un dossier ; le reste est « other error ».
- **Bugs Rust relevés** : aucun.
- **Dépendances** : aucune nouvelle (`golang.org/x/sys` déjà là, dont `windows/registry`).

## Unité updater (vague 7, wave7-updater)
- **Paquet `internal/updater`**, avec la note de recherche `research/updater.md`. C'est une implémentation maison, pas l'updater de Wails (voir Décisions).
  - Elle reproduit le comportement de tauri-plugin-updater 2.14.1 : manifeste, 204, statuts, cible `windows-x86_64-nsis` puis `windows-x86_64`, `v` de tête, semver strict, comparaison avant la cible, téléchargement en mémoire, en-tête `MZ`, fichier temporaire, `ShellExecute "open"` avec `/S /UPDATE /R /ARGS`, puis sortie.
  - Le contrat UI est inchangé : commandes, résultats JSON, événement `app://update-ready`.
  - Aucun fichier de `cmd/` n'est modifié.
- **Commandes prêtes, non branchées (à enregistrer par l'app-shell, décompte inchangé : 79).** Les trois ont la forme `func(ctx, json.RawMessage) (any, error)` :
  - `cfg := updater.DefaultConfig(version)` : `version` = celle de package.json, injectée au build. Garder `cfg.Quit` par défaut (`os.Exit(0)`, comme `std::process::exit(0)` dans le plugin) : le processus doit avoir disparu avant que l'installateur copie l'exe, et un `app.Quit` gracieux n'est pas essayé. `Install` garde le verrou jusqu'à la sortie, comme le Rust : un second clic ne lance pas un second installateur.
  - `u := updater.New(cfg, emit)` (`emit` = le `bridge.Emitter` de l'app).
  - `reg.Register("app_update_check", updater.CheckHandler(u, debugBuild))`, `reg.Register("app_update_pending", updater.PendingHandler(u))` (UpdateInfo ou `null`) et `reg.Register("app_update_install", updater.InstallHandler(u))` (erreur : chaîne nue, comme `Result<(), String>`).
  - En build release seulement : `go u.Run(ctx)`, première vérification 15 s après le lancement, puis toutes les 6 h.
  - `debugBuild` vient du même drapeau que `system_report`.
  - Build release : `-ldflags "-X viewer/internal/updater.PublicKey=<base64 32 octets>"`. Vide, tout échoue en `signature`.
- **JSON** : `UpdateInfo` = `{version, notes}` (2 goldens `events.update_ready*`, comparés octet par octet). `CheckOutcome` = `{status, …}`, avec l'étiquette d'abord (`MarshalJSON` à la main).
- **Tests Rust portés : 6 / 6, sous le même nom** : `info_trims_and_defaults_notes`, `slot_downloads_each_version_once`, `payload_serializes_for_the_ui`, `outcomes_serialize_tagged_for_the_ui`, `failures_log_a_category_only` et `shipped_config_is_quiet_and_signed`.
  - **Écart sur `shipped_config_is_quiet_and_signed`.** La clé est injectée au build, donc le test ne peut plus vérifier `pubkey.len() > 40`. Il vérifie à la place que la valeur versionnée est vide et échoue fermé.
  - Le reste est vérifié contre `DefaultConfig` et contre `tauri.conf.json` : endpoint identique, transport non sécurisé autorisé, mode `quiet`, arguments NSIS.
- **Tests Go en plus** (`Test_go_*`), tous contre un `httptest` sur localhost avec une paire de clés éphémère :
  - le cycle complet : un seul téléchargement, un seul événement, `pending` renseigné ;
  - quatre vérifications concurrentes, qui ne téléchargent qu'une fois ;
  - les signatures refusées : autres octets, autre clé, base64 invalide, manifeste sans `ed25519`, clé vide ;
  - la version signée : version échangée (installateur de 1.3.0 annoncé en 1.4.0) et ancien installateur rejoué sous un numéro plus récent, tous deux refusés en `signature` ;
  - la disposition exacte du message signé (vecteur SHA-512 de FIPS 180-2) ;
  - le repli de `windows-x86_64-nsis` sans `ed25519` vers `windows-x86_64` ;
  - la validation de toutes les entrées (url relative ou invalide, sans url, sans signature), que la version soit plus récente ou non ;
  - les arguments de l'app après `/ARGS`, et `it_escapes_correctly_for_nsis` du plugin, avec les mêmes cas ;
  - la nouvelle vérification avant le lancement (octets altérés dans le slot : rien n'est lancé) ;
  - 14 issues de vérification (à jour, plus ancienne, pré-version, 204, debug `available`, 500 et 404, corps non JSON, sans version, semver invalide, `pub_date` invalide, sans cible, téléchargement en échec) ;
  - le format dynamique et la priorité de la cible nsis ;
  - les erreurs de configuration et le serveur fermé ou annulé ;
  - l'installation : arguments, chemin, contenu, sortie ; refus d'un fichier sans `MZ` et d'un lancement en échec ;
  - les trois handlers, et la boucle `run` ;
  - le semver : analyse stricte et ordre de précédence de SemVer 2.0.0 §11.
- **Rouge observé par mutation** : 4 mutations, 4 détectées :
  - vérification de signature court-circuitée ;
  - `<=` changé en `<` dans la comparaison de versions ;
  - version fixe dans le message vérifié ;
  - vérification d'`Install` court-circuitée.
- **Écarts assumés.**
  - La signature est en ed25519 pur sur `SignedMessage(version, octets)`, dans le champ `ed25519`, au lieu de minisign dans `signature`. La version est toujours liée, alors que le plugin n'exige la version signée que quand le commentaire de confiance la porte (c'est le cas de toutes les releases publiées).
  - Une entrée valide sans `ed25519` est sautée. Si aucune cible présente n'en a, la catégorie est `signature` (le plugin dirait `TargetsNotFound`, `manifest`).
  - Une entrée est valide avec `signature` **ou** `ed25519`, pour qu'un manifeste puisse abandonner la minisign après la transition.
  - encoding/json v1 ignore la casse des clés.
  - Les fichiers `.zip` et `.msi` ne sont pas pris en charge : seul un exe est publié.
  - Le User-Agent est `Viewer/<version>`.
  - Le proxy du registre n'est pas lu (déjà relevé dans « Unité emby-client »).
- **Bugs Rust relevés** : aucun. Une particularité du plugin est portée telle quelle : un corps 200 qui n'est pas du JSON donne `network`, pas `manifest`.
- **Corrigé après relecture** (wave7-updater, 2026-11-22).
  - La première version signait les octets seuls et perdait le contrôle de version signée du plugin : le rejeu d'un ancien installateur était possible en HTTP.
  - Corrigés aussi : le repli de cible, la validation de toutes les entrées, les arguments de l'app après `/ARGS` et la nouvelle vérification avant le lancement.
- **Dépendances** : aucune nouvelle (`crypto/ed25519` de la bibliothèque standard ; `golang.org/x/sys/windows.ShellExecute`).

### Sous-partie release-pipeline : chemin du basculement (à faire, rien n'est implémenté)
- **Nouvelle clé de signature.**
  - Une paire ed25519, générée par le propriétaire sur viewer-pc, jamais dans le dépôt. La clé privée PKCS#8 PEM va dans `~/.viewer/updater-ed25519.pem`, à côté de `updater.key` (ADR 0126 : les clés restent sur le PC).
  - Elle est aussi exposée en secret Actions **`UPDATER_ED25519_KEY`**, que le job `desktop` passe en variable d'environnement. Comme pour `TAURI_SIGNING_PRIVATE_KEY`, publish.ts lit d'abord l'environnement, puis le fichier.
  - La clé publique (base64 de 32 octets) n'est pas secrète. Elle peut être un fichier versionné (`go-app/updater.pub`) lu par le build pour `-ldflags -X`.
- **`scripts/publish.ts`.**
  - Construire le binaire Go (`CGO_ENABLED=0 go build -ldflags "-X …PublicKey=… -X main.version=…"`), puis l'installateur NSIS (`makensis` sur un `project.nsi` maison, voir plus bas), au lieu de `npx tauri build`.
  - Signer avec `version` = celle de `package.json`. C'est la semver canonique, sans `v`, la même que le `version` du manifeste :
    ```ts
    const digest = createHash("sha512").update(readFileSync(installer)).digest();
    const message = Buffer.concat([Buffer.from(`viewer-update-v1\0${version}\0`, "utf8"), digest]);
    const ed25519 = sign(null, message, createPrivateKey(pem)).toString("base64");
    ```
    - C'est le `SignedMessage` de `internal/updater/signature.go` : préfixe, octet nul, version, octet nul, puis les 64 octets du SHA-512.
    - Un test Vitest de `desktopManifest` devrait vérifier la signature avec `crypto.verify` et la clé publique.
  - Écrire `platforms["windows-x86_64"].ed25519` dans `desktopManifest` (src/release/manifest.ts, test à ajouter).
  - Pendant la transition, continuer à produire la `signature` minisign de l'installateur pour les apps Tauri : `npx tauri signer sign`, ou minisign en Node (`blake2b512` et ed25519 de `node:crypto`).
  - `verify:rust` devient la porte Go : gofmt, vet, staticcheck, test.
  - Au retrait de `src-tauri/`, `Test_shipped_config_is_quiet_and_signed` saute sa comparaison avec `tauri.conf.json` (fichier absent) ; `DefaultConfig` devient alors la seule source.
  - ADR 0112 (minisign, clé Tauri) et ADR 0126 (les clés restent sur le PC, alors qu'il y a un nouveau secret Actions) sont à amender par un ADR de basculement.
    - **ADR 0112, ligne 36** (« le numéro de version du manifeste n'est pas signé ») est fausse : les signatures minisign publiées portent `version:`, et le plugin refuse un écart.
    - L'app Go lie toujours la version (`SignedMessage`). La ligne est à réécrire au basculement.
- **`.gitea/workflows/release.yml`**, job `desktop` :
  - `env` : `UPDATER_ED25519_KEY: ${{ secrets.UPDATER_ED25519_KEY }}` à côté de `GITEA_TOKEN` ;
  - Go et NSIS sur le runner `viewer-pc` ;
  - la piste desktop versionne `go-app/` (la version vient toujours de `package.json`), et `src-tauri/Cargo.toml` sort de la liste au retrait de `src-tauri/`.
- **Installateur NSIS** (NON VÉRIFIÉ, voir `research/updater.md`).
  - Le modèle Wails est en `admin`, installe dans `$PROGRAMFILES64`, ne copie que l'exe et ne relance pas l'app.
  - Il faut un `project.nsi` maison :
    - `RequestExecutionLevel user`, `InstallDir $LOCALAPPDATA\Viewer` et clé de désinstallation dans HKCU, aux mêmes noms que l'installateur Tauri, pour qu'il s'installe par-dessus ;
    - `libmpv-2.dll` à côté de l'exe ;
    - relance de l'app quand `/R` est passé, et `/UPDATE` accepté sans effet de bord ;
    - attente de la fin de l'app en cours (ou sa fermeture) avant de copier l'exe.
  - À essayer à la main sur une installation Tauri existante avant toute release.
- **Release de transition, recommandée.** C'est la dernière release Tauri, publiée avec l'installateur **Wails** :
  - le `latest.json` porte `url` = installateur Wails, `signature` = sa signature minisign (clé Tauri actuelle) et `ed25519` = sa signature ed25519 ;
  - les apps Tauri installées vérifient la minisign, lancent l'installateur Wails en `/S /UPDATE /R /ARGS` et sont remplacées sans geste du propriétaire ;
  - ensuite, chaque release garde les deux signatures tant qu'une app Tauri peut rester installée, ou seulement `ed25519` une fois le PC passé à Go.
  - Préalables : l'installateur maison accepte `/S /UPDATE /R` et reprend le dossier et la clé de l'installation Tauri.
- **Alternative : réinstallation manuelle.** Le propriétaire désinstalle l'app Tauri et installe une fois l'app Wails. C'est plus simple, sans signature minisign à garder, mais c'est un geste manuel. Elle reste le repli si l'installateur maison ne peut pas reprendre l'installation Tauri.
- Choix entre les deux : décision du propriétaire, à prendre au basculement (`whole-app.md`).

## Unité installer (vague 7, wave7-installer)
- **Fichiers** : `go-app/build/windows/installer.nsi`, `go-app/build/windows/utils.nsh` (macros reprises du modèle Tauri, sans le plugin `nsis_tauri_utils`), `scripts/build-wails-installer.ps1`. Aucun fichier de `internal/` ni de `cmd/` n'est touché.
- **Construire** : `pwsh scripts/build-wails-installer.ps1 [-Build] [-ExePath …] [-OutDir …]`. Elle lit la version de package.json, lance `npm run build:wails:exe` si l'exe manque (ou avec `-Build`), cherche `makensis` sans rien installer (`-Makensis`, `$env:MAKENSIS`, PATH, `%LOCALAPPDATA%\tauri\NSIS`, `Program Files (x86)\NSIS`) et produit `Viewer_<version>_x64-setup.exe` (défaut : `go-app/bin/`, ignoré par git). `makensis` est appelé avec `/INPUTCHARSET UTF8`.
- **Vérifié** : makensis 3.13 (celui du cache Tauri) compile sans avertissement, avec un exe construit par `CGO_ENABLED=0 go build -ldflags -H=windowsgui ./cmd/viewer` (18,7 Mo) ; `7z l` sur l'installateur (43,3 Mo) montre `viewer.exe`, `libmpv-2.dll` et `uninstall.exe`. **L'installateur n'a pas été exécuté** (sur ce poste, une installation lancée depuis le shell de l'agent est virtualisée et toucherait l'enregistrement de l'app du propriétaire) : le comportement ci-dessous est lu dans le script, pas observé.
- **Constantes comparées au modèle que Tauri génère** (`src-tauri/target/release/nsis/x64/installer.nsi`, v1.5.0) :

| Élément | Tauri (généré) | Wails (`installer.nsi`) |
|---|---|---|
| Niveau d'exécution | `RequestExecutionLevel user` (`installMode: currentUser`) | identique |
| Dossier par défaut | `$LOCALAPPDATA\Viewer` | identique |
| Dossier d'une installation précédente | `HKCU\Software\viewer\Viewer` (valeur par défaut = `$INSTDIR`) | identique, lue et écrite |
| Exe principal | `viewer.exe` (`MAINBINARYNAME`) | identique |
| Désinstalleur | `$INSTDIR\uninstall.exe` | identique |
| Clé de désinstallation | `HKCU\Software\Microsoft\Windows\CurrentVersion\Uninstall\Viewer` (vue 64 bits) | identique |
| Valeurs de la clé | `MainBinaryName`, `DisplayName`, `DisplayIcon`, `DisplayVersion`, `Publisher` = `viewer`, `InstallLocation`, `UninstallString`, `NoModify`, `NoRepair`, `EstimatedSize` | identiques ; `EstimatedSize` = taille réelle du dossier (Tauri y ajoute 135157 Ko) |
| Langue de l'installateur | clé `Installer Language` sous `HKCU\Software\viewer\Viewer` (MUI), français, sans sélecteur | identique |
| Raccourci menu Démarrer | `$SMPROGRAMS\Viewer.lnk` (pas de sous-dossier) | identique |
| Raccourci bureau | `$DESKTOP\Viewer.lnk` | identique |
| AppUserModelID des raccourcis | `io.github.quentinpn.viewer` | identique |
| Valeur `Run` supprimée à la désinstallation | `HKCU\...\Run\Viewer` | identique |
| Données supprimées (case cochée seulement) | `%APPDATA%\io.github.quentinpn.viewer`, `%LOCALAPPDATA%\io.github.quentinpn.viewer`, `HKCU\Software\viewer\Viewer` | identiques |
| Fichiers installés | `viewer.exe`, `libmpv-2.dll` (de `vendor/mpv`), `uninstall.exe` | identiques |
| WebView2 | `downloadBootstrapper` (`go.microsoft.com/fwlink/p/?LinkId=2124703`, `/silent /install`), ignoré si déjà présent, ignoré avec `/UPDATE` | identique |
| Icône de l'installateur | aucune (`INSTALLERICON ""`) | `src-tauri/icons/icon.ico` |
| Signature de code | aucune | aucune |

- **Options de ligne de commande (même sémantique que Tauri).** `/S` : silencieux, aucune page. `/P` : passif, pages sautées. `/NS` : pas de raccourci. `/UPDATE` : pas d'installation de WebView2, pas de création de raccourci, désinstalleur sans suppression de raccourcis ni de données. `/R` : relance l'app après l'installation, seulement en `/S` ou `/P` (l'interface graphique a sa case « Lancer »), avec le texte qui suit `/ARGS` en arguments. Une app en cours est fermée avant la copie (Restart Manager, `RmForceShutdown`, sans demande en silencieux ou passif) ; sinon un message en français demande de la fermer.
- **Écarts avec le modèle Tauri, assumés.**
  - Pas de page « déjà installé : réinstaller ou désinstaller d'abord » : l'installateur écrase en place, comme le fait déjà le chemin `/UPDATE`. Tauri n'y ajoute rien d'autre qu'une désinstallation préalable, et ses fichiers sont les mêmes (exe, dll, uninstall.exe).
  - Pas de contrôle de version ni de blocage des rétrogradations (`allowDowngrades` est déjà `true` côté Tauri).
  - Pas de migration depuis une installation WiX (aucune n'a jamais été publiée) ni du nom d'exe d'avant 1.x.
  - Pas de `nsis_tauri_utils` : `RunAsUser` devient `Exec` (l'installateur n'est pas élevé), `StrReplace` devient des chaînes françaises écrites en dur.
  - Les textes viennent de `French.nsh` de Tauri, sans les chaînes de la page de réinstallation.
- **À signaler à l'app-shell (hors de cette unité).**
  - Le binaire Go n'a pas d'icône ni de ressource de version (aucun `.syso`) : sans elle, `DisplayIcon` et les raccourcis auront l'icône générique après le remplacement de l'app Tauri.
  - Le dossier de données de WebView2 de Wails peut ne pas être dans `io.github.quentinpn.viewer` : la case « Supprimer les données » ne l'effacerait alors pas.
  - La version de l'installateur vient de package.json, passée à `makensis`. L'exe packagé ne reçoit ni version ni `updater.PublicKey` (`build:wails:exe` ne passe que `-H=windowsgui`) : son updater échoue donc fermé. Pour la release, le build doit ajouter les `-ldflags -X` décrits dans « Unité updater ».
  - **Provenance de l'exe vérifié** : un `go build` direct (mêmes options que `build:wails:exe`) sur le `frontend/dist` déjà présent. Ni `npm run build:wails:exe` ni la branche `-Build` du script n'ont été exécutés : lancer vite dans `cmd/` pendant que d'autres agents y compilent était à éviter.
  - Le tableau a été confronté au modèle Tauri par un `grep` des `!define`, `WriteReg*`, `DeleteReg*`, `CreateShortcut` et `StrCpy $INSTDIR` des deux scripts (`SHCTX` lu comme `HKCU`). Il ne reste que les branches mortes pour la configuration actuelle : `perMachine` / `both`, `HOMEPAGE`, `STARTMENUFOLDER`.
- **Smoke propriétaire** : voir la dernière section (installer par-dessus Tauri, lancer, désinstaller).

## Unité session et segments (vague 4, wave4)
- **Fichiers.**
  - `internal/segments` : `segments.go` (`Segment`, `MediaIDs`, `Query`, `Marker`, `EmbyMarkers`, `Service` avec cache de 256 entrées, `Lookup`), `providers.go` (IntroDB et TheIntroDB : URL, statuts, corps limité à 64 Kio), `decode.go` (lecture stricte des réponses).
  - `internal/emby/session.go` : `SessionFile` / `SessionRecord`, `EmbyService` et toutes les méthodes de session.rs en PascalCase, `context.Context` en premier sur les appels réseau. S'y ajoutent `session_playback.go` (`PrepareSource` et ses variantes, `PrepareAtCap`, `StopEncoding`, `ReportPlayback`, `ResendStop`, `SegmentQuery`, `SegmentsKey`) et `session_views.go` (les conversions DTO vers modèles).
  - `internal/emby/reporting.go` : `Tracker`, `Reporter` et `ActivePlayback`.
- **Fichiers persistés, mêmes noms et format.** `session.json` : JSON indenté, écrit par `.json.tmp` puis renommage ; l'identifiant d'appareil est créé s'il manque. `playback-quality.json` est à côté.
  - **Écart avec la consigne.** Rust n'a pas de fichier de préférences de pistes : `TrackPreferences` n'est qu'un argument venu de l'UI, et rien n'est persisté.
  - **Écart d'écriture.** Go échappe U+2028 et U+2029 dans `session.json`, serde non. Le JSON reste valide et lisible par les deux.
- **Tests Rust portés.**
  - tests.rs : **90 / 90** sous le même nom, plus 14 `real_server_*` en `t.Skip` (`#[ignore]`, vrai serveur ; `session_real_server_test.go`). Les 87 de cette vague passent par `EmbyService` ; 3 étaient déjà portés.
  - reporting.rs : **13 / 13**. segments : **10 / 10**. session.rs n'a pas de test en ligne.
  - Contrôle par script : chaque nom Rust a son `func Test_<nom>`.
  - Les **14 moitiés client** de client_test.go (vague 2) sont **remplacées** par les tests entiers sous le même nom, comme demandé. client_test.go ne garde que les tests propres au client.
  - Aucun test n'a dû choisir une requête par son chemin au lieu de sa position : les tests Rust qui suivent un appel parallèle le faisaient déjà.
- **Tests Go en plus :**
  - `Test_go_reporter_*` (2) : la goroutine du Reporter contre le faux serveur, y compris le renvoi d'un arrêt perdu par Flush et ResendStop ;
  - `Test_go_resume_of_a_played_item_starts_at_zero` : la règle `!played` de la reprise, qu'aucun test Rust ne couvre ;
  - `TestRequestAccountIsRequestAccount` (paquet `emby_test`) ;
  - `TestSessionStatusNeedsItsFields` ;
  - `TestMemoryCredentialStoreNeverPrintsItsTokens` (voir plus bas) ;
  - `Test_go_answers_are_read_with_serde_rules` (segments).
  Le paquet externe `emby_test` (`session_external_test.go`, avec `export_test.go`) contient aussi `artwork_cache_spares_the_server_and_keeps_its_checks` et `emby_forget_drops_request_token` (request/tests.rs, différé par la vague 6), parce qu'artworkcache et request importent emby.
- **Rouge observé par mutation** (tests écrits avec le code) : 5 mutations, 5 détectées. Ce sont la comparaison de jeton d'`expireOn401`, la règle `!played` de la reprise (seulement par le test Go ajouté), la raison forcée du repli, le garde `SameToken` de `ResendStop` et l'appel `forgetRequest` (dans `ForgetServer`, puis dans `SignOut`). `-race` n'a pas été lancé, car il demande cgo sous Windows.
- **Goldens produits par du code Go (`golden_session_test.go`).** golden.rs `session_cases`, `browse_cases` et la partie service d'`error_cases` sont rejoués par `EmbyService` sur les mêmes faux serveurs. L'adresse devient `http://emby.test`, le jeton `TOKEN_PLACEHOLDER`, et un jeton restant fait échouer le test.
  - Couverts : models.* **43 / 43**, titles.emby_titles.* (3), titles.emby_local_trailer, chapters.emby_chapters.* (2), quality.emby_* (2), errors.service.* (9, plus invalid_response comparé sur `kind`).
  - **emby_restore.* (4) viennent tous de `Restore`.** Rust construisait authentication_required et server_unreachable à la main ; ici, ils viennent d'un jeton révoqué et d'un serveur qui ne répond pas.
  - **playback.plan.*, emby_play.* et engine_request.* (8 chacun) passent maintenant par `PrepareSource`** sur `signed_in_browser`. Les 5 champs auparavant copiés du golden sont produits par `prepareWith` : `title`, `bitrateLimit`, `startTicks`, la raison forcée et l'en-tête `X-Emby-Token`.
  - Les 3 `errors.playback.*` sont couverts aussi.
  - `TestGolden_models_shapes` ne garde que device_profile et events.playback_stopped.* (2), que rien ne produit encore.
  - Mutation vérifiée : sans réécriture de l'adresse, 8 comparaisons échouent.
- **Décisions.**
  - **Sens des dépendances** : emby importe segments (alias `segs`), jamais l'inverse. `segments.Marker` porte le chapitre, et `emby.EmbyMarkers` convertit `ChapterDto`.
  - **Artwork** : emby définit l'interface `ArtworkStore` (Get/Put), qu'implémente `*artworkcache.Cache`. Les commandes `artwork_cache_stats` / `artwork_cache_clear` appellent `Stats` / `Clear` sur le cache lui-même : `artwork_stats` / `clear_artwork` ne sont pas sur le service.
  - **Reporter** : `NewReporter(service, emit)` remplace l'AppHandle. `Close(timeout)` est ajouté, faute de Drop. La file est sans limite, comme le canal Rust.
  - **Appels parallèles** : chaque `tokio::join!` devient des goroutines (`async`). Tous les résultats sont attendus, puis traités dans l'ordre de Rust. Un 401 passe par `expireOn401` : seule la session dont le jeton est refusé se termine, et un 401 tardif d'une session précédente donne `cancelled`. Le compteur d'époque annule les vérifications dépassées. Les tests sensibles à l'ordre passent 20 fois sur 20 (`-count=20`).
  - **segments** : la forme tableau des structs de serde et les contrôles UTF-8 ne sont pas reproduits.
- **Ce que la session exporte pour request et l'app-shell** (rien n'est branché, et les ur_* restent non branchées) :
  - `(*EmbyService).ExchangeIdentity() (serverID, userID string, token AccessToken, err error)`, à envelopper en `request.IdentitySource` (`Identity{ServerID, UserID, EmbyToken}`) ;
  - `emby.RequestAccount(serverID, userID)`, épinglé sur `request.Account` par un test ;
  - `SignOut` et `ForgetServer` suppriment déjà le jeton Request du coffre (`forgetRequest`, comme Rust). L'app-shell n'a donc qu'à appeler `RevocationFor` avant eux.
  - `ArtworkCache()` renvoie le cache, et `CurrentUser`, `Client` et `LastServerURL` sont exportés.
  - `emby_forget_drops_request_token` est porté ici sous son nom. Le stub `t.Skip` d'`internal/request/service_test.go`, non modifié, est à retirer par son unité.
- **Relevé par la vague 6, corrigé** : `MemoryCredentialStore` affichait ses jetons avec `%#v`. Un `GoString` est ajouté ; le test a échoué avant le correctif.
- **Demande de la vague 6** : exporter un décodeur strict commun et l'encodeur `form_urlencoded`. Fait depuis : `internal/strictjson` et `internal/formenc`.
- **Commandes** : aucune n'était branchée par cette vague ; l'app-shell les a toutes branchées depuis (0 / 80).

## Unité app-shell : devoirs de la couche commandes (commands.rs) — faits, voir la section suivante
- Ce que les services Go ne font pas et que l'app-shell doit reproduire depuis `src-tauri/src/commands.rs` :
  - **Échec de chargement** (`commands.rs:671`) : `Shutdown` est envoyé au reporter seul, sans démantèlement du moteur.
  - **« Bye » à la TV** : avant `emby_sign_out` et avant un changement de serveur, la TV reçoit « bye ».
  - **Reprise d'un arrêt** : `resend_stop` avant `prepare`, `begin` après.
  - **Bascule de session** : `switch` avant `load`.

## Unité app-shell, sync-service et library-events (vagues 2, 3 et 5, app-shell)
- **Fichiers.**
  - `internal/app` : `AppState` et le `setup` de lib.rs. On y trouve les services (Emby, Request, segments, reporter, sync, socket, updater, cache d'illustrations) et la case du moteur, un `atomic.Pointer` : les lecteurs n'attendent jamais, comme le `try_lock` du sync, et la création est sérialisée par un mutex. S'y ajoutent `ClosePlayer` (sort le moteur, puis `Shutdown` ; le puits diffuse `Shutdown`, rien n'est réémis), `Close` (fin du processus, faute de Drop) et `Identity` pour Request. `New` ne touche pas le réseau ; `Start` lance le socket et, en release, l'updater.
  - `internal/libraryevents` : library_events.rs, hors de `internal/emby` (devsync importe emby). `Supervisor` ouvre une socket par session, reconnecte après 2, 5, 15 puis 30 s, envoie un KeepAlive à la connexion puis toutes les 30 s, contrôle la session chaque seconde et annonce un changement après 2 s de calme. S'y ajoutent `IsLibraryChange`, `SendStringOf` et `Events`.
  - `internal/devsync/service.go` : service.rs. `Service` est le `SyncHandle`. Une goroutine possède l'automate, une seconde envoie les enveloppes dans l'ordre. Les files sont sans limite (`mailbox`), car le puits du lecteur ne doit jamais bloquer. Dépendances injectées : session, moteur (sans attente), émetteur, id de lien. `Devices` est `sync::devices`.
  - `cmd/viewer` : `main.go` (lib.rs `run`), `commands*.go` (commands.rs, un fichier par domaine), `args.go` (lecture des arguments à la manière de Tauri, sur `strictjson`, avec les mêmes textes d'erreur), `protocols.go` (`emby_image`, `request_image`).
  - `internal/bridge` : `Registry.Names()`, ajouté pour le test du jeu de commandes.
- **Commandes : 80 / 80 enregistrées**, avec les mêmes noms, les mêmes clés camelCase (relevées dans les appels `invoke` de `src/`) et les mêmes résultats. `TestRegisteredCommandsAreTheRustInvokeHandler` lit `generate_handler!` dans lib.rs et compare les deux ensembles.
- **Devoirs de la couche commandes, reproduits.**
  - `emby_play` : `ResendStop` avant la négociation, `Begin` et `sync.Playing` après. Un échec après l'annonce envoie `Shutdown` au reporter seul, sans démantèlement (commands.rs:671).
  - `emby_change_quality` : `Switch` après l'analyse de la source et l'obtention du moteur, avant `Load`, sans `Shutdown`.
  - La TV est prévenue (`bye stopped`, 3 s au plus) avant `emby_sign_out` et `emby_change_server`.
  - `RevocationFor` est lu avant `ForgetServer` / `SignOut` ; les révocations partent en tâche détachée, sur le contexte de l'app.
  - `emby_set_played` et `emby_hide_from_resume` émettent `emby://playback-stopped`.
- **Fermeture pendant la lecture.** Le crochet `WindowClosing` annule la fermeture si un moteur est ouvert. Ensuite, hors du fil de l'UI : `ClosePlayer`, `Sync.StopBlocking(2 s)`, `Reporter.Flush(3 s)`, puis `win.Close()`. Jamais `app.Quit` (erreur 1412, voir gate1-go). Après `Run` : `Close(3 s)`.
- **Paniques.**
  - Les goroutines de l'app diffèrent `Options.Recover` (`logger.RecoverAndRepanic`) : la panique va au journal avec sa pile, puis le crash continue, comme le hook de panique de Rust.
  - C'est le cas de `App.Go` (superviseur du socket, updater, fermeture, révocations), des goroutines de `devsync` (`ServiceConfig.Recover`) et de celles de `libraryevents` (`Supervisor.Recover`).
  - Une commande qui panique est journalisée par `bridge.runHandler` (cible `panic`, avec la pile) et rejette son seul appel.
  - Restent hors de cette unité : les goroutines de `internal/emby` (`reporting.go`, `session.go`) et la boucle d'événements de `internal/player`.
- **Événements.** `player://event`, `emby://library-changed` (charge `null`), `emby://playback-stopped`, `sync://status|notice|action` et `app://update-ready` passent par `app.Event.Emit(nom, charge)`. Une donnée unique n'est pas enveloppée (vérifié dans `event_manager.go`). Les noms sont ceux des `listen` de `src/`.
- **Build.**
  - Drapeaux `-ldflags -X` : `viewer/internal/app.buildMode=release` et `viewer/internal/app.version=X.Y.Z`.
  - Sans drapeau (`go build`, `go test`), c'est une build debug : identifiant `io.github.quentinpn.viewer.debug`, coffre `Viewer-debug`, titre « (debug) », `player_open` permis, pas d'updater.
  - `npm run build:wails:exe` passe les deux drapeaux. La version vient de `%npm_package_version%` : le script npm tourne sous cmd.
  - `updater.PublicKey` reste à injecter pour une release (sous-partie release-pipeline).
- **Dossiers** (tauri 2.12.5, `path/desktop.rs` et `manager/webview.rs`).
  - Configuration : `%APPDATA%\{id}` (`session.json`, `playback-quality.json`, `request.json`).
  - Cache : `%LOCALAPPDATA%\{id}` (`artwork`, `tls-roots.pem`). Journaux : `%LOCALAPPDATA%\{id}\logs`.
  - Profil WebView2 : `WebviewUserDataPath` = `%LOCALAPPDATA%\{id}`. Tauri passe `app_local_data_dir` à WebView2, qui y ajoute `EBWebView` ; observé à l'exécution.
  - Les variables `VIEWER_E2E_*` et `VIEWER_LIBMPV` sont reprises, en debug seulement.
  - Port DevTools des scénarios CDP : Wails vide `WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS` dans un `init()` de paquet, avant `main`. La build debug lit donc `VIEWER_E2E_BROWSER_ARGS` (`app.BrowserArgsFor`) et la passe à `Windows.AdditionalBrowserArgs`. Un `--disable-features` rejoint `Windows.DisabledFeatures`. `scripts/e2e/app.mjs` pose les deux variables (2026-11-22, `docs/evidence/wails-smoke-2026-11-22.md`).
- **Tests.** Les 8 tests Rust de library_events.rs sont portés sous le même nom ; service.rs n'en a pas. Tests `Test_go_*` ajoutés :
  - socket, contre un vrai WebSocket local : KeepAlive, capacités, session propre, relais, annonce après le calme, fermeture en fin de session, accueil non affiché, catégories d'erreur ;
  - service sync : refus d'invitation, `load` puis `bye` dans l'ordre avec l'accusé de `Stop` (le service invite encore après, comme après une déconnexion), index de flux en lecture directe seulement, `join` nommé par le serveur, changement de session, service arrêté, ids de lien ;
  - app : noms, dossiers, libmpv, état sans lecteur ;
  - `cmd/viewer` : jeu de commandes, réponses du premier lancement, arguments à la Tauri, `player_open` réservé au debug, plein écran, statuts des protocoles.
  - ordre d'`emby_play` et d'`emby_change_quality` (`commands_play_test.go`, faux qui enregistre les appels) : succès, URL refusée, moteur absent, échec de `Load`. Deux mutations ont été vérifiées en rouge : sans `Shutdown` au reporter, et sans `Switch`.
  - panique : `App.Go` appelle `Recover`, et le pont journalise la panique d'une commande.
  - `-count=10` est vert sur devsync et cmd.
- **Preuve de bout en bout (2026-11-22).**
  - `npm run build:wails:exe` : exe de 21,2 Mo, sous-système GUI ; `go version -m` lit `-X …buildMode=release -X …version=1.5.0`.
  - Lancé avec `APPDATA` / `LOCALAPPDATA` pointés vers un dossier jetable : aucune session, donc ni restauration ni socket. Fermé avant la première vérification de l'updater (15 s).
  - Capture PrintWindow de sa seule fenêtre (2560x1400) : écran de premier lancement « Bienvenue. Ajoute ton premier serveur Emby. », sans « commande non portée ».
  - `WM_CLOSE` : sortie en 31 ms, code 0, pas d'erreur 1412 dans le journal. L'exe a été supprimé ensuite.
  - Relancé sur le code final (gardes de panique, `state.Go` dans le crochet de fermeture) : fenêtre après 572 ms, même écran, sortie 15 ms après `WM_CLOSE`, code 0. Le journal tient en trois lignes (démarrage en release, WebView2 créé, sortie), sans erreur ni adresse 192.0.2.
  - **Limite :** la fenêtre était au repos. La fermeture pendant une lecture (annulation, démantèlement, `bye`, envoi du rapport d'arrêt, puis `win.Close`) n'a pas été exécutée, seulement relue (voir la checklist smoke). **Exécutée le 2026-11-22** par `scripts/e2e/wails-smoke.mjs`, sans TV liée : sortie en 303 ms. Emby a la position de la fermeture (380 s, à 3 s près), et le journal n'a pas l'avertissement « stop report not confirmed ».
- **Écarts assumés.**
  - Pas d'instance unique : le squelette avait `SingleInstance`, retiré parce que l'app Tauri n'a pas ce plugin.
  - Les dossiers sont lus dans `%APPDATA%` / `%LOCALAPPDATA%` (`os.UserConfigDir`, `os.UserCacheDir`) au lieu des dossiers connus du shell. Ce sont les mêmes dans une session normale, et un processus lancé avec d'autres variables reste hors des données du propriétaire.
  - `app_info.spike` vaut toujours `false` : le scénario Gate 1 de la build debug Rust (spike.rs) n'est pas dans l'app Go.
  - `VIEWER_E2E_OFFSCREEN` place la fenêtre en -20000,-20000 **sans la maximiser** (maximisée, elle reviendrait sur l'écran le plus proche). Wails n'a pas d'option « sans activation ».
  - libmpv en debug : `vendor/mpv/libmpv-2.dll` est cherché en remontant depuis l'exe (Go n'a pas `CARGO_MANIFEST_DIR`).
  - Socket : le `Close(None)` de Rust devient une trame de fermeture 1000. La taille maximale d'un message reste 64 Mio (défaut de tungstenite).
- **Points ouverts (décision de l'orchestrateur ou du propriétaire).**
  - **Réglé côté Go (2026-11-22), confirmé à l'écran le même jour : les illustrations visent l'origine de l'app.** Le smoke Wails a vu dans un vrai WebView2, sans image cassée, les illustrations de l'accueil, de la grille et de la fiche (`/emby-img/`) ainsi que celles de Demander (`/requestimg/`) (`docs/evidence/wails-smoke-2026-11-22.md`).
    - Wails v3.1.0 ne route vers Go que l'hôte `wails.localhost` (`webview_window_windows.go`, `processRequest`). Les origines Tauri `http://emby-img.localhost` et `http://requestimg.localhost` n'y arrivaient donc jamais.
    - L'origine des URL est devenue un paramètre : `imageRefAt(origin, …)` dans `internal/emby/images.go` et `internal/request/images.go`. `ImageRefFor` et `imageRef` lisent l'origine du paquet, Tauri par défaut (`TauriImageOrigin`, `TauriOrigin`), donc les goldens ne changent pas.
    - Pas de point d'injection propre : les constructeurs sont des fonctions libres, appelées par les fonctions de conversion et par les `decorate()`, sans service. D'où un réglage de paquet, `SetImageOrigin` (un `atomic.Pointer`), appelé une seule fois par `main` avant `app.New` via `useAppImageOrigins` (`protocols.go`) : `http://wails.localhost/emby-img` et `http://wails.localhost/requestimg`. Les tests d'`internal/emby` et d'`internal/request` ne le changent jamais.
    - Préfixes gardés : `/emby-img/` et `/requestimg/`. Ils ne croisent ni `dist` (`index.html`, `assets/`) ni un routeur (`src/` n'en a pas), et `src/` ne construit aucune URL d'image (seuls des commentaires les citent).
    - CSP Wails : `img-src 'self' data: blob:`, sans les deux hôtes Tauri. `'self'` couvre les illustrations. `csp_test.go` compare chaque directive à `tauri.conf.json`, aux deux écarts près. La CSP Tauri ne change pas.
    - Preuve, sans vrai serveur : `Test_go_library_artwork_is_served_on_the_app_origin` connecte l'app à un faux Emby local, prend le `src` et le `srcset` renvoyés par `emby_libraries`, et les envoie à `withCSP(withProtocols(…))`, comme `main.go`. Résultat : 200, `image/jpeg`, `Cache-Control` immuable, CSP et octets du faux. Le `src` répété vient du cache d'illustrations, et un autre id de serveur reçoit 403. Côté Request, `Test_go_set_image_origin_reaches_decorated_models` vérifie que le réglage atteint les modèles, et le routage est prouvé (401 hors session). Le 200 n'a pas tourné, faute de faux serveur Request dans `cmd/viewer`. L'appel `request.SetImageOrigin` de `useAppImageOrigins` n'est vérifié qu'à la relecture.
  - **Le localStorage ne suit pas la bascule.**
    - Le dossier WebView2 est le même, mais le stockage est rangé par origine : `http://tauri.localhost` (vu dans `EBWebView\Default\Local Storage\leveldb` de l'app installée) contre `http://wails.localhost`.
    - Les réglages de l'UI (préférences de lecture, etc.) repartent donc des valeurs par défaut. Aucune migration n'est faite ici.
    - **Décision (2026-11-22), à faire au basculement.** La release Tauri de transition exporte les clés localStorage de l'app dans `%APPDATA%\io.github.quentinpn.viewer\ui-settings.json` : une commande dédiée, appelée au démarrage. Au premier lancement, l'app Wails lit ce fichier par une commande, remplit le localStorage s'il est vide, puis renomme le fichier. Clés concernées : `ItemDetail`, `LibraryBrowser`, `PlayerView` (volume), `subtitles`, `HomeRowsSettings`, `Settings`.
- **Pour d'autres unités.**
  - installer : un `go build` sans drapeau donne un exe **debug** (identifiant `io.github.quentinpn.viewer.debug`, coffre `Viewer-debug`). Un build direct, hors de `npm run build:wails:exe`, doit passer `-X viewer/internal/app.buildMode=release`. L'icône et la ressource de version restent à faire. La note sur le dossier WebView2 est réglée : il est `%LOCALAPPDATA%\{id}\EBWebView`, comme sous Tauri.
  - release-pipeline : la commande décrite dans « Unité updater » écrit `-X main.version=…`. La variable est maintenant `viewer/internal/app.version`, avec `viewer/internal/app.buildMode=release` en plus.
- **Notes de recherche** : `research/websocket.md` et `research/wails-shell.md`.
- **Dépendance.** `github.com/coder/websocket` v1.8.14 passe d'indirecte à directe. La bibliothèque standard n'a pas de client WebSocket, et Wails embarque déjà celui-ci : aucun téléchargement.
- **Bugs Rust relevés** : aucun.

## Bugs relevés
- emby/server_url.rs (Rust, non corrigé, porté tel quel) : seul le premier `/web` du chemin est examiné. `http://h/website/web` garde donc `/website/web`, au lieu de s'arrêter au `/web` final. Impact faible : cela ne vise que des adresses copiées depuis le navigateur.
- emby/artwork_cache.rs (Rust, non corrigé, porté tel quel) : l'éviction (`remove_empty_dirs`) peut supprimer le dossier qu'un `put` vient de créer, entre `create_dir_all` et l'écriture : ce `put` échoue (`NotFound`) sans autre effet. Les lecteurs d'une image servie depuis le réseau ignorent l'erreur. Impact faible ; une relance unique dans `put` le corrigerait.
- emby/client.rs (Rust, non corrigé, porté tel quel) : `image()` ne demande pas de jeton (pas de `require_token`). Seul le service vérifie la session avant l'appel. Sans effet aujourd'hui : un client sans jeton envoie l'en-tête sans `Token` et Emby répond 401.
- emby/session.rs:495 (Rust, non corrigé, porté tel quel) : la réponse à `/Views` est écrite dans la session ouverte au moment où elle arrive, quelle qu'elle soit, et non dans celle qui a posé la question. Un changement de compte pendant l'appel peut donc y mettre les vues de l'autre compte. Impact faible.

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
- 2026-11-22 (wave3-player) : l'événement `Shutdown` est émis par le moteur quand il est démantelé (par `MpvEngine.Shutdown`, sur la goroutine appelante, et non plus par l'équivalent de `close_player`) ; il peut arriver deux fois si mpv quitte de lui-même (boucle d'événements, puis `Shutdown`), comme en Rust (fil d'événements, puis `close_player`). Exception : `commands.rs:671` envoie `Shutdown` au reporter seul en cas d'échec de chargement, sans démantèlement du moteur : l'app-shell doit le reproduire. `Shutdown` appelé depuis le puits renvoie `ErrShutdownFromSink` sans rien faire. Il appelle `Shutdown` hors du fil de l'UI, jamais depuis le puits d'événements, et sans tenir à travers l'appel un verrou que le puits prend : il sort d'abord le moteur de son mutex, comme le fait Rust.
- 2026-11-22 (wave3-player) : `internal/redact` (port de `diagnostics::redact`, avec ses 5 tests) existe dès la vague 3. Toute journalisation Go qui peut contenir un jeton passe par `redact.Redact`, et la vague 7 réutilise ce paquet.
- 2026-11-22 (wave7-updater) : les mises à jour passent par une implémentation maison (`internal/updater`), pas par l'updater de Wails v3.
  - L'updater de Wails accepte l'HTTP sur le LAN, marche sans fenêtre et vérifie ed25519, ed25519ph ou ecdsa-p256.
  - Mais il **échange un seul fichier** et ne lance jamais d'installateur. Ni `libmpv-2.dll`, ni les raccourcis, ni la clé de désinstallation ne seraient mis à jour.
  - Signature : ed25519 pur (`crypto/ed25519`) sur `"viewer-update-v1" 0x00 version 0x00 SHA-512(installateur)`, dans le champ `ed25519` du même `desktop/latest.json`. La version liée remplace le contrôle de version signée du plugin. Le plugin Tauri ignore les champs inconnus, donc un seul manifeste sert les deux apps pendant la transition.
  - Clé publique : var `updater.PublicKey`, injectée par `-ldflags -X`, vide dans le dépôt (échec fermé).
  - Garder minisign est écarté : il faudrait BLAKE2b, donc `golang.org/x/crypto`. Détail dans `research/updater.md`.

## Checklist smoke pour le propriétaire (avant le basculement)
Smoke automatisé : `node scripts/e2e/wails-smoke.mjs` (build debug, vrai serveur, CDP et PrintWindow, sans prise du bureau), plus un sous-ensemble `--offline` sans serveur. Résultats du 2026-11-22 dans `docs/evidence/wails-smoke-2026-11-22.md`. **[vérifié]** = exercé par ce scénario ; le reste demeure manuel. Point ouvert relevé : sous Wails, la fenêtre hors écran peut prendre le premier plan à son ouverture (pas d'option « sans activation »).
- Clavier réel : Espace, flèches et Échap dans le lecteur, focus après le plein écran. **[vérifié par CDP seulement]** : Espace (pause et reprise), flèche droite (+10 s, `seeked`) et Échap (fermeture du panneau, puis sortie du lecteur) envoyés à la page. Le clavier physique et le focus après le plein écran restent NON VÉRIFIÉS ici ; le propriétaire les signale bons.
- Son audible, lecture d'un vrai MKV et d'un vrai flux Emby (avec jeton). **[vérifié]** : flux Emby en lecture directe, MKV H.264 1920×800, pistes audio et sous-titres changées depuis les panneaux. Le son reste NON VÉRIFIÉ (sortie `null` en test) ; le propriétaire le signale bon.
- Lecture par `MpvEngine` dans la fenêtre Wails (`wid` = HWND principal) : le spike a validé la composition, mais pas encore ce moteur, qui n'a tourné qu'en headless. **[vérifié]** : `d3d11va`, gpu-next / d3d11, vidéo visible sous les contrôles (PrintWindow du HWND de l'app), progression par `player://event`.
- Échelle d'affichage à 125-150 %, deuxième écran.
- Coffre : après une connexion faite avec l'app Tauri, la build Go doit rouvrir la session sans redemander le mot de passe (même entrée `{serverId}/{userId}.Viewer`).
- Mise à jour : une build Go signée (clé injectée) trouve une version plus récente sur un `latest.json` de test, affiche la pastille, puis « Redémarrer maintenant » lance l'installateur en silence et l'app revient à jour. Un installateur à la signature altérée est refusé (Réglages › Mises à jour : erreur `signature`).
- Installateur Wails (`pwsh scripts/build-wails-installer.ps1 -Build`, jamais exécuté jusqu'ici) : l'installer par-dessus une installation Tauri 1.5.0 (même dossier `%LOCALAPPDATA%\Viewer`, une seule entrée « Viewer » dans Paramètres › Applications, raccourcis conservés). Lancer l'app (elle charge `libmpv-2.dll` à côté de l'exe), vérifier l'icône des raccourcis, puis tester `Viewer_x.y.z_x64-setup.exe /S /UPDATE /R` app ouverte (elle se ferme, l'installateur écrase, l'app revient). Enfin désinstaller depuis Paramètres : exe, dll, raccourcis et clé disparaissent, les données restent sauf case cochée. Vérifier aussi que les accents français de l'installateur sont lisibles (l'encodage n'a pas été observé).
- Coquille Wails (unité app-shell, rien de cela n'a tourné) :
  - fermer avec ✕ pendant une lecture : le lecteur s'arrête, la TV liée reçoit `bye`, Emby enregistre la position, puis l'app quitte en moins de 6 s ; **[vérifié sans TV liée]** : `WM_CLOSE`, sortie en 303 ms (code 0). Reprise Emby à la position de la fermeture après relance, à 3 s près, et journal sans « stop report not confirmed ». Le `bye` reste à voir avec une TV ;
  - plein écran depuis une fenêtre maximisée, puis retour : la fenêtre redevient maximisée ;
  - les événements arrivent à l'UI : progression du lecteur, nouveau titre sur l'accueil (`emby://library-changed`), pastille de mise à jour ; **[vérifié pour `player://event`]** (`timePos`, `tracks`, `paused`, `seeked`, `buffering`…). `emby://library-changed` et la pastille ne le sont pas ;
  - lecture liée avec une TV (invitation, `load`, pause, déliaison) ;
  - une deuxième instance s'ouvre comme sous Tauri (pas d'instance unique) ;
  - les illustrations s'affichent dans la build Wails : accueil, fiche détaillée, demandes Request (URL `http://wails.localhost/emby-img/…` et `/requestimg/…`) ; **[vérifié]** ;
  - après la bascule, les réglages de l'UI repartent des valeurs par défaut (localStorage rangé par origine, voir `research/wails-shell.md`).
