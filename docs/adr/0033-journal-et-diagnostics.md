# ADR 0033 — Journal local et diagnostics de plantage

Statut : Accepté (2026-11-07).

## Contexte
`PRODUCT.md` (P0) demande des « diagnostics de plantage sans secrets ». Le code Rust appelait déjà `log::info!/warn!/debug!`, mais aucun logger n'était installé, en debug comme en release : rien n'était enregistré, et un panic ne laissait aucune trace hors de la console. Les messages peuvent contenir des URL de flux (`api_key=`), des en-têtes (`X-Emby-Token`, `X-Emby-Authorization`) ou des corps JSON (`Pw`, `AccessToken`).

## Décision
- **Un backend `log` maison, sans nouvelle dépendance** (`src-tauri/src/diagnostics.rs`, environ 250 lignes hors tests) : le crate `log` est déjà là ; la date UTC est calculée à la main (algorithme de H. Hinnant).
- **Fichier** : `viewer.log` dans le dossier des journaux de l'application (`app_log_dir`, soit `%LOCALAPPDATA%\io.github.quentinpn.viewer\logs`). Les builds debug acceptent `VIEWER_E2E_LOG_DIR` pour les scénarios.
- **Rotation par taille** : au-delà de 2 Mio, le fichier devient `viewer.1.log`, qui devient `viewer.2.log` ; le plus ancien est supprimé. Il y a donc au plus 3 fichiers, environ 6 Mio. Un enregistrement est coupé au-delà de 64 Kio. Si la rotation échoue, l'écriture continue dans le fichier courant.
- **Niveaux** : en release, info et au-dessus pour l'application (`viewer_lib::*`, `mpv`, `panic`). En debug, debug et au-dessus, avec copie sur stderr. Les dépendances (tao, wry, reqwest…) n'écrivent que warn et error.
- **Rédaction à l'écriture** : chaque ligne passe par `diagnostics::redact` avant d'atteindre le fichier, quel que soit l'appelant. Sont rédigées les valeurs de `api_key`, `apikey`, `x-emby-token`, `x-mediabrowser-token`, `accesstoken`, `access_token`, `token`, `password`, `passwd` et `pw`, quelle que soit la casse et quelle que soit la forme : `clé=valeur`, `clé: valeur` ou JSON `"clé":"valeur"`. Les valeurs de `authorization` et `x-emby-authorization` sont rédigées jusqu'à la fin de la ligne. La partie `utilisateur:mot de passe@` des URL l'est aussi. La rédaction est volontairement large : `token: expiré` perd aussi son mot. `mpv.rs` réutilise la même fonction au lieu de la sienne.
- **Panic** : un hook écrit le message, l'emplacement, le nom du thread et la pile (`Backtrace::force_capture`) dans le journal, vide le tampon, puis appelle le hook précédent. Les symboles sont retirés du build release (`strip = true`) : la pile n'y donne que des adresses, pas des noms de fonctions. Un panic levé pendant que le logger tient son verrou n'écrit rien plutôt que de bloquer.
- **Réglages › « Diagnostics »** : un bouton « Ouvrir le dossier des journaux » appelle `diagnostics_open_log_dir`. La commande ne prend aucun argument : Rust choisit le dossier, le crée au besoin et lance `%SystemRoot%\explorer.exe` par chemin complet. Aucune permission shell ou opener n'est ajoutée aux capabilities.
- **Rien n'est envoyé** : pas de télémétrie, le propriétaire décide de partager ou non un fichier.

## Alternatives
- **`tauri-plugin-log`** : rotation et cibles prêtes à l'emploi, mais il tire `fern`, `time` et un pont JS avec ses propres permissions, et il n'offre aucune rédaction : il faudrait un formateur maison quand même. Rejeté.
- **`flexi_logger` / `log4rs`** : rotation complète, mais plusieurs dépendances (dont `chrono`) pour 100 lignes de rotation. Rejeté.
- **`tracing` + `tracing-appender`** : migration de tous les appels `log::`, et rotation par date seulement, pas par taille. Rejeté.
- **Rédiger à la source, appel par appel** : un oubli suffit à fuiter ; la rédaction centrale reste nécessaire. Les appels existants n'écrivent déjà que des `kind()` d'erreur.
- **`tauri-plugin-opener` pour ouvrir le dossier** : il ouvre n'importe quel chemin autorisé par la portée et expose une API au WebView. Une commande sans argument est plus étroite. Rejeté.

## Conséquences
- Un problème de lecture, de réseau ou un panic laisse une trace locale datée en UTC, sans secret connu.
- La rédaction repose sur des noms de clés : un secret écrit sous une autre clé ou en texte libre ne serait pas reconnu. Les nouveaux messages doivent continuer à n'écrire que des genres d'erreur.
- Le dossier du journal contient le nom du compte Windows dans son chemin ; il n'est pas affiché dans l'interface.
- Testé : rédaction, rotation, niveaux et format (tests Rust) ; message d'erreur (Vitest) ; section affichée, journal écrit au démarrage, panic volontaire (`VIEWER_E2E_PANIC`, builds debug) enregistré avec sa pile et rédigé (`gate3-settings`). L'ouverture de l'explorateur n'est pas cliquée par le scénario, pour ne pas ouvrir de fenêtre sur le bureau.
