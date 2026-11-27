# Fixtures Stremio

Réponses réelles du compte du propriétaire (API `api.strem.io` et addons installés), capturées le 23/11/2026 avec sa permission, puis caviardées avant toute écriture sur disque. Partagées par le bureau (Go) et Android TV.

| Fichier | Requête |
|---|---|
| `login_ok.json` | `POST /api/login`, succès (réduit à `authKey` + e-mail fictif) |
| `login_wrong_password.json` | `POST /api/login`, mauvais mot de passe : HTTP 200, `error.code` 3 |
| `addon_collection.json` | `POST /api/addonCollectionGet`, collection complète (10 addons) |
| `addon_collection_session_expired.json` | idem avec une clé inconnue : HTTP 200, `error.code` 1 |
| `streams_movie.json` | `GET {base}/stream/movie/tt0111161.json`, par addon de flux |
| `streams_series.json` | `GET {base}/stream/series/tt0944947:1:2.json`, par addon de flux |

`streams_*.json` : objet indexé par `addon-<n>` (`n` = rang dans la collection) avec `name`, `status` HTTP (0 = injoignable), `total` (nombre de flux reçus) et `response` limitée à 6 flux avec `url` et 2 sans.

## Caviardage

- `authKey` → `REDACTED_AUTHKEY` ; e-mail → `owner@example.com`.
- `transportUrl` → `https://addon-<n>.example/addon-<n>/manifest.json` (le chemin réel porte la configuration et la clé debrid). « Local Files » garde `http://127.0.0.1:11470/manifest.json`.
- `url` de flux → `https://debrid.example/REDACTED/file.<ext>` ; `externalUrl` et valeurs de `proxyHeaders` → `REDACTED`.
- Toute chaîne de 40 caractères ou plus qui n'est pas un `infoHash` → `REDACTED` ; suffixe d'`id` propre au compte → zéros.

Ne jamais ajouter de capture non caviardée à ce dossier.
