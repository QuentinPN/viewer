# ADR 0026 — Le magasin de certificats Windows pour le lecteur

Statut : Accepté (2026-11-06). Complète l'ADR 0025.

## Contexte
FFmpeg utilise ici OpenSSL, sans magasin de certificats : `tls-ca-file` est vide, et un flux HTTPS est refusé même avec un certificat public valide (ADR 0025). L'API, elle, passe par le magasin Windows (`native-tls`). Sur un serveur HTTPS, la connexion réussirait donc et la lecture échouerait. La validation doit rester active : le lecteur n'envoie le jeton qu'à un serveur reconnu.

OpenSSL ne lit pas le magasin Windows. Il lit un fichier PEM.

## Décision
**Avant chaque chargement d'un flux HTTPS, `MpvEngine::load` exporte les racines Windows dans un fichier PEM et le donne à `tls-ca-file`.**

- **Quels certificats** : le magasin `ROOT` de l'utilisateur, qui inclut celui de la machine. Une racine admise par l'utilisateur ou l'entreprise est donc admise par le lecteur, comme par l'API. Sont écartés les certificats expirés, ceux dont les usages excluent l'authentification de serveur, et ceux présents dans le magasin `Disallowed`.
- **Quand** : à chaque chargement, pas au démarrage. Windows peut télécharger une racine à la demande pendant la validation de l'API, et une liste figée au démarrage la manquerait. Le coût est mesuré dans `docs/evidence/gate4-https-2026-11-06.md`. Rien n'est lu ni écrit pour un flux HTTP ou un fichier local.
- **Où** : `tls-roots.pem` dans le dossier de cache de l'application. Il ne contient que des certificats publics, donc aucun secret. Il n'est réécrit que si son contenu change, et par renommage, pour qu'une lecture ne voie jamais un fichier à moitié écrit. Une modification manuelle est effacée au chargement suivant.
- **Échec fermé** : si l'export échoue ou ne donne aucun certificat, `load` renvoie une erreur et ne charge rien. Sans chemin configuré, `tls-ca-file` reste vide, et OpenSSL refuse.

**Deux dépendances directes de plus, déjà compilées par `native-tls` et `reqwest`** : `schannel`, pour lire les magasins sans écrire de code `unsafe`, et `base64`, pour le PEM. Aucun nouveau code tiers n'entre dans l'application. `schannel` est limité à Windows.

## Alternatives
- **Export au démarrage seulement** : plus simple, mais manque une racine téléchargée ensuite, et le serveur valide pour l'API serait refusé par le lecteur. Rejeté.
- **Livrer un fichier de racines (Mozilla)** : ignore les choix de l'utilisateur, les racines d'entreprise et les retraits, et il faudrait le tenir à jour. Rejeté.
- **Relais HTTP local validant par Windows** : un service de plus dans le chemin de lecture, déjà écarté par l'ADR 0025. Rejeté.
- **`tls-verify=no`, ou une exception pour le réseau local** : contraire à la règle de validation TLS. Rejeté.
- **API `windows-sys` directe** : du code `unsafe` à la place d'une API sûre déjà présente. Rejeté.

## Conséquences
- Un serveur HTTPS dont le certificat est reconnu par Windows peut lire. Un certificat auto-signé doit être approuvé dans Windows, pour l'API comme pour le lecteur.
- **Le lecteur reste un peu moins strict que Windows** : la révocation (CRL, OCSP) n'est pas contrôlée, et les restrictions fines posées sur une racine par Windows, comme une date limite par usage, ne sont pas reprises. L'API, elle, passe par la validation Windows avant tout flux.
- Le contrôle du nom d'hôte et de la chaîne est fait par OpenSSL, avec `tls-verify=yes`.
- Un programme tournant sous le même compte peut modifier le fichier entre l'écriture et la lecture. Il peut aussi lire le jeton dans le Gestionnaire d'identification : ce fichier ne lui donne rien de plus.

## Validation
Tests : conversion PEM, filtrage par usage, export du vrai magasin, écriture du fichier, échec fermé et option posée dans la vraie libmpv. Sondes contre la vraie libmpv : voir `docs/evidence/gate4-https-2026-11-06.md`.
