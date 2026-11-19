# Intégration Request — design

Date : 2026-11-19 · Statut : approuvé par le propriétaire (plan, puis maquettes « tes choix »).

## Intention

Les membres de la famille demandent films et séries depuis Viewer (PC et Shield) sans ouvrir le portail web Request. Emby reste la source de ce qui est disponible ; Request gère les demandes.

## Objectifs

- Onglet « Demander » (desktop et TV) : Découvrir, Parcourir, Recherche, Fiche avec demande (film ou saisons, quota), Mes demandes (détail, frise, annulation), Réglages › Demandes (adresse, état, Appareils connectés, déconnexion).
- Recherche Emby : section « À demander » sous les résultats de la bibliothèque.
- « Lire » quand `embyItemId` est présent : ouvre l'élément Emby dans Viewer.
- Connexion transparente avec le jeton Emby existant, sans mot de passe.

## Non-objectifs (phase 2)

Ma liste (`/api/watchlist`), cloche (`/api/notifications`), signalements, actions de gestionnaire, 4K, demandes pour un autre membre, `PATCH /api/requests/{id}`.

## Anti-objectifs

- Aucun appel depuis la webview ni `<img src>` vers Request (pas de CORS, images authentifiées).
- Jeton jamais en query string, jamais journalisé, jamais en clair sur disque.
- Aucun champ deviné : tout modèle vient de `contracts/request/openapi.json`.
- L'intégration ne doit jamais casser la partie Emby : Request absent, trop ancien ou en panne → onglet masqué ou message, le reste de l'app intact.

## Contrat (source de vérité)

`contracts/request/openapi.json` = copie de `docs/openapi.json` d'Request `main` @ `54cd791` (OpenAPI 3.1, API 1.0.0) et `docs/API.md` § « Clients natifs ». Serveur réel vérifié, le serveur Request réel (adresse dans debug.env) : `/api/version` → `{"apiVersion":"1.0.0","features":["api.version","media.item-id","auth.emby-token","auth.bearer","auth.sessions"]}`, TLS valide.

Règles du contrat retenues :

1. Compatibilité : `GET /api/version` (public). Exige major 1 et `auth.emby-token`, `auth.bearer`, `auth.sessions`, `media.item-id`. Sinon « Serveur Request trop ancien ».
2. `POST /api/auth/emby-token` `{userId, accessToken, deviceName}` **sans** en-tête `Authorization` → `{token, expiresAt, viewer}`. 401 refusé, 400 serveur non Emby/Jellyfin, 429 `Retry-After`, 502 Emby injoignable.
3. Toutes les autres requêtes : `Authorization: Bearer <token>`, ni cookie ni `X-Api-Key`. 401 → supprimer le jeton, refaire l'étape 2 **une seule fois** avec le jeton Emby courant, rejouer la requête ; second échec → message.
4. Erreurs : corps `{ "error": "…" }` en français, affiché tel quel ; 403 droit manquant, 404 introuvable.
5. `viewer.capabilities` masque ce que le membre ne peut pas faire (`canRequestMovies`, `canRequestTv`, `quotaExempt`…).

## Approches considérées

- **A — retenue : client natif par plateforme, contrat figé partagé.** Rust (reqwest) côté desktop derrière des commandes Tauri ; Kotlin (OkHttp) côté TV. Modèles écrits à la main pour les ~15 schémas utilisés ; des fixtures JSON partagées, validées contre l'OpenAPI par un test Vitest, sont désérialisées par les tests Rust et Kotlin. Suit le modèle déjà en place pour Emby et les vecteurs de lecture liée.
- B — générateur OpenAPI (progenitor/typify, openapi-generator Kotlin) : lourdes dépendances pour 15 types, code généré difficile à relire. Écartée.
- C — appels depuis la webview : impossible (pas de CORS, images authentifiées). Écartée.

## Décisions d'UX (maquettes choisies)

Canevas : https://claude.ai/artifact/66yn4wUwJsr2ujBKLTQboz

- Découvrir **B + bande C** : titre « Demander », segments Découvrir / Films / Séries / Mes demandes · N ; au-dessus des rangées, la bande « Mes demandes en cours » (cartes avec statut et barre de progression) quand il y en a ; rangées de `GET /api/discover` avec « Tout voir » (`seeAll`).
- Parcourir **A** : barre de filtres de la bibliothèque (Trier par, Genre, Années, Note, Réinitialiser) sur `GET /api/discover/{type}` ; TV : pastilles qui ouvrent une liste.
- Recherche **C** : la recherche Emby existante ajoute « À demander » (résultats `GET /api/search` absents d'Emby, c.-à-d. sans `embyItemId`) ; dans l'onglet Demander, la liste B (ligne avec statut et action Lire / Demander / Voir).
- Fiche **A** : fiche façon fiche Emby (fond, titre, méta, statut par saison) ; « Demander » ouvre une fenêtre centrée (saisons cochables, seules les demandables actives ; quota avant confirmation ; bouton « Demander N saison(s) ») ; « Lire » si `embyItemId`.
- Mes demandes **A** : liste filtrée par statut (totaux de `RequestPage.totals`), détail et frise (`RequestDetail.events`) dans un panneau à droite ; « Annuler la demande » si `status = pending` ; TV : détail plein écran.
- Réglages **A + C** : section « Demandes » dans Réglages (adresse, état, Appareils connectés, Se déconnecter) ; au premier passage l'onglet Demander propose la configuration sur place ; états « trop ancien », « injoignable », « connexion refusée ».

Libellés de statut : `available` Disponible, `partial` Partiel, `downloading` Téléchargement (avec `progress`), `requested` Demandé, `pending` En attente, `declined` Refusé ; requêtes : `pending` En attente, `approved` Approuvée, `sent` Envoyée, `available` Disponible, `declined` Refusée, `cancelled` Annulée, `failed` Échec.

## Contraintes techniques

- Desktop : module `src-tauri/src/request/`, reqwest existant (aucune nouvelle dépendance), jeton dans le Gestionnaire d'identification (même `CredentialStore` qu'Emby, compte distinct), images par un protocole `requestimg://` (Bearer ajouté en Rust, cache disque existant), adresse dans un fichier de réglages local (pas un secret). Faux serveur de test : `TcpListener` comme `emby/tests.rs`.
- TV : paquet `io.github.quentinpn.viewer.tv.request`, OkHttp + kotlinx.serialization existants, intercepteur Bearer limité à l'hôte Request, jeton chiffré Keystore comme `SessionStore`, Coil avec le client authentifié. Tests MockWebServer (déjà en dépendance).
- Nom d'appareil : `<nom de la machine> (Windows)` ; TV : `<nom de l'appareil Android> (Android TV)`.
- Versions : desktop 1.5.0, TV 1.4.0, avec CHANGELOG.

## Laissé au constructeur

Découpage interne des composants Svelte/Compose, noms de fichiers d'écran, détail des états de chargement (squelettes existants à réutiliser).
