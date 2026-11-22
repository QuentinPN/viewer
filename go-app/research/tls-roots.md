# Racines TLS de Windows pour mpv (unité tls-roots)

## Besoin
`player/tls_roots.rs` exporte les certificats racine de Windows dans un fichier PEM, passé à mpv par `tls-ca-file` : l'OpenSSL de FFmpeg n'a pas de magasin de certificats. `mpv.rs` appelle `tls_roots::refresh(path)` avant de charger un flux en HTTPS, et traduit l'échec en `PlayerError::CertificateStore`. Il lui faut donc la **liste** des racines, pas une vérification de chaîne.

## Pourquoi pas `crypto/x509`
`x509.SystemCertPool()` sous Windows ne contient pas les certificats : il délègue la vérification à CryptoAPI (`CertGetCertificateChain`) et le pool renvoyé est vide. Il est impossible d'en tirer les racines. Le paquet utilise donc `golang.org/x/sys/windows` (déjà dans go.mod, sans cgo), comme le crate `schannel` côté Rust :

| Rust (schannel) | Go |
|---|---|
| `CertStore::open_current_user("ROOT")` | `windows.CertOpenStore(CERT_STORE_PROV_SYSTEM, …, CERT_SYSTEM_STORE_CURRENT_USER, "ROOT")` |
| `.certs()` | `windows.CertEnumCertificatesInStore` (libère le contexte précédent) |
| `valid_uses()` | `CertGetEnhancedKeyUsage(ctx, 0, …)` via `crypt32.dll` (non emballé par x/sys) |
| `is_time_valid()` | `CertVerifyTimeValidity(NULL, ctx.CertInfo) == 0` via `crypt32.dll` |
| magasin `Disallowed` | même ouverture, comparaison du DER |

Règles reprises à l'identique : la vue de l'utilisateur courant inclut les racines de la machine ; une racine est gardée si elle autorise l'authentification serveur (OID `1.3.6.1.5.5.7.3.1`, ou aucune liste d'usages = tous les usages ; une liste vide = aucun usage), si elle est dans sa période de validité et si elle n'est pas dans `Disallowed`. Une erreur d'API autre que `CRYPT_E_NOT_FOUND` avec zéro usage fait échouer tout le rafraîchissement, comme dans `schannel`.

`crypto/x509` sert seulement dans le test (analyse du DER produit).

## Vérification (2026-11-22, machine de développement)
- Magasin ROOT de l'utilisateur : 114 certificats ; le paquet en garde 87.
- Les 27 exclus ont été listés avec PowerShell (`Cert:\CurrentUser\Root`) : 20 sont expirés, 7 n'autorisent pas l'authentification serveur (1 par l'extension : un certificat de signature de code d'Electronic Arts ; 6 par la propriété d'usage : racines de signature de code Sectigo, Entrust, DigiCert, GlobalSign, Symantec Mobile, Microsoft Identity Verification). Aucun n'est dans `Disallowed`. Le calcul PowerShell fondé sur l'extension seule donnait 93 ; les 6 de différence sont les racines à propriété d'usage, que `CertGetEnhancedKeyUsage` voit et pas PowerShell.
- Les 87 DER passent `x509.ParseCertificate`.

## Écarts
- `CertGetEnhancedKeyUsage` renvoie `CRYPT_E_NOT_FOUND` par le dernier code d'erreur alors que l'appel réussit. Go renvoie ce code à chaque appel système ; la vérification sur les 114 certificats réels n'a produit aucune erreur parasite.
- Hors Windows, `windowsRoots` renvoie l'erreur « magasin de certificats Windows uniquement », comme le Rust.
