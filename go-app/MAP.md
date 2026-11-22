# Carte de la migration Go/Wails (ADR 0128)

Règles : `.claude/skills/go-wails-anything/`. Une unité est prise par un seul agent à la fois.

**Commandes non portées : 80 / 80**

## Vagues
| Vague | Unité | Fichiers Rust | Statut |
|---|---|---|---|
| 0 | Spike lecture | spike.rs (abandonné), video_surface.rs (mode parent) | porté (spike, `go-app/cmd/spike`) |
| 1 | emby-models | emby/mod.rs (EmbyError), models.rs, titles.rs, quality.rs, chapters.rs, images.rs, server_url.rs | porté (`internal/emby`, sans commande : voir « Unité emby-models ») |
| 2 | emby-tracks | emby/tracks.rs (dépend de playback) | à faire |
| 1 | credentials | emby/credentials.rs | à faire |
| 2 | emby-client | emby/client.rs, player/tls_roots.rs | à faire |
| 2 | emby-playback | emby/playback.rs | à faire |
| 2 | artwork-cache | emby/artwork_cache.rs | à faire |
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

## Bugs relevés
- emby/server_url.rs (Rust, non corrigé, porté tel quel) : seul le premier `/web` du chemin est examiné. `http://h/website/web` garde donc `/website/web`, au lieu de s'arrêter au `/web` final. Impact faible : cela ne vise que des adresses copiées depuis le navigateur.

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

## Checklist smoke pour le propriétaire (avant le basculement)
- Clavier réel : Espace, flèches et Échap dans le lecteur, focus après le plein écran.
- Son audible, lecture d'un vrai MKV et d'un vrai flux Emby (avec jeton).
- Échelle d'affichage à 125-150 %, deuxième écran.
