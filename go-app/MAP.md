# go-app : bugs portés et points ouverts

Le port Rust/Tauri vers Go + Wails v3 est terminé : la v1.7.0 est la première release Go, et `src-tauri/` a été retiré après elle. La carte complète du port (vagues, unités, décisions de chaque unité, checklist de bascule), que des commentaires du code Go citent encore (« MAP.md, … »), reste dans l'historique git : `git show 6d4a989:go-app/MAP.md`.

Ce fichier ne garde que ce qui sert encore : les bugs de l'app Rust portés tels quels, les points ouverts et les conventions du code Go.

## Bugs de l'app Rust portés tels quels (à corriger en Go)

Chaque correction se fait dans un commit à part qui nomme le changement.

- `internal/emby/server_url.go` (server_url.rs) : seul le premier `/web` du chemin est examiné. `http://h/website/web` garde donc `/website/web`, au lieu de s'arrêter au `/web` final. Impact faible : cela ne vise que des adresses copiées depuis le navigateur.
- `internal/artworkcache` (artwork_cache.rs) : l'éviction (`remove_empty_dirs`) peut supprimer le dossier qu'un `put` vient de créer, entre `create_dir_all` et l'écriture : ce `put` échoue (`NotFound`) sans autre effet. Les lecteurs d'une image servie depuis le réseau ignorent l'erreur. Impact faible ; une relance unique dans `put` le corrigerait.
- `internal/emby/client.go` (client.rs) : `image()` ne demande pas de jeton (pas de `require_token`). Seul le service vérifie la session avant l'appel. Sans effet aujourd'hui : un client sans jeton envoie l'en-tête sans `Token` et Emby répond 401.
- `internal/emby/session.go` (session.rs:495) : la réponse à `/Views` est écrite dans la session ouverte au moment où elle arrive, quelle qu'elle soit, et non dans celle qui a posé la question. Un changement de compte pendant l'appel peut donc y mettre les vues de l'autre compte. Impact faible.

## Points ouverts

- **Icône de l'exe** : le binaire Go n'a ni icône ni ressource de version (aucun `.syso`). `DisplayIcon` et les raccourcis ont l'icône générique ; seul l'installateur a la sienne (`build/windows/icon.ico`).
- **Coffre** : une session ouverte par l'ancienne app Tauri doit se rouvrir dans l'app Go sans mot de passe (même entrée `{serverId}/{userId}.Viewer`, format du crate keyring 3.6.3 relu octet par octet sur une entrée écrite par Go). Jamais croisé avec une entrée écrite par Rust : à constater sur la v1.7.0 installée.
- **CSP** : `connect-src` garde `ipc: http://ipc.localhost`, l'IPC de Tauri, inutile sous Wails (`cmd/viewer/csp.go`).
- **E2E** : les scénarios gate pilotent le build debug Go depuis le retrait de Tauri, mais n'ont pas été relancés ; seul `wails-smoke.mjs` a tourné (33/33 le 2026-11-22). `gate5-release.mjs` ne peut pas s'attacher à une release Go (aucun port DevTools). Sous Wails, la fenêtre hors écran peut prendre le premier plan à son ouverture (pas d'option « sans activation »).
- **Non vérifié à la main** :
  - mise à jour Go vers Go : pastille, « Redémarrer maintenant », installateur silencieux, app revenue à jour ; installateur à la signature altérée refusé (Réglages › Mises à jour : erreur `signature`) ;
  - installateur : `Viewer_x.y.z_x64-setup.exe /S /UPDATE /R` app ouverte (elle se ferme, l'installateur écrase, l'app revient) ; désinstallation (exe, dll, raccourcis et clé disparaissent, les données restent sauf case cochée) ; accents français lisibles ;
  - fermeture par ✕ pendant une lecture liée à une TV (`bye`), et lecture liée (invitation, `load`, pause, déliaison) ;
  - plein écran depuis une fenêtre maximisée, puis retour : la fenêtre redevient maximisée ;
  - événements `emby://library-changed` (nouveau titre sur l'accueil) et pastille de mise à jour dans l'UI ;
  - une deuxième instance s'ouvre (pas d'instance unique, comme sous Tauri) ;
  - échelle d'affichage à 125-150 %, deuxième écran ; clavier physique et focus après le plein écran (signalés bons par le propriétaire, non enregistrés).
- `staticcheck` n'est pas installé sur le poste.

## Goldens (`testdata/golden/`)

JSON de référence produit par l'ancienne app Rust, figé : plus rien ne le régénère, et il ne s'édite pas à la main. Un écart est un bug du code Go.

- Golden typé : jetons comparés dans l'ordre, nombres par valeur (serde écrit `0.0` là où Go écrit `0`). Tables `json!` : comparées sans tenir compte de l'ordre.
- Les messages d'erreur issus des crates `url` et `serde` se comparent sur `kind` seulement (voir `index.json`).
- `playback.*` `playback_start` et `engine_request` sont des miroirs faits à la main de `emby_play` : `cmd/viewer/commands_play.go` doit rester aligné sur eux.

## Conventions du code Go

- **Pont** : une seule méthode liée, `Bridge.Invoke(cmd, args)` (FQN `viewer/internal/bridge.Bridge.Invoke`), qui distribue par nom de commande ; les commandes s'enregistrent dans `cmd/viewer/commands*.go` et `commands_test.go` fige leur liste. Erreur : `bridge.MarshalError` ; une erreur qui implémente `json.Marshaler` est sérialisée telle quelle (`{kind, message}` pour `EmbyError`, `null` pour `bridge.ErrUnit`), toute autre devient une chaîne nue. Wails la place dans `cause` du `RuntimeError` JS, et `src/lib/native/wails.ts` la relance seule. Commande inconnue : `{kind: "unknownCommand", message}`.
- **JSON vers l'UI** (encoding/json v1, l'encodeur de Wails) : une liste est un `emby.List[T]`, écrit `[]` même nil ; un champ optionnel est un pointeur sans `omitempty` (écrit `null`), ou `omitzero` s'il doit disparaître ; un enum à variantes unité est une chaîne typée ou un int à `UnmarshalJSON` strict ; un enum taggé a un `MarshalJSON` à la main. Les entrées venues de l'UI (`ItemsOptions`, `TitlesSource`, `QualityCaps`) ont un `UnmarshalJSON` strict.
- **Réponses Emby** : décodeur strict par réflexion (`internal/emby/decode.go`) : casse exacte, clé en double refusée, champ requis balisé `emby:"required"`, null refusé sauf pointeur, `json.RawMessage` ou champ `emby:"option"`, rien après la valeur. Tout nouveau DTO porte ces balises.
- **Appels réseau** : un `context.Context` par appel ; annulé donne `cancelled`, délai dépassé `timeout`, autre erreur d'envoi `unreachable` (cause la plus profonde, jamais l'URL). `AccessToken` est masqué (`String`, `GoString`, `LogValue`) et refuse `MarshalJSON` ; tout journal qui peut contenir un jeton passe par `internal/redact`.
- **Lecteur** : `Shutdown` n'est jamais appelé depuis le puits d'événements ni en tenant un verrou que le puits prend ; il peut être émis deux fois si mpv quitte de lui-même.
- **Mises à jour** : `internal/updater` maison (l'updater de Wails n'échange qu'un fichier et ne lance pas d'installateur). Signature ed25519 pure sur `"viewer-update-v1" 0x00 version 0x00 SHA-512(installateur)`, champ `ed25519` de `desktop/latest.json` ; clé publique `updater.PublicKey` injectée par `-ldflags -X` (vide dans le dépôt : échec fermé).
- **Debug et release** : un `go build` sans drapeau donne une build debug (identifiant `io.github.quentinpn.viewer.debug`, coffre `Viewer-debug`, titre « (debug) », `player_open` permis, pas d'updater, `VIEWER_E2E_*` et `VIEWER_LIBMPV` lus). La release passe `-X viewer/internal/app.buildMode=release` (`scripts/build-wails-exe.ts`). Les identifiants `io.github.quentinpn.viewer`, le dossier WebView2 `%LOCALAPPDATA%\{id}\EBWebView`, le nom `viewer.exe` et le dossier d'installation sont ceux de l'app Tauri, gardés pour reprendre ses données.
- **Dépendances** : `golang.org/x/net` (`idna`, UTS 46 absent de la bibliothèque standard) et `github.com/coder/websocket` (déjà embarqué par Wails). Pas de nouvelle dépendance sans raison écrite dans `DECISIONS.md`.
