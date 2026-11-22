# Request : reqwest et url contre net/http (unité request)

Code Rust : `src-tauri/src/request/` (client.rs, service.rs, mod.rs). Même crate reqwest 0.13.5 que le client Emby : voir `http-client.md` pour les délais, le proxy, TLS et l'encodage de la requête. Ce qui suit ne vaut que pour Request. Lu dans le code de l'unité et dans le registre cargo local le 2026-11-22.

## Erreurs d'envoi
- `From<reqwest::Error> for RequestError` : `is_decode() || is_body()` donne `InvalidResponse`, tout le reste `Unreachable`. Il n'y a ni `Timeout` ni `Cancelled` dans cet enum, au contraire d'EmbyError.
- En Go : toute erreur de `http.Client.Do` donne `Unreachable` (délai, connexion, contexte annulé), et toute erreur de lecture du corps `InvalidResponse`. Un contexte annulé (appel JS abandonné) finit donc en `Unreachable`, alors que tokio abandonne le futur sans erreur. Personne ne lit ce résultat.
- Un délai dépassé pendant la lecture du corps est `is_body()` dans reqwest, donc `InvalidResponse`, comme en Go.

## Statuts
- Pas de redirection suivie (`Policy::none()`) : un 3xx passe par `check` et devient `Server { status, "erreur d'Request (HTTP 3xx)" }`.
- `Retry-After` : la première valeur, seulement si elle est en ASCII visible (`HeaderValue::to_str`), puis `trim` et `u64::parse` (un `+` initial est accepté), bornée à 24 h. Sinon 60 s.
- Le texte `error` du serveur n'est gardé que s'il n'est pas blanc ; il est gardé tel quel, sans `trim`.
- `send` sur un 204 sans corps : `InvalidResponse`, puisque le corps vide n'est pas du JSON.

## En-têtes
- `RequestBuilder::header` fait un `append` : la requête d'image porte deux `Accept`, `application/json` puis `image/*`. Le client Go fait de même (`Header.Add`).
- `bearer_auth` : `Authorization: Bearer {jeton}`. L'échange `POST /api/auth/emby-token` part sans `Authorization`.

## Adresse (`normalize_address`)
- Rust passe par `Url::parse` (WHATWG). Go réutilise `emby.ParseServerURL`, qui porte déjà l'analyseur WHATWG du crate (hôte, IDNA, IPv4/IPv6, port par défaut), mais seulement sur `schéma://autorité` : sa règle de chemin (`/web`, `/emby`) ne doit pas s'appliquer ici, et sa règle locale est plus large (lien local, `.lan`, `.home.arpa`, ULA IPv6).
- Le chemin est normalisé à part : `/` et `\` séparent, `.` et `..` (aussi en `%2e`) sont résolus, l'ensemble « path percent-encode » est encodé.
- La règle locale d'Request est appliquée sur l'hôte déjà normalisé : IPv4 bouclage ou privé, IPv6 `::1` seulement (`Ipv6Addr::is_loopback`, donc pas `::ffff:127.0.0.1`), `localhost` ou `*.local`.
- `?` ou `#` n'importe où : refus, car pour http(s) ils ouvrent toujours une requête ou un fragment.
- Écarts possibles : seulement dans des chemins exotiques que l'analyseur WHATWG complet traiterait autrement (aucun vu dans les tests). Bizarrerie Rust gardée : `https://` perd ses `/`, puis reçoit `https://` : `https://https`.

## Délai après un 429
`as_secs() + 1` comme en Rust, calculé depuis l'échéance `now + Retry-After` : une attente de N s exactes donne N + 1. L'horloge du service est injectable (`Service.now`) pour que les tests ne dépendent pas de la finesse de l'horloge.

## Fichier `request.json`
`serde_json::to_vec_pretty` : indentation de 2 espaces, pas de saut de ligne final, `signedOut` jamais null. Écrit à côté (`request.json.tmp`) puis renommé. Le texte d'une erreur d'E/S est celui de l'OS vu par Go.
