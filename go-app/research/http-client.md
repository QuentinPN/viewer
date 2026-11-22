# Client HTTP Emby : reqwest 0.13.5 contre net/http (unité emby-client)

Le code Rust (`src-tauri/src/emby/client.rs`) utilise reqwest 0.13.5 avec `default-features = false, features = ["native-tls", "json", "http2"]`. Source lue dans le registre cargo local le 2026-11-22 (`reqwest-0.13.5/src/error.rs`, `proxy.rs`, `async_impl/response.rs`, `form_urlencoded-1.2.2`).

## Ce qui est repris
- Délais : connexion 5 s (`connect_timeout`), requête entière 15 s (`timeout`, corps compris). En Go : `net.Dialer.Timeout` et `TLSHandshakeTimeout` pour la connexion, `http.Client.Timeout` pour le total.
- Aucune redirection suivie (`Policy::none()`) : `CheckRedirect` rend `http.ErrUseLastResponse`, et la réponse 3xx passe par `check`.
- Pas de nouvelle tentative ni de backoff dans client.rs : le client Go n'en ajoute pas. Les reprises sont du ressort de session.rs.
- `User-Agent: Viewer/{version}`, `Accept: application/json`, `X-Emby-Authorization` sur chaque requête. Le jeton ne va jamais dans l'URL, sauf `library_socket_url` (`api_key`, comme Rust).
- Pas de gzip (fonction `gzip` absente, même après unification) : `DisableCompression`, donc pas d'`Accept-Encoding` envoyé.
- HTTP/2 négocié par ALPN sur TLS : `ForceAttemptHTTP2`, sans quoi Go le coupe dès qu'un `DialContext` est fourni.
- Proxy : **écart ouvert.** `cargo tree -e features -i reqwest` montre que `tauri-plugin-updater` active `system-proxy` par unification des fonctions (ainsi que `stream` et `rustls-no-provider`, mais pas gzip). reqwest lit donc les variables d'environnement et, sous Windows, le proxy système du registre (Internet Settings, via hyper-util). Le client Go ne lit que l'environnement (`http.ProxyFromEnvironment`), et ignore toujours le proxy pour `localhost` et les boucles locales. Sans effet sur un poste sans proxy ; à reprendre si le propriétaire en configure un (`x/sys/windows/registry`, sans dépendance nouvelle).
- TLS : native-tls passe par schannel et le magasin racine de Windows. Go sur Windows vérifie la chaîne avec `CertGetCertificateChain` quand `RootCAs` est nil : même magasin, même validation du nom.

## Encodage de la requête
`Url::parse_with_params` encode avec `form_urlencoded::byte_serialize` : seuls `*-._`, les lettres et les chiffres ASCII restent tels quels, l'espace devient `+`. `url.QueryEscape` garde `~` et encode `*` : le client Go a donc son propre encodeur (`formEncode`). L'ordre des paramètres est celui du code, pas l'ordre alphabétique de `url.Values.Encode`.

## Erreurs (`From<reqwest::Error>`)
- Envoi : délai dépassé donne `Timeout` ; échec de connexion ou de requête donne `Unreachable(cause racine)` ; le reste donne `InvalidResponse(cause racine)`. En Go, net/http ne distingue pas `is_connect` d'`is_request` : toute erreur d'envoi autre qu'un délai donne `Unreachable`, avec le texte de l'erreur la plus profonde (jamais celui de `*url.Error`, qui contient l'URL).
- Lecture du corps : reqwest la classe `Decode` (`response.rs`, `do_bytes`), donc `InvalidResponse("JSON illisible")`, sauf un délai dépassé qui reste `Timeout`.
- Construction de la requête : une erreur du constructeur n'est ni délai, ni décodage, ni connexion, ni requête pour reqwest : `InvalidResponse(cause racine)`.
- Annulation : tokio annule en abandonnant le futur, sans erreur. En Go, un `context` annulé par l'appelant donne `Cancelled` (décision MAP.md).
- Les textes des causes racines diffèrent entre hyper et Go : seuls les `kind` se comparent.

## Décodage des réponses
serde respecte la casse des clés, refuse null pour un champ non `Option`, refuse un champ requis absent et une clé en double, et ignore les clés inconnues. encoding/json v1 ne fait rien de tout cela, et encoding/json/v2 ne refuse ni null ni l'absence d'un champ. `decode.go` écrit donc un décodeur strict par réflexion ; un champ requis porte la balise `emby:"required"` (les champs non `Option` sans `#[serde(default)]` de models.rs, listés par script), un `Option` gardé en slice ou map la balise `emby:"option"`.
