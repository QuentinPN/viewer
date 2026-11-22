# Contrat Request

Contrat figé de l'API Request utilisée par l'onglet « Demander » (desktop et Android TV).

- Source : dépôt Request, fichier `docs/openapi.json`
- Commit : `54cd791217a9f3f18b6312c7070089382dfb7938` (`main@54cd791`)
- Rafraîchir : `git -C <Request> show origin/main:docs/openapi.json > contracts/request/openapi.json`, puis mettre à jour le commit ci-dessus et relancer `npx vitest run src/lib/requests/contract.test.ts`.

## Fixtures

`fixtures/*.json` sont lues par le test de contrat (Vitest), les tests Rust (`include_str!`) et les tests Kotlin. Le test `src/lib/requests/contract.test.ts` les valide en mode strict (propriété inconnue ou champ requis absent = échec) et échoue si une fixture n'a pas de schéma.

| Fixture | Schéma OpenAPI |
|---|---|
| `version-ok`, `version-old` | `ApiVersion` |
| `emby-token` | `EmbyTokenSession` |
| `sessions` | réponse 200 de `GET /api/auth/sessions` (`{ sessions: AuthSession[] }`) |
| `discover` | `DiscoverResponse` |
| `paged-media` | `PagedMedia` |
| `search` | `MediaSearchResults` |
| `media-movie`, `media-tv` | `MediaDetails` |
| `quota` | `RequestQuota` |
| `request-view` | `RequestView` |
| `request-page` | `RequestPage` |
| `request-detail` | `RequestDetail` |
| `error` | `ApiError` |

Depuis la tâche 2b (2026-11-19, sonde `real_server_probe`), `version-ok`, `emby-token`, `sessions`, `discover`, `paged-media`, `search`, `media-movie`, `media-tv` et `quota` sont des réponses réelles nettoyées (nom et id du membre, appareil, ids Emby et jeton remplacés ; listes raccourcies). Ajouts faits à la main : les saisons de `media-tv` réduites à six cas (vraies formes, renumérotées, saison 4 future, saison 5 `pending`), deux `seeAll` dans `discover` (le serveur n'en renvoie aucun) et deux sessions fictives dans `sessions`. `request-view`, `request-page`, `request-detail`, `error` et `version-old` restent écrites à la main : le compte de test n'a aucune demande.
