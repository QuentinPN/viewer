# ADR 0125 — Intégration Request (onglet « Demander »)

- Statut : accepté
- Date : 2026-11-19

## Contexte

Request est le service de demandes de films et de séries du foyer. Les membres doivent pouvoir parcourir, demander et suivre leurs demandes depuis Viewer (desktop et Android TV), avec leur compte Emby et sans nouveau mot de passe. Spécification : `docs/superpowers/specs/2026-11-19-request-integration-design.md`. Plan : `docs/superpowers/plans/2026-11-19-request-integration.md`.

## Décision

- **Client natif par plateforme** : `reqwest` derrière des commandes Tauri (desktop), OkHttp (TV). Aucun appel depuis la webview, donc pas de CORS à ouvrir ni de jeton exposé à l'UI. Aucune nouvelle dépendance.
- **Contrat figé** : `contracts/request/openapi.json` (Request `main@54cd791`). Des fixtures JSON partagées (`contracts/request/fixtures/`) sont validées contre ce contrat (test Vitest strict) puis désérialisées par les tests Rust et Kotlin. Elles sont provisoires jusqu'à la tâche 2b (réponses réelles nettoyées).
- **Authentification** : `POST /api/auth/emby-token` (sans `Authorization`) échange l'identité Emby de l'utilisateur contre un jeton de session ; tout le reste passe en `Authorization: Bearer`. Compatibilité exigée : API major `1` et features `auth.emby-token`, `auth.bearer`, `auth.sessions`, `media.item-id`.
- **Stockage du jeton** : stockage sécurisé de l'OS (`CredentialStore` desktop, Keystore TV), par compte Emby : `request/{embyServerId}/{embyUserId}`. Jamais dans une URL, un journal, SQLite ou le disque en clair. `Debug`/`toString` masquent le jeton.
- **Échange single-flight** : le serveur limite à 10 échecs par IP et par 5 minutes, partagés avec la connexion par mot de passe. Au démarrage, Découvrir, la bande et ~20 images partent en parallèle : un seul verrou d'échange ; après l'avoir obtenu, on relit le jeton stocké et on ne ré-échange que s'il est celui qui a échoué.
- **401** : suppression du jeton, un seul ré-échange, rejeu ; un second 401 donne l'état « rejected » (« connexion refusée »), jamais de boucle. **429** : message avec le délai `Retry-After`, pas de nouvel essai automatique.
- **Déconnexion persistante** : drapeau `signedOut` par compte Emby ; l'onglet propose « Se connecter » et ne se reconnecte pas seul. Déconnexion Emby ou « Oublier ce serveur » supprime aussi le jeton Request (révocation serveur au mieux).
- **Adresse** : espaces et `/` final retirés, `https://` par défaut. `http://` accepté seulement pour loopback et réseaux privés (127/8, 10/8, 172.16/12, 192.168/16, `*.local`), avec avertissement « non chiffré » ; sinon `InvalidAddress`. Le jeton Emby ne part jamais en clair sur Internet. TLS validé.
- **Images** : protocole `requestimg` (`http://requestimg.localhost/{size}/{file}`) servi par Rust avec le Bearer et le cache disque ; `size` dans {w185, w342, w500, w780, w1280}, `file` limité à `[A-Za-z0-9_-]{1,64}` + `jpg|jpeg|png|webp` (jamais SVG), sinon 400. Le jeton stocké est lié à l'adresse qui l'a émis : après un changement d'adresse, il n'est jamais envoyé au nouvel hôte. CSP : `http://requestimg.localhost` ajouté à `img-src` uniquement. TV : Coil sur le client authentifié, Bearer seulement pour l'hôte configuré.
- **Erreurs** : le champ `error` du serveur est affiché tel quel ; textes UI en français.

## Hors périmètre (phase 2)

Gestion des demandes d'autrui, demandes 4K ou avancées, signalements, liste de surveillance, notifications, mode hors ligne des demandes, lecture du statut sans Emby.

## Conséquences

- Une dérive du serveur se voit au rafraîchissement du contrat : le test de contrat échoue avant les clients.
- Chaque compte Emby a sa session Request et apparaît dans « Appareils connectés » (`{COMPUTERNAME} (Windows)` / `{Build.MODEL} (Android TV)`).
- Un essai réel crée la fiche membre du compte de test et une entrée Emby › Appareils ; aucune demande réelle sans accord.
