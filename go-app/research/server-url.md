# Adresse du serveur : crate `url` contre Go (unité emby-models)

Le code Rust (`src-tauri/src/emby/server_url.rs`) analyse l'adresse avec le crate `url` 2.5.8, qui suit le standard WHATWG URL. `net/url` de Go ne le suit pas. Plusieurs écarts changent le résultat, et certains changent `is_local`, donc le refus du HTTP en clair :

- port par défaut : `:80` et `:443` disparaissent avec `url`, pas avec Go ;
- casse de l'hôte : `url` le passe en minuscules, Go non ;
- IPv4 numériques : `127.1`, `0x7f.0.0.1`, `2130706433` et `0300.0250.1.10` sont des IPv4 pour `url`, des noms de domaine pour Go ;
- IPv6 : `url` écrit la forme canonique `[::1]` et `[::ffff:c0a8:101]`, jamais la forme pointée ;
- segments `.`, `..` et `%2e` : `url` les résout ;
- `\` vaut `/` ;
- tabulations et sauts de ligne : `url` les retire partout ;
- encodage du chemin avec le jeu `PATH` : `{`, `}`, `` ` ``, `"`, `<`, `>`, l'espace et tout octet non ASCII sont encodés, mais pas `^` ni `|`.

`go-app/internal/emby/server_url.go` écrit donc l'analyseur WHATWG des schémas spéciaux, sans `net/url`.

## Vérification
Le 2026-11-22, un projet cargo jetable a affiché `Url::parse` du crate pour une cinquantaine d'entrées limites. Il était construit hors ligne (`--offline`) avec `url = "=2.5.8"`, `serde_json = "=1.0.154"` et le Cargo.lock de src-tauri. Il n'a exécuté que `Url::parse`, pas `ServerUrl::parse` : la suite des résultats attendus de `TestServerURLFollowsTheURLCrate` est déduite de la lecture de server_url.rs, et non exécutée en Rust :
- le retrait de `/web` et de `/emby` ;
- `set_path` ;
- la barre oblique finale retirée.
Une hypothèse n'est pas vérifiée : `set_path` laisserait intact un chemin déjà normalisé, par exemple `//x`. Le même projet a vérifié l'écriture des nombres par serde_json, utilisée par `person_id` et `collection_refs` :
- les exposants positifs ont un `+` : `1e+16`, `1.8446744073709552e+19` ;
- `-0` devient `-0.0` ;
- `1e3` devient `1000.0` ;
- `0.000001` devient `1e-6`.

## Écarts restants (assumés)
- **Hôte non ASCII (IDNA).** Plus d'écart : `golang.org/x/net/idna` convertit `émby.local` en `xn--mby-9la.local`, avec le profil du crate `url` (UTS 46 non transitoire, traits d'union et longueurs DNS non vérifiés, bidi et joiners vérifiés). Un nom IDNA invalide donne `invalidUrl` (« invalid international domain name »).
- **Textes d'erreur.** Les textes anglais du crate (`empty host`, `invalid port number`, `invalid IPv4 address`, `invalid IPv6 address`, `invalid domain character`, `relative URL without a base`) sont repris tels quels. Pour quelques entrées, le crate donne un autre texte que Go : pour `http://a b.local`, il répond `invalid international domain name`, parce que l'IDNA échoue avant la vérification des caractères. Le `kind` est toujours `invalidUrl`. index.json ne compare que le `kind` pour ces messages.
- **Schémas non spéciaux et `file:`.** Seul le schéma est lu, puis l'adresse est refusée avec « schéma … non pris en charge ». Le crate pourrait échouer plus tôt, sur l'hôte d'un schéma non spécial, avec un autre texte. Le `kind` reste `invalidUrl` dans tous les cas.
- **Nombre hors de la plage f64** (`1e400`) dans un `Id` : serde refuse l'item entier, alors que Go ignore seulement l'id.
