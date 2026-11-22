# Coffre d'identification Windows : crate `keyring` 3.6.3 contre Go (unité credentials)

Le code Rust (`src-tauri/src/emby/credentials.rs`) range le jeton avec `keyring::Entry::new(SERVICE, account)`, backend `windows-native` (Cargo.lock : `keyring` 3.6.3). Pour que les utilisateurs existants n'aient pas à se reconnecter, `go-app/internal/emby/credentials_windows.go` lit et écrit exactement les mêmes entrées. Source lue : `keyring-3.6.3/src/windows.rs` et `error.rs` dans le registre cargo local, le 2026-11-22.

## Format d'une entrée
- API : `CredWriteW`, `CredReadW`, `CredDeleteW` et `CredFree` d'advapi32, type `CRED_TYPE_GENERIC` (1), drapeaux 0.
- Nom de cible (`TargetName`) : `"{user}.{service}"`, soit `"{serverId}/{userId}.Viewer"` (ou `.Viewer-debug` pour un build de debug, ADR 0105). C'est la seule clé de recherche de Windows.
- `UserName` : le compte (`{serverId}/{userId}`). `TargetAlias` : vide. `Comment` : `keyring v3.6.3`. `Persist` : `CRED_PERSIST_ENTERPRISE` (3). Aucun attribut.
- Blob : le mot de passe en UTF-16 petit-boutiste, sans zéro final (`encode_utf16` puis `write_u16_into`).
- Lecture : un blob de longueur impaire, ou un UTF-16 invalide (surrogate isolé), donne `BadEncoding` (« Data is not UTF-8 encoded »). Un blob vide donne une chaîne vide.

## Erreurs
- `CredReadW` ou `CredDeleteW` qui échoue avec `ERROR_NOT_FOUND` (1168) donne `NoEntry` : `load` rend « pas de jeton », `delete` réussit.
- `ERROR_NO_SUCH_LOGON_SESSION` (1312) donne `NoStorageAccess`, tout autre code `PlatformFailure`. Le texte (`Platform secure storage failure: Windows error code 5`…) est repris tel quel dans `EmbyError::CredentialStore`.
- Contrôles avant tout appel, sur la longueur en octets UTF-8 comme `String::len` : compte au plus 513, cible non vide et au plus 32 767, mot de passe au plus 2 560 octets une fois en UTF-16.

## Choix Go
- Pas de dépendance nouvelle : `golang.org/x/sys/windows` (déjà présent) pour `NewLazySystemDLL`, `UTF16PtrFromString` et les codes d'erreur. x/sys n'exporte pas les fonctions `Cred*`, elles sont chargées à la main.
- `CREDENTIALW` est recopiée champ pour champ ; l'alignement naturel de Go donne la même disposition que le SDK Windows en amd64 et arm64.
- Le blob est copié avant `CredFree`, puis la copie Windows est effacée, comme `erase_secret`.
- Le nom du service (`Viewer` ou `Viewer-debug`) est un argument du constructeur : Go n'a pas de `cfg!(debug_assertions)`. L'app-shell choisira (MAP.md).

## Vérification
`TestWindowsStoreRoundTrip` et `TestWindowsStoreWritesTheKeyringFormat` écrivent une entrée au nom unique et jetable, puis la suppriment (`t.Cleanup` enregistré avant l'écriture). Le second relit l'entrée brute avec `CredReadW` et compare la cible, le `UserName`, le `Persist` et les octets du blob, paire de substitution comprise. Aucune entrée réelle n'est lue ni listée. Le test n'a pas été croisé avec une entrée écrite par le binaire Rust : la compatibilité repose sur la lecture du code du crate.
