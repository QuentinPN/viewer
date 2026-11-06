# ADR 0025 — Durcissement réseau du lecteur

Statut : Accepté (2026-11-06). Complète les ADR 0002, 0007 et 0022.

## Contexte
L'API Emby refuse déjà les redirections et les URL de flux hors du serveur (`Policy::none()`, `stream_url`). Le lecteur, lui, reçoit ensuite ce que le serveur lui sert : FFmpeg suit les redirections et ouvre ce que nomme une playlist HLS. Mesuré avec la vraie libmpv contre des serveurs locaux et un faux jeton :
- une redirection 302 vers un autre hôte y emmène `X-Emby-Token` ;
- une playlist HLS servie en HTTP et nommant `file:C:/…` ou `C:/…` fait **lire et jouer** ce fichier local (`FileLoaded`, puis fin de fichier) ;
- un segment HLS sur un autre hôte y reçoit le jeton ;
- une playlist `.m3u` nommant un chemin local ou `edl://` : mpv refuse (`No protocol handler found`), rien n'est lu. C'est déjà le comportement par défaut.

## Décision
**Trois options de plus, dans `configure()` :**
- `max_redirects=0` dans `stream-lavf-o` : le lecteur ne suit aucune redirection, comme l'API. Une source qui redirige échoue, et le repli sur transcodage (ADR 0023) prend le relais si elle était une lecture directe ;
- `demuxer-lavf-o=protocol_whitelist=[http,https,tcp,tls]` : les ouvertures internes de FFmpeg, dont les segments HLS, ne peuvent plus viser un fichier local. Les fichiers locaux ouverts par mpv lui-même ne passent pas par cette liste et restent lisibles ;
- `tls-verify=yes`, écrit explicitement : c'est la valeur par défaut de cette version, mais la validation TLS ne doit pas dépendre du binaire embarqué.

**Le risque qui reste est accepté.** Un segment HLS sur un autre hôte reçoit toujours le jeton : FFmpeg joint les en-têtes à toutes ses requêtes et n'a pas de liste d'hôtes. Il faut pour cela un serveur compromis, qui détient déjà le jeton, ou une injection sur un lien HTTP en clair, où le jeton circule déjà en clair. Ce cas ne donne donc pas de nouvel accès à un attaquant, sauf s'il veut le relayer vers un tiers.

## Alternatives
- **`access-references=no`** : mesuré, cette option bloque aussi le premier segment HLS (`Not opening … due to --access-references=no`). Le transcodage ne lirait plus. Rejeté.
- **Relais HTTP local pour filtrer les hôtes** : réglerait le segment sur un autre hôte, au prix d'un service de plus dans le chemin de lecture, contraire à l'objectif de lecture directe. Rejeté pour ce risque.
- **Couper mpv à la fin de type « redirection » de sa playlist** : mpv enchaîne seul sur l'entrée suivante, sans garantie de l'arrêter à temps, et la fuite vers l'autre hôte vient d'abord du démultiplexeur HLS. Rejeté.

## Conséquences
- Un élément dont Emby sert le flux par redirection (`.strm`, source distante) ne peut plus être lu en direct. Aucun élément de ce type n'a été testé.
- **HTTPS ne lit pas encore.** FFmpeg utilise ici OpenSSL sans magasin de certificats (`tls-ca-file` vide) : un flux HTTPS est refusé, y compris avec un certificat public valide (`https://www.example.com`, erreur TLS avant tout envoi d'en-tête). L'échec est du bon côté, le jeton ne part pas, mais un serveur HTTPS ne peut pas lire. Mesuré avec une autorité fournie à `tls-ca-file` : le certificat valable se connecte, le mauvais nom d'hôte est refusé. Le magasin Windows est fourni au lecteur par l'ADR 0026.

## Validation
Tests contre la vraie libmpv et un serveur local : la redirection ne porte pas le jeton ailleurs, et une playlist HLS n'ouvre pas de fichier local. Scénarios `gate2-play`, `gate2-transcode`, `gate3-player`, `gate4-fallback` sur le serveur réel. Résultats : `docs/evidence/gate4-securite-2026-11-06.md`.
