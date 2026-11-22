# Mises à jour du bureau sans tauri-plugin-updater (updater.rs)

Sources :
- le guide et la référence du manifeste de l'updater Wails v3, par context7 (`/llmstxt/v3_wails_io_llms_txt`, « updater ») ;
- le code de `pkg/updater` de Wails v3.1.0, dans le cache de modules (`verify.go`, `providers/endpoint/endpoint.go`) ;
- tauri-plugin-updater 2.14.1 et minisign-verify 0.3.0, dans `~/.cargo/registry` ;
- le modèle NSIS des exemples Wails (`examples/*/build/windows/nsis`).

Aucune connexion au Gitea ni à l'Emby : les tests ne parlent qu'à un `httptest` sur localhost.

## Ce que fait le Rust aujourd'hui (plugin 2.14.1)
- **Vérification.**
  - `GET` de l'endpoint avec `Accept: application/json`. 204 veut dire à jour ; un statut hors 2xx est sauté, puis finit en `ReleaseNotFound`.
  - Un corps qui n'est pas du JSON est une erreur reqwest (catégorie `network`), renvoyée tout de suite. Un JSON de mauvaise forme est `Serialization` (`manifest`).
- **Manifeste.**
  - `version` (alias `name`) : tous les `v` de tête sont retirés, puis la version est lue en semver strict.
  - `notes` et `pub_date` (RFC 3339) sont optionnels.
  - Ensuite, soit `platforms` (cibles `windows-x86_64-nsis` puis `windows-x86_64`, chacune `{url, signature}`), soit `url` et `signature` à la racine.
  - La comparaison `release.version > current` vient **avant** la recherche de cible : une version qui n'est pas plus récente n'est jamais une erreur.
- **Téléchargement.** Le fichier est téléchargé entièrement en mémoire, avec `Accept: application/octet-stream`. Un statut hors 2xx est `Network`. La signature est vérifiée ensuite.
- **Signature minisign.**
  - Les signatures publiées sont en `ED` (préhachées en BLAKE2b-512, octets `0x45 0x44`, vu sur le `.sig` de la 1.3.1).
  - Ensuite la signature globale est vérifiée, sur la signature et le commentaire de confiance.
  - **Version signée** (`verify_signed_version`, updater.rs:1912-1958 du plugin).
    - `requireSignedVersion` désactivé n'agit que sur une signature **sans** `version:`.
    - Quand le commentaire de confiance porte `version:X.Y.Z`, un écart avec la version du manifeste est refusé (`SignedVersionMismatch`). C'est le cas des `.sig` réels de 1.2.0, 1.3.0 et 1.3.1.
    - Le Rust bloque donc aujourd'hui le rejeu d'un ancien installateur authentique annoncé comme plus récent.
    - La phrase de l'ADR 0112 (« le numéro de version du manifeste n'est pas signé ») est fausse pour ces releases.
- **Installation.**
  - Contrôle de l'en-tête `MZ` (`infer::app::is_exe`).
  - Écriture dans `%TEMP%\Viewer-<v>-updater-XXXX\Viewer-<v>-installer.exe`.
  - `ShellExecuteW(NULL, "open", fichier, "/S /UPDATE /R /ARGS", NULL, SW_SHOW)`, puis `std::process::exit(0)`. Le dossier temporaire n'est jamais supprimé.
  - Arguments en mode `quiet` avec `restart_after_install` (vrai par défaut). Les arguments de l'app sont vides, d'où `/ARGS` en dernier, sans rien après.

## L'updater de Wails v3 (v3.1.0, `pkg/updater`)
- **(a) Endpoint HTTP en clair sur le LAN : oui.**
  - Le provider `endpoint` accepte une URL de manifeste `http`.
  - Une URL d'artefact doit être `http` ou `https` (`endpoint.go`, `resolved.Scheme`).
  - Seul le passage de `https` à `http` est refusé pour les en-têtes.
- **(b) Manifeste produit depuis les releases Gitea : oui, mais dans un autre format.**
  - C'est le « Wails Update Manifest » : `version`, `notes`, `artifacts[]` avec `url`, `platform`, `arch`, `digestAlgo` et `digest`, `signatureAlgo` et `signature`.
  - publish.ts pourrait l'écrire à côté de `latest.json`.
- **(c) Sans fenêtre : oui.** Avec `Window: updater.WindowNone`, l'UI écoute les événements `updater:*` et appelle `CheckAndInstall`.
- **(d) Signatures : `ed25519`, `ed25519ph` (SHA-512) et `ecdsa-p256`**, vérifiées contre `Config.PublicKey`, en PEM ou en octets bruts. Une signature sans clé épinglée échoue fermé. **Pas de minisign.**
- **Bloquant : le mode d'application.**
  - `Restart` relance le binaire en mode « helper », qui **remplace un seul fichier**. La cible est l'exe, ou l'unique entrée de premier niveau d'un `.zip`.
  - Il ne lance **jamais** d'installateur : `.msi` n'est pas pris en charge, et le sélecteur par défaut ignore `*-installer.exe`.
  - Notre installation, elle, est un installateur NSIS. Il pose `libmpv-2.dll` à côté de l'exe (chargée par `internal/mpv`), les raccourcis et la clé de désinstallation.
  - Un échange de l'exe seul ne mettrait donc jamais libmpv à jour. Il laisserait aussi la version affichée dans « Applications » en retard.
  - Les événements `updater:*` et l'état interne ne correspondent pas non plus au contrat actuel : `app://update-ready`, `CheckOutcome`, le verrou partagé et le mode debug `available`. Il faudrait une couche d'adaptation aussi grosse que l'implémentation elle-même.

## Décision : une petite implémentation maison (`internal/updater`)
Elle reproduit le plugin point par point (voir plus haut) : même contrat UI, même chemin NSIS, même manifeste `desktop/latest.json`. Elle n'utilise que la bibliothèque standard et `golang.org/x/sys/windows` (`ShellExecute`, déjà une dépendance), sans nouvelle dépendance.

- **Signature : ed25519 pur sur un message qui lie la version** (`crypto/ed25519`), contre une clé publique épinglée.
  - Le message est `"viewer-update-v1" 0x00 version 0x00 SHA-512(installateur)` (`SignedMessage`), où `version` est la semver canonique du manifeste, sans `v`.
  - Une version échangée, ou un ancien installateur rejoué sous un numéro plus récent, donne `signature`, comme `SignedVersionMismatch` en Rust.
  - La version est toujours signée : il n'y a pas de cas « signature sans version ».
  - La clé est `updater.PublicKey`, la base64 de 32 octets. Elle est injectée au build par `-ldflags "-X viewer/internal/updater.PublicKey=…"` ; vide dans le dépôt, tout échoue fermé (catégorie `signature`).
  - Côté publication, `node:crypto` signe sans autre outil : `crypto.sign(null, message, cléPrivéeEd25519)` donne de l'Ed25519 pur (recette exacte dans MAP.md, release-pipeline). C'est vérifié sur ce poste : une signature Node passe `ed25519.Verify` en Go (paire de clés éphémère, dans le scratchpad).
  - Le message contient le SHA-512 de l'installateur, pas ses octets : il reste court, et le préfixe sépare cet usage de la clé de tout autre.
- **Manifeste : le même `latest.json`, avec un champ en plus.**
  - Le champ est `platforms["windows-x86_64"].ed25519`, à côté de `url` et `signature`.
  - Le plugin Tauri n'a pas `deny_unknown_fields`, donc les apps Tauri encore installées l'ignorent et continuent de vérifier leur `signature` minisign.
  - Un seul fichier sert donc les deux apps pendant la transition.
  - Chaque entrée de `platforms` doit avoir une `url` absolue valide et une signature (`signature` ou `ed25519`). Sinon tout le manifeste est refusé (`manifest`), avant la comparaison, comme le fait la désérialisation du plugin.
  - Une cible ne compte que si elle a `ed25519`. Sinon on passe à `windows-x86_64`, comme le plugin passe à la cible suivante.
  - Si les cibles présentes n'ont que la minisign (manifeste purement Tauri), la catégorie est `signature`.
- **Écartés.**
  - *Réutiliser la clé minisign.* La vérification demande BLAKE2b-512, absent de la bibliothèque standard, donc une nouvelle dépendance (`golang.org/x/crypto/blake2b`) plus le format minisign à analyser. Elle aurait évité une nouvelle clé, mais la consigne demande `crypto/ed25519` et une clé distincte.
  - *L'updater Wails.* Il échange un fichier sans lancer l'installateur (voir plus haut).
  - *Signer les octets seuls* : écarté, car ce serait plus faible que le Rust, qui refuse un écart de version signée (voir plus haut). La première version de ce port le faisait ; corrigé après relecture.

## Points NON VÉRIFIÉS (pour l'unité release-pipeline)
- Le modèle NSIS de Wails a `REQUEST_EXECUTION_LEVEL` à `admin` par défaut, installe dans `$PROGRAMFILES64` et ne copie que l'exe. Il ne connaît ni `/UPDATE` ni `/R`, alors que `/S` est natif à NSIS.
  - L'installateur Tauri, lui, est en `currentUser` (`%LOCALAPPDATA%\Viewer`, clé de désinstallation dans HKCU), relance l'app sur `/R` et ne demande pas d'élévation.
  - Il faudra un `project.nsi` maison : par utilisateur, `libmpv-2.dll`, relance sur `/R`. Rien de cela n'a été construit ni essayé.
- On ne sait pas si un installateur Wails posé par-dessus une installation Tauri reprend le même dossier et la même clé de désinstallation, ni s'il laisse des restes.
- Le proxy système : reqwest lit le proxy du registre, Go seulement l'environnement (déjà relevé dans `research/http-client.md`). Sans effet sur le LAN.
