# Contrat Request

Contrat figé de l'API Request utilisée par l'onglet « Demander » (desktop et Android TV). Voir l'ADR 0125.

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

Les fixtures sont **provisoires** : écrites à la main d'après le schéma, elles seront remplacées par des réponses réelles nettoyées (noms, ids et jetons retirés) à la tâche 2b du plan.
