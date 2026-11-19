# Intégration Request — plan d'implémentation

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** onglet « Demander » Request pour les membres, desktop (Tauri/Rust/Svelte) et Android TV (Kotlin/Compose).

**Architecture:** client natif par plateforme (reqwest derrière des commandes Tauri ; OkHttp côté TV), contrat OpenAPI figé dans `contracts/request/`, fixtures JSON partagées validées contre ce contrat et désérialisées par les tests des deux plateformes.

**Tech Stack:** Rust (reqwest 0.13, serde, keyring via `CredentialStore`), Svelte 5 + TS strict, Kotlin (OkHttp 5.5.1, kotlinx.serialization, Coil 3, mockwebserver3).

**Spec:** `docs/superpowers/specs/2026-11-19-request-integration-design.md` (lire avant toute tâche).

## Global Constraints

- Aucune nouvelle dépendance (Rust, npm, Gradle). Faux serveur Rust : `TcpListener` comme `src-tauri/src/emby/tests.rs`.
- Appels Request uniquement en natif. Images via protocole `requestimg` (desktop) ou Coil sur le client authentifié (TV). Jamais de jeton dans une URL ni un journal.
- `POST /api/auth/emby-token` sans en-tête `Authorization` ; tout le reste en `Authorization: Bearer`. 401 → supprimer, ré-échanger une fois, rejouer ; second 401 → erreur `rejected`.
- Compatibilité : major `1` et features `auth.emby-token`, `auth.bearer`, `auth.sessions`, `media.item-id`.
- Erreurs serveur `{ "error": "…" }` affichées telles quelles.
- Textes UI en français, libellés de statut exactement ceux de la spec.
- Nom d'appareil : `{COMPUTERNAME} (Windows)` / `{Build.MODEL} (Android TV)`.
- Gates : `npm run verify` (desktop) ; `android-tv/gradlew.bat -p android-tv :app:testDebugUnitTest :app:lintDebug` (TV). Un commit par tâche, messages en anglais à l'impératif comme l'historique.

## Règles vérifiées dans le code d'Request (`main@54cd791`)

- Saison demandable (`src/shared/requests.ts:37-49`, `status.ts:97`) : `seasonNumber > 0` ET diffusée (`episodeCount > 0` et `airDate` non nul et `airDate <= aujourd'hui UTC AAAA-MM-JJ`) ET statut absent, `partial` ou `declined`. Titre demandable : film → statut absent/`partial`/`declined` ; série → statut ≠ `available` et (statut demandable ou au moins une saison demandable).
- `GET /api/requests?status=a,b` : plusieurs statuts séparés par des virgules, jamais répétés. « En cours » = `approved,sent` ; « En attente » = `pending` ; « Disponibles » = `available` ; « Sans suite » (refusées, échouées, annulées) = `declined,failed,cancelled`.
- `seeAll` = `{ type: "movie"|"tv", filters: { genres?: number[], yearFrom?, yearTo?, rating?, runtimeMin?, runtimeMax?, language?, provider? } }`.
- Images : `posterPath`/`backdropPath`/`profilePath` TMDB commencent par `/` (ex. `/abc.jpg`) ; la route attend `file` = `^[A-Za-z0-9_-]+\.(jpg|jpeg|png|webp|svg)$` → retirer le `/` initial. Côté client on n'accepte que `jpg|jpeg|png|webp` (pas de SVG). Tailles : `w185 w342 w500 w780 w1280 original`.
- Recherche : les résultats passent par `withStatuses` ; `embyItemId` n'est présent que pour un titre `available` ou `partial`. « À demander » = résultats sans `embyItemId`.
- Limite : 10 échecs (connexion ou Bearer invalide) par IP / 5 min, partagée avec la connexion par mot de passe.

## Review Focus

1. **Ruée d'échanges** : au démarrage sans jeton ou après expiration, Découvrir + bande + ~20 images partent en parallèle ; un échange par requête épuiserait la limite et bloquerait le foyer. L'échange est **single-flight** (un verrou ; après l'avoir obtenu, relire le jeton stocké et ne ré-échanger que s'il est encore celui qui a échoué). → tests `concurrent_calls_exchange_once` et `concurrent_401s_exchange_once` (T2) et équivalents Kotlin (T7).
2. Jeton Request périmé ET jeton Emby révoqué : un seul ré-échange, puis état « connexion refusée », jamais de boucle. → `rejected_after_single_retry` (T2), `rejectedAfterSingleRetry` (T7).
3. « Se déconnecter » doit tenir : drapeau `signedOut` persistant par compte Emby, l'onglet affiche « Se connecter », aucune reconnexion automatique. Déconnexion Emby ou « Oublier ce serveur » → supprimer aussi le jeton Request de ce compte (révocation au serveur au mieux). → `sign_out_does_not_auto_reconnect`, `emby_forget_drops_request_token` (T2).
4. Adresse : espaces, `/` final, sans schéma → `https://` ; `http://` accepté seulement pour loopback et réseaux privés (127/8, 10/8, 172.16/12, 192.168/16, `*.local`) avec l'avertissement « non chiffré » ; sinon `InvalidAddress` (le jeton Emby ne part jamais en clair sur Internet). → `normalizes_address` (T1).
5. 429 : message avec le délai `Retry-After` en minutes, pas de nouvel essai automatique. → `rate_limited_exposes_retry_after` (T1).
6. Compte Emby qui change : jeton rangé sous `request/{embyServerId}/{embyUserId}`. → `token_is_scoped_to_emby_account` (T2).

---

### Task 0: ADR, contrat figé et fixtures partagées

**Files:**
- Create: `docs/adr/0125-integration-request.md` (approche A, contrat figé + fixtures, jetons et single-flight, politique http limitée au LAN, déconnexion persistante) — écrit avant tout code ; lire d'abord `.claude/skills/{security-review,emby-api,desktop-ui,quality-gates}/SKILL.md`.
- Create: `contracts/request/openapi.json` (déjà copié, `main@54cd791`), `contracts/request/README.md` (source, commit, commande de rafraîchissement `git -C <Request> show origin/main:docs/openapi.json > contracts/request/openapi.json`)
- Create: `contracts/request/fixtures/{version-ok,version-old,emby-token,sessions,discover,paged-media,search,media-movie,media-tv,quota,request-view,request-page,request-detail,error}.json`
- Create: `src/lib/requests/contract.test.ts`
- Modify: `biome.json` (exclure `contracts/request/openapi.json` du formatage)

**Interfaces:** Produces le répertoire de fixtures consommé par T1 (Rust `include_str!("../../../contracts/request/fixtures/…")`) et T7 (Kotlin `File("../../contracts/request/fixtures/…")`, cwd des tests = `android-tv/app`).

- [ ] Écrire les fixtures (provisoires, remplacées en T2b par des réponses réelles nettoyées) : valeurs réalistes françaises, chemins d'images avec `/` initial ; `media-tv` = série `partial` avec saisons 0 (spéciaux), 1 `available`, 2 `partial`, 3 sans statut diffusée, 4 sans statut `airDate` future, 5 `pending`, `embyItemId` non nul ; `media-movie` sans statut et `embyItemId: null` ; `version-old` = `{"apiVersion":"1.0.0","features":["api.version"]}` ; `error` = `{"error":"Quota de films atteint"}`.
- [ ] Test `contract.test.ts` : charge `openapi.json`, associe chaque fixture à son schéma (`version-*`→`ApiVersion`, `emby-token`→`EmbyTokenSession`, `sessions`→réponse 200 de `GET /api/auth/sessions`, `discover`→`DiscoverResponse`, `paged-media`→`PagedMedia`, `search`→`MediaSearchResults`, `media-*`→`MediaDetails`, `quota`→`RequestQuota`, `request-view`→`RequestView`, `request-page`→`RequestPage`, `request-detail`→`RequestDetail`, `error`→`ApiError`) et la valide avec un petit validateur local (type, `required`, `enum`, `const`, `anyOf`/`oneOf`, `$ref`, `items`, types nullables `["string","null"]`) **strict** : toute propriété absente du schéma échoue. Le test doit aussi échouer sur une fixture mutée (cas négatif dans le test).
- [ ] `npx vitest run src/lib/requests/contract.test.ts` → PASS ; `npm run lint` → PASS.
- [ ] Commit `Add the Request contract and shared fixtures`.

### Task 1: Client Rust Request

**Files:** Create `src-tauri/src/request/{mod.rs,models.rs,client.rs,tests.rs}` ; Modify `src-tauri/src/lib.rs` (`mod request;`), `src-tauri/src/diagnostics.rs` (aucune clé nouvelle sauf si `token` n'y est pas déjà).

**Interfaces — Produces:**
- `pub enum RequestError` (thiserror, `Serialize` en `{ kind, message }` comme `EmbyError`) : `NotConfigured`, `InvalidAddress`, `Unreachable`, `TooOld { api_version: String, missing: Vec<String> }`, `Rejected(String)`, `RateLimited { retry_after_s: u64 }`, `Forbidden(String)`, `NotFound(String)`, `Server { status: u16, message: String }`, `InvalidResponse`, `EmbySignedOut`. Messages français ; pour 4xx/5xx, `message` = champ `error` du corps.
- `models.rs` : structs serde `camelCase` reprenant exactement les schémas OpenAPI utilisés (ApiVersion, EmbyTokenSession, Viewer, Capabilities, AuthSession, DiscoverResponse/DiscoverRow, MediaSummary, PagedMedia, MediaSearchResults/SearchPerson, MediaDetails/Season/CastMember/CrewMember/Genre, RequestQuota/QuotaUsage, RequestView, RequestDetail/RequestEvent, RequestPerson, RequestPage/RequestTotals, NewRequest (`type`, `tmdbId`, `seasons: Option<SeasonsChoice>` avec `All` → `"all"` / `List(Vec<u32>)`)). Statuts en enums `#[serde(rename_all = "lowercase")]`. Champs optionnels du schéma en `Option` + `#[serde(default)]`.
- `pub fn normalize_address(input: &str) -> Result<Url, RequestError>` ; `pub const REQUIRED_FEATURES: [&str; 4]` ; `pub fn ensure_compatible(v: &ApiVersion) -> Result<(), RequestError>`.
- `pub struct RequestClient` : `new(base: Url) -> Self` (reqwest comme `emby/client.rs` : timeouts 5 s / 15 s, redirections off, UA `Viewer/{version}`), `version()`, `exchange(user_id, emby_token: &AccessToken, device_name) -> EmbyTokenSession` (`device_name` tronqué à 64 caractères), `get<T: DeserializeOwned>(path, query: &[(&str, String)], token) -> Result<T>`, `send<B: Serialize, T>(method, path, body: Option<&B>, token) -> Result<T>`, `send_empty(method, path, token) -> Result<()>` (204), `image(size, file, token) -> Result<(Vec<u8>, String /*content-type*/)>`. Le jeton est un `SessionToken(String)` dont `Debug` affiche `SessionToken(***)`.

- [ ] Tests (fixtures + faux serveur) : `parses_every_fixture`, `normalizes_address` (`" request.exemple.fr/ "`→`https://request.exemple.fr`, `ftp://x`→`InvalidAddress`), `rejects_old_server` (`version-old` → `TooOld` listant les 3 features manquantes ; `"2.0.0"` → `TooOld`), `exchange_sends_no_authorization` (en-tête absent, corps `{userId,accessToken,deviceName}`), `bearer_on_other_calls`, `maps_error_body` (403 + `error.json` → `Forbidden("Quota de films atteint")`), `rate_limited_exposes_retry_after` (429 `Retry-After: 120` → `RateLimited{120}`), `token_never_debug_printed` (`format!("{:?}", token)` ne contient pas la valeur).
- [ ] Implémenter jusqu'au vert ; `cargo test --manifest-path src-tauri/Cargo.toml request`.
- [ ] `npm run verify:rust` → PASS. Commit `Add the native Request client`.

### Task 2: Service, réglages, commandes Tauri et images

**Files:** Create `src-tauri/src/request/{service.rs,commands.rs,images.rs}` ; Modify `src-tauri/src/emby/session.rs` (accesseur + suppression du jeton Request à la déconnexion/oubli Emby), `src-tauri/src/lib.rs` (manage, handlers, protocole), `src-tauri/tauri.conf.json` (CSP : ajouter `http://requestimg.localhost` à `img-src`), `src-tauri/capabilities/*` si nécessaire.

**Interfaces — Consumes:** T1. **Produces:**
- `EmbyService::exchange_identity(&self) -> Result<(String /*server_id*/, String /*user_id*/, AccessToken), EmbyError>` (nouvel accesseur, la seule fuite du jeton Emby, utilisée uniquement par le service).
- `RequestService` (état Tauri) : adresse persistée dans `{app_config_dir}/request.json` `{ "address": "https://…" }` ; jeton dans le `CredentialStore` existant sous le compte `request/{embyServerId}/{embyUserId}` ; méthode générique `call(emby, |client, token| …)` = jeton chargé ou échangé, appel, sur 401 suppression + un ré-échange + rejeu, second 401 → `Rejected`. Version vérifiée au premier appel puis mise en cache jusqu'au changement d'adresse.
- Commandes (`#[tauri::command]`, erreurs `RequestError`) : `ur_status() -> UrStatus` (`{ state: "notConfigured"|"tooOld"|"unreachable"|"rejected"|"ready", address: Option<String>, viewer: Option<Viewer>, apiVersion: Option<String>, missing: Vec<String>, message: Option<String> }`), `ur_set_address(address: String) -> UrStatus`, `ur_sign_out()`, `ur_discover()`, `ur_browse(media_type, filters: BrowseFilters { page, genres, year_from, year_to, rating, sort })`, `ur_genres(media_type)`, `ur_search(query, page)`, `ur_media(media_type, tmdb_id)`, `ur_quota()`, `ur_request(request: NewRequest) -> RequestView`, `ur_my_requests(status: Option<String>, page: u32) -> RequestPage` (`mine=true`), `ur_request_detail(id)`, `ur_cancel(id) -> RequestView`, `ur_sessions() -> Vec<AuthSession>`, `ur_revoke_session(id)`.
- Images : chaque `posterPath`/`backdropPath`/`profilePath` renvoyé à l'UI est accompagné d'un `ImageRef` (type existant `emby/models.rs:521`) construit en Rust : `http://requestimg.localhost/{size}/{file}` (+ `srcset` w185/w342/w500 pour les affiches, w780/w1280 pour les fonds). Le protocole `requestimg` valide `size` ∈ {w185,w342,w500,w780,w1280} et `file` ∈ `^[A-Za-z0-9_-]{1,64}\.(jpg|png)$`, sinon 400 ; récupère via `client.image` avec le Bearer (même logique 401) et réutilise le cache disque des affiches (`emby/artwork_cache.rs`).

- [ ] Tests : `concurrent_calls_exchange_once` (8 appels parallèles sans jeton → 1 échange), `concurrent_401s_exchange_once` (8 appels reçoivent 401 → 1 ré-échange), `sign_out_does_not_auto_reconnect`, `emby_forget_drops_request_token`, `token_is_scoped_to_emby_account` (deux comptes Emby → deux entrées), `rejected_after_single_retry` (deux 401 consécutifs → exactement 2 échanges max, puis `Rejected`), `retries_once_after_401` (401 puis 200 → succès, 1 ré-échange), `status_not_configured`, `image_path_validation` (`../x.jpg`, `w9999` → refus), `sign_out_revokes_and_deletes` (`POST /api/auth/logout` puis jeton supprimé même si le serveur est injoignable).
- [ ] `npm run verify:rust` → PASS. Commit `Expose Request through Tauri commands`.

### Task 2b: Sonde réelle et fixtures réelles

- [ ] Petit binaire/test ignoré (`#[ignore]`, lancé à la main via `scripts/with-test-account.ps1`) qui, avec le compte de test Emby, appelle en lecture seule `version`, `emby-token`, `discover`, `search?q=dune`, `media/movie/…`, `media/tv/…`, `requests/quota`, `requests?mine=true`, `auth/sessions`, puis `logout`. Effets de bord connus : crée la fiche membre du compte de test dans Request et une entrée « Verification Viewer » dans Emby › Appareils. Aucune demande créée.
- [ ] Remplacer les fixtures par ces corps réels nettoyés (noms et ids de membres réels remplacés, jetons retirés), relancer T0/T1 (contrat Vitest + Rust) ; corriger les modèles si un champ réel diffère.
- [ ] Commit `Rebuild the Request fixtures from real responses`.

### Task 3: Client TS, Réglages › Demandes et onglet (états)

**Files:** Create `src/lib/requests/{api.ts,types.ts,labels.ts,labels.test.ts,RequestsSettings.svelte,RequestsTab.svelte,RequestsSetup.svelte}` ; Modify `src/lib/settings/Settings.svelte` (section « Demandes » après « Accueil »), `src/lib/library/LibraryBrowser.svelte` (entrée « Demander » dans `topnav` après les bibliothèques, avant Réglages ; état `requestsOpen`).

**Interfaces — Produces:** `requestsApi` (une entrée par commande T2, `invoke` comme `embyApi`), `toRequestsError(e) -> { kind, message }`, `statusLabel(MediaStatus)`, `requestStatusLabel(RequestStatus)`, `statusTone(...) -> "success"|"accent"|"warning"|"danger"|"muted"` (couleurs DESIGN.md : succès `#64C69A`, accent `#8FD3F5`, danger `#FF6961`, avertissement `#F5C451`).
- [ ] `labels.test.ts` : chaque statut → libellé exact de la spec.
- [ ] `RequestsSettings` (maquette Réglages A) : adresse (champ + « Enregistrer »), pastille d'état (Connecté en tant que {name} · {rôle} · API x.y.z), Appareils connectés (`deviceName ?? userAgent`, « cet appareil » si `current`, « Déconnecter » sinon), « Se déconnecter », note « aucun mot de passe ».
- [ ] `RequestsSetup` (maquette Réglages C) : non configuré (carte avec adresse + « Se connecter »), trop ancien (liste `missing`, « Réessayer », « Changer d'adresse »), injoignable, refusé.
- [ ] `RequestsTab` : `ur_status` au montage ; `ready` → segments Découvrir / Films / Séries / Mes demandes · N (contenu en T4–T6), sinon `RequestsSetup`.
- [ ] `npm run verify:web` → PASS. Commit `Add the Demander tab and its settings`.

### Task 4: Découvrir et Parcourir (desktop)

**Files:** Create `src/lib/requests/{Discover.svelte,Browse.svelte,MediaCard.svelte,InFlightStrip.svelte}` ; Modify `RequestsTab.svelte`.
- [ ] `MediaCard` : réutilise le style `PosterCard` (lift, focus), badge de statut en bas à gauche (`statusLabel`, `progress` en % pour `downloading`).
- [ ] `InFlightStrip` (bande C) : `ur_my_requests(null,1)` filtré sur `pending|approved|sent` + `available` des 7 derniers jours, 3 cartes max, masqué si vide ; « Toutes mes demandes (N) ».
- [ ] `Discover` : rangées `ur_discover` dans l'ordre ; « Tout voir » ouvre `Browse` pré-filtré selon `seeAll`.
- [ ] `Browse` (Parcourir A) : barre de filtres de la bibliothèque (Trier par : Popularité/Note/Date de sortie/Titre ; Genre via `ur_genres` ; Années ; Note minimale ; Réinitialiser), grille paginée au défilement (page suivante à 80 %), squelettes, vide « Aucun titre ne correspond à ces filtres ».
- [ ] Tests unitaires des fonctions pures (filtres → `BrowseFilters`, fusion de pages sans doublons `tmdbId`).
- [ ] `npm run verify:web` → PASS. Commit `Add Découvrir and Parcourir to the Demander tab`.

### Task 5: Fiche et demande (desktop)

**Files:** Create `src/lib/requests/{MediaPage.svelte,RequestDialog.svelte,seasons.ts,seasons.test.ts}` ; Modify `LibraryBrowser.svelte` (ouvrir une fiche Request ; « Lire » → item Emby par id).
- [ ] `seasons.ts` : `requestableSeasons(details, today = isoDay()) -> number[]` = règle « Saison demandable » ci-dessus (copiée d'Request) ; `isRequestable(details, today)` = règle « Titre demandable » ; `seasonsLabel(n)` → « Demander 1 saison » / « Demander N saisons ».
- [ ] `MediaPage` (Fiche A) : fond, titre, méta, genres, synopsis, distribution, statut global et par saison ; « Lire » si `embyItemId` (ouvre la fiche Emby via la commande existante de chargement d'un item par id ; à défaut ajouter `emby_item(item_id)`), « Demander » si `capabilities.canRequestMovies`/`canRequestTv` et quelque chose à demander, sinon « Rien à demander ».
- [ ] `RequestDialog` : film = confirmation + quota ; série = cases (non demandables grisées avec leur statut) ; quota `ur_quota` affiché (« 8 sur 10 restantes · 7 jours », masqué si `exempt`) ; erreurs serveur affichées telles quelles ; succès → statut mis à jour, toast « Demande envoyée ».
- [ ] `npm run verify:web` → PASS. Commit `Request a film or seasons from Viewer`.

### Task 6: Mes demandes et recherche (desktop)

**Files:** Create `src/lib/requests/{MyRequests.svelte,RequestTimeline.svelte,RequestSearch.svelte,searchMerge.ts,searchMerge.test.ts}` ; Modify `src/lib/search/SearchResults.svelte`, `RequestsTab.svelte`.
- [ ] `MyRequests` (A) : segments par statut avec `totals`, liste paginée, panneau à droite avec `RequestTimeline` (`events`, libellés français, dates relatives existantes du projet), « Annuler la demande » si `pending` (confirmation), « Lire » si `media.embyItemId`.
- [ ] `searchMerge.ts` : `toRequest(results: MediaSummary[]) -> MediaSummary[]` = sans `embyItemId` et non `blocked`.
- [ ] `SearchResults` : section « À demander · Request » sous les résultats Emby quand l'intégration est `ready` (requête parallèle, échec silencieux avec « Recherche Request indisponible »).
- [ ] Onglet Demander : champ de recherche → liste B (ligne : affiche, titre, méta, statut, action Lire/Demander/Voir).
- [ ] Preuve UI sans prise de contrôle du bureau : script `scripts/e2e/requests-ui.mjs` sur le modèle des scripts `scripts/e2e/*` (CDP), contre le serveur réel avec le compte de test, captures de : configuration, Découvrir, Parcourir, fiche film, fiche série + fenêtre de demande (sans envoyer), Mes demandes, Réglages › Demandes, état « trop ancien » (adresse d'un faux serveur local). Captures dans `docs/evidence/request/`.
- [ ] `npm run verify:web` + `npm run verify:rust` → PASS. Commit `Track requests and search Request from the library search`.

### Task 7: Client Kotlin Request (TV)

**Files:** Create `android-tv/app/src/main/java/io/github/quentinpn/viewer/tv/request/{Models.kt,RequestError.kt,RequestClient.kt,RequestStore.kt,RequestRepository.kt}` ; Modify `session/SessionStore.kt` (extraire `KeystoreTokenCipher` en `KeystoreCipher(alias: String)` réutilisable, alias session inchangé `viewer.session`), `AppServices.kt` (`requests(context)`), tests `app/src/test/java/io/github/quentinpn/viewer/tv/request/*`.
- Mêmes règles et mêmes erreurs que T1/T2 (sealed class `RequestError` avec messages français), `Json { ignoreUnknownKeys = true; explicitNulls = false }`, intercepteur Bearer seulement pour l'hôte de l'adresse, jeton chiffré (alias `viewer.request`) rangé par `{embyServerId}/{embyUserId}`, adresse dans les préférences.
- [ ] Tests MockWebServer reprenant les noms de T1/T2 en camelCase (`parsesEveryFixture` sur `../../contracts/request/fixtures`, `rejectsOldServer`, `exchangeSendsNoAuthorization`, `retriesOnceAfter401`, `rejectedAfterSingleRetry`, `rateLimitedExposesRetryAfter`, `mapsErrorBody`, `tokenNeverInToString`).
- [ ] `gradlew.bat -p android-tv :app:testDebugUnitTest` → PASS. Commit `Add the Request client to Android TV`.

### Task 8a: TV — onglet, réglages et états

**Files:** Create `ui/requests/{RequestsScreen.kt,RequestsSetupTv.kt,RequestsSettingsTv.kt}` ; Modify `ui/HomeBar.kt` (`BarItem.Requests` « Demander » avant Réglages), `MainActivity.kt` (état `requests: RequestsMemory?`), `ui/SettingsScreen.kt` (section « Demandes » : adresse au clavier système, état, Appareils connectés, Se déconnecter / Se connecter).
- [ ] États configuration / trop ancien / injoignable / refusé (maquette Réglages C TV). Gate : `gradlew.bat -p android-tv :app:testDebugUnitTest :app:lintDebug`. Commit `Add the Demander tab and settings to Android TV`.

### Task 8b: TV — Découvrir et Parcourir

**Files:** Create `ui/requests/{DiscoverTv.kt,BrowseTv.kt,MediaCardTv.kt}`.
- [ ] Segments, bande « en cours », rangées D-pad avec titre du focus sous la rangée ; Parcourir A version TV (pastilles qui ouvrent une liste de choix, grille 6 colonnes, page suivante en approchant la fin). Gate idem. Commit `Add Découvrir and Parcourir to Android TV`.

### Task 8c: TV — Fiche et demande

**Files:** Create `ui/requests/{MediaTv.kt,RequestDialogTv.kt,Seasons.kt}`, test `SeasonsTest.kt`.
- [ ] `Seasons.kt` = même règle que `seasons.ts` (tests `requestableSeasons` sur `media-tv.json`). Fenêtre centrée, OK coche, focus initial sur la première saison demandable, quota avant confirmation ; « Lire » → `openMovie`/`openSeries` par id Emby. Gate idem. Commit `Request a film or seasons from Android TV`.

### Task 8d: TV — Mes demandes et recherche

**Files:** Create `ui/requests/MyRequestsTv.kt` ; Modify `ui/SearchScreen.kt` (rangée « À demander »).
- [ ] Liste plein écran par statut, détail plein écran avec frise, « Annuler la demande » (confirmation) ; recherche Emby + rangée « À demander ». Gate : `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug`. Commit `Track requests and search Request on Android TV`.

### Task 9: Documentation, versions, essai réel

- [ ] `ARCHITECTURE.md`, `DESIGN.md` (onglet Demander), `ROADMAP.md`, `TESTING.md` (fixtures partagées).
- [ ] Versions (une seule montée : la fonctionnalité est livrée en un bloc) : desktop 1.5.0 (`package.json`, CHANGELOG), TV 1.4.0 (`versionCode` 9, CHANGELOG).
- [ ] Essai réel desktop contre le serveur Request réel (adresse dans debug.env) avec le compte de test (`debug.env`) : statut `ready`, Découvrir, fiche, quota ; **ne pas créer de demande réelle sans accord**. Sur la Shield : même parcours, puis désinstaller `io.github.quentinpn.viewer.tv.debug`.
- [ ] Commit `Document the Request integration and bump versions`.
