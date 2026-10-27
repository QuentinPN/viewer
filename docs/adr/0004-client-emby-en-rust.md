# ADR 0004 — Client Emby en Rust, jeton dans le Gestionnaire d'identification Windows

Statut : Accepté pour la Gate 2 (2026-10-27). La politique HTTP/HTTPS est à revoir quand un accès distant sera configuré.

## Contexte
ARCHITECTURE.md range `EmbyClient` parmi les services applicatifs sans préciser la couche. CLAUDE.md impose aussi plusieurs contraintes :
- le jeton est stocké dans un coffre du système ;
- il n'apparaît jamais dans les logs, SQLite ou les URLs ;
- TLS est validé ;
- l'UI ne construit jamais d'URL média.

Serveur réel, vérifié avec l'autorisation de l'utilisateur (lecture seule, compte de test non administrateur) :
- Emby Server **4.10.1.0** sous Linux (QNAP), joignable en **HTTP uniquement** sur le LAN : le port HTTPS 8920 refuse la connexion.
- La spec OpenAPI 3.0.1 servie par le serveur (`/emby/openapi.json`) sert de référence de contrat :
  - `POST /Users/AuthenticateByName` prend `{Username, Pw}` et renvoie `{User, SessionInfo, AccessToken, ServerId}` ;
  - l'en-tête `X-Emby-Authorization: Emby Client=…, Device=…, DeviceId=…, Version=…[, Token=…]` est accepté ;
  - `POST /Sessions/Logout` renvoie 204, après quoi le jeton donne 401 ;
  - un mauvais mot de passe renvoie 401 avec un message localisé.
- La spec annote `/System/Info/Public` « Requires authentication as user », mais le serveur y répond sans authentification. Les annotations d'authentification de la spec ne sont donc pas fiables : il faut vérifier sur le serveur.

## Décision
1. **`EmbyClient` en Rust** (`src-tauri/src/emby/`), avec `reqwest` 0.13 en async.
   - Le jeton ne quitte jamais Rust.
   - Pas de CORS ni de CSP à élargir.
   - Le même en-tête pourra être passé à mpv (`http-header-fields`, déjà en place depuis la Gate 1).
   - L'UI reçoit des modèles typés sans jeton.
2. **TLS : `native-tls`** (SChannel). La validation suit le magasin de certificats Windows.
   - Un certificat QNAP auto-signé n'est accepté que si l'utilisateur l'a approuvé dans Windows.
   - Jamais de désactivation de la vérification.
   - `rustls` a été écarté : le backend par défaut de reqwest 0.13 (aws-lc) exige CMake et NASM, absents du poste.
3. **Jeton dans le Gestionnaire d'identification Windows** via `keyring` 3 (`windows-native`).
   - Une entrée par couple serveur/utilisateur : service `Viewer`, compte `<ServerId>/<UserId>`.
   - Les métadonnées non secrètes (URL, ServerId, nom du serveur, UserId, nom d'utilisateur) et un `DeviceId` stable, généré une fois, vont dans `session.json` du dossier de configuration de l'app.
   - Le mot de passe n'est jamais conservé.
4. **Politique d'URL**.
   - Schémas `http` et `https` uniquement.
   - Pas d'identifiants, de requête ni de fragment dans l'URL.
   - Un éventuel préfixe de chemin de reverse proxy est conservé.
   - **HTTP n'est accepté que vers une adresse locale** : IP privée, loopback ou link-local, `localhost`, `*.local`, `*.lan`, `*.home.arpa`. L'UI signale alors une connexion non chiffrée.
   - Une adresse publique exige HTTPS. Sinon, le mot de passe et le jeton circuleraient en clair sur Internet.
5. **Identification du client** : `Client="Viewer"`, `Device=<nom du PC>`, `DeviceId=<UUID stable>`, `Version=<version de l'app>`. Les guillemets et les virgules sont retirés des valeurs.
6. **Erreurs typées et sans secret**, présentées en français :
   - `invalidUrl`, `insecureRemoteUrl`, `unreachable`, `timeout`, `invalidCredentials`, `forbidden`, `server` ;
   - `invalidResponse`, `notSignedIn`, `credentialStore`.
7. **Délais** : connexion 5 s, requête 15 s. Pas de nouvelle tentative automatique sur l'authentification, qui n'est pas idempotente. Les nouvelles tentatives avec backoff viendront avec les lectures de bibliothèque.

## Alternatives
- **Client en TypeScript** (fetch depuis la WebView). Le jeton vivrait côté JS, exposé à tout script de la WebView et à ses journaux. Il faudrait autoriser l'origine Emby dans la CSP. Écarté.
- **Clé d'API administrateur.** Interdite par le skill emby-api : elle contourne les droits utilisateur.
- **Jeton chiffré dans un fichier** (DPAPI). Possible, mais le Gestionnaire d'identification est le mécanisme standard, visible et révocable par l'utilisateur.
- **Désactiver la vérification TLS pour un certificat auto-signé.** Interdit par CLAUDE.md.

## Conséquences
- Chaque nouvel appel Emby demande une commande Tauri typée. C'est plus de code, mais la surface est contrôlée.
- Le serveur de l'utilisateur ne propose pas HTTPS. La connexion LAN fonctionne, mais le jeton y circule en clair. C'est un risque accepté sur un réseau domestique, signalé dans l'UI. Un accès distant exigera HTTPS (certificat Let's Encrypt via QNAP ou reverse proxy).
- Chaque authentification crée ou met à jour un appareil côté serveur, identifié par le `DeviceId` : c'est un effet visible dans le tableau de bord Emby. La sonde de développement a enregistré l'appareil `viewer-dev-probe`.

## Validation
- Tests Rust contre un faux serveur HTTP local :
  - en-tête d'auth et corps envoyés ;
  - jeton absent des URLs ;
  - 401 traduit en `invalidCredentials` ;
  - réponse invalide, serveur injoignable ;
  - déconnexion.
- Tests unitaires de la politique d'URL.
- Test d'intégration `#[ignore]` contre le serveur réel. Identifiants fournis par variables d'environnement, jamais dans git.
- Restant : HTTPS réel avec un certificat valide ou auto-signé approuvé, et le comportement derrière un reverse proxy. Rien de cela n'est disponible aujourd'hui.
