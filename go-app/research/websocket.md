# Socket de session Emby : tokio-tungstenite 0.30.0 contre coder/websocket 1.8.14 (unité library-events)

Le code Rust (`src-tauri/src/emby/library_events.rs`) ouvre le socket de session avec `tokio_tungstenite::connect_async` (Cargo.toml : `tokio-tungstenite` 0.30.0, fonction `native-tls`). Le port Go (`go-app/internal/libraryevents/socket.go`) passe par `github.com/coder/websocket` v1.8.14, déjà présent en dépendance indirecte, maintenant directe. Sources lues le 2026-11-22 : `tungstenite-0.30.0/src/protocol/mod.rs` et `tokio-tungstenite-0.30.0/src/connect.rs` dans le registre cargo local, et `go doc github.com/coder/websocket` (Conn.Read, SetReadLimit, Dial).

## Ce qui est repris
- Connexion : `connect_async(url)` sans configuration, donc `WebSocketConfig::default()`. En Go : `websocket.Dial(ctx, u, nil)`. L'URL porte `api_key` (ADR 0110, seule exception au jeton hors URL) et n'est jamais journalisée.
- Taille maximale d'un message : tungstenite autorise 64 Mio par défaut (`max_message_size: Some(64 << 20)`). coder/websocket s'arrête à 32 768 octets : `conn.SetReadLimit(64 << 20)` rétablit la limite de Rust, sinon une grosse liste de sessions fermerait le socket (`StatusMessageTooBig`).
- Trames : seules les trames texte sont lues (`Message::Text`). Les trames binaires sont ignorées. Une trame Close, une erreur ou la fin du flux terminent l'écoute.
- KeepAlive : `tokio::time::interval` part immédiatement, donc Rust envoie `{"MessageType":"KeepAlive"}` dès la connexion, puis toutes les 30 s. Go l'envoie explicitement à la connexion, puis sur un `time.Ticker` de 30 s. Un envoi qui échoue termine l'écoute.
- Contrôle de session toutes les secondes : si l'utilisateur s'est déconnecté ou si l'URL a changé, une trame Close (sans code en Rust, `StatusNormalClosure` en Go) est envoyée puis l'écoute s'arrête. En Go, la fermeture part dans sa propre goroutine, pour qu'une nouvelle session se reconnecte tout de suite.
- Délai de stabilisation : 2 s après le dernier changement de bibliothèque, et un changement vu juste avant la coupure est quand même annoncé.
- Ping et Pong : tungstenite répond aux Ping lui-même. coder/websocket le fait pendant `Read`, que la goroutine de lecture appelle en continu.

## Catégories d'erreur (`error_kind`)
| Rust (`tungstenite::Error`) | Go |
|---|---|
| `Http` avec statut 401 : « jeton refusé » | réponse de la poignée de main avec 401 |
| autre `Http` : « réponse HTTP » | toute autre réponse non nulle |
| `Url` : « adresse » | `*url.Error` avec `Op == "parse"` |
| `Tls` : « TLS » | `tls.CertificateVerificationError`, `x509.UnknownAuthorityError`, `HostnameError`, `CertificateInvalidError`, `tls.RecordHeaderError`, `tls.AlertError` |
| `Io` : « réseau » | `net.Error` |
| le reste : « protocole » | le reste |

## Écart connu
- TLS : native-tls passe par schannel. Go sous Windows vérifie la chaîne avec `CertGetCertificateChain` quand `RootCAs` est nil (même magasin racine, voir `tls-roots.md`). Les textes d'erreur diffèrent, mais seules les catégories ci-dessus sont journalisées.
