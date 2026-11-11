# ADR 0105 — Version debug séparée sur le desktop

Statut : Accepté (2026-11-11). Complète l'ADR 0004 (stockage des jetons) et reprend pour Windows l'idée de l'ADR 0102.

## Contexte
La version debug, celle des scénarios CDP (`scripts/e2e`), et la version installée avaient le même identifiant (`io.github.quentinpn.viewer`) et le même service du Gestionnaire d'identification (`Viewer`). Les tests partageaient donc le `session.json`, le cache, les journaux, le stockage WebView et les jetons du propriétaire. Un nettoyage du coffre a ainsi supprimé le jeton du propriétaire. Le propriétaire demande un coffre séparé pour la version debug.

## Décision
- Les builds debug (`debug_assertions`) prennent l'identifiant `io.github.quentinpn.viewer.debug`. Ils le reçoivent dans `context()`, avant la création de l'app. Config (`session.json`), cache (images, `tls-roots.pem`), journaux et données WebView2 (`localStorage` : tri, filtres, volume) vont donc dans des dossiers à part. Le titre de la fenêtre se termine par « (debug) ».
- Le service du coffre devient `Viewer-debug` en debug (`credentials::SERVICE`). La cible Windows est donc `<serverId>/<userId>.Viewer-debug`, et la release garde `Viewer`.
- Les scénarios ne comptent et ne vérifient que les entrées `.Viewer-debug` (`gate2-ui`, `gate21-session`, `gate5-shelf`).
- `with-test-account.ps1` prend l'adresse du serveur dans le `session.json` de la version debug, sinon dans celui de la release (aucun secret dans ce fichier).
- `gate5-release`, le seul scénario qui lance l'app installée, s'arrête sans rien toucher si elle est connectée avec un autre compte que le compte de test. À la fin, il oublie la vignette du compte de test.

## Conséquences
- Les tests ne lisent, ne remplacent et ne suppriment plus la session ni les réglages du propriétaire.
- Le premier lancement de la version debug part de zéro : nouvel identifiant d'appareil Emby, pas de session. Les scénarios se connectent avec le compte de test.
- Les données de test laissées avant cette décision dans `io.github.quentinpn.viewer` (une vignette « claude » sans jeton) restent. Le propriétaire peut l'oublier depuis « Gérer ».
- `cargo test` est aussi un build debug : le test opt-in `windows_store_round_trip` écrit sous `Viewer-debug`.
