# Analyse initiale : contradictions, manques et décisions (2026-10-27)

Relecture de CLAUDE.md, PRODUCT.md, ARCHITECTURE.md, DESIGN.md, ROADMAP.md, TESTING.md, README.md, biome.json et des skills, confrontée à l'état réel du poste et des outils.

## Contradictions ou écarts constatés

| # | Écart | Résolution |
|---|---|---|
| 1 | README.md décrivait un « starter pack », pas une application. | README réécrit pour refléter l'état réel. |
| 2 | `biome.json` visait le schéma 2.2.0 ; `recommended: true` est déprécié en 2.6.3, et `biome migrate` le convertit en `preset: "none"` (toutes les règles désactivées). | Schéma 2.6.3, `preset: "recommended"` posé à la main et vérifié (ADR 0001). |
| 3 | « Svelte 5 + Vite (no SSR) » alors que le template Tauri officiel génère du SvelteKit. | Svelte + Vite sans SvelteKit (ADR 0001). |
| 4 | « Biome pour le frontend » : Biome ne voit pas les usages du template Svelte, et ses correctifs « unsafe » supprimeraient du code utilisé. | Règles « unused » désactivées pour `*.svelte` ; svelte-check fait foi (ADR 0001). |
| 5 | ROADMAP Gate 1 demande un « flux HTTP autorisé », mais tester Emby exige ton autorisation, qui relève de la Gate 2. | Gate 1 validée sur un flux HTTP **local** avec jeton en en-tête. Le flux Emby réel est reporté en Gate 2 / Gate 4. |
| 6 | TESTING.md : `npm run tauri build` comme commande de packaging, alors que la Gate 5 interdit tout paquet tant que la lecture n'est pas validée. | `bundle.active=false` dans `tauri.conf.json` jusqu'à la Gate 5. |
| 7 | Le skill media-playback dit « comparer embarqué vs fenêtre séparée » sans signaler que des contrôles automatiques peuvent tous passer avec une image invisible. | Skill enrichi : revue visuelle des captures obligatoire (ADR 0003). |

## Points d'architecture à trancher (propositions, non appliquées)

1. **Où vit `EmbyClient` : TypeScript ou Rust ?** ARCHITECTURE.md liste `EmbyClient` parmi les « services applicatifs » sans préciser la couche, tout en exigeant un stockage OS des jetons et l'interdiction pour l'UI de construire des URLs média.
   - **Proposition** : `EmbyClient` en Rust (reqwest + rustls, validation TLS). Le jeton ne quitte jamais Rust (Windows Credential Manager via la crate `keyring`), il n'y a pas de CORS à gérer, et l'en-tête d'auth est passé directement à mpv. L'UI reçoit des modèles typés.
   - **Tranché par l'ADR 0004** (Gate 2) : `EmbyClient` en Rust, mais TLS via native-tls (SChannel) plutôt que rustls, dont le backend aws-lc exige CMake et NASM absents du poste.
2. **Images (affiches)** : la CSP actuelle n'autorise que `'self'`. Deux options :
   - autoriser l'origine du serveur Emby ;
   - servir les images via le `CacheRepository` Rust (protocole personnalisé), ce qui donne le cache disque borné demandé et évite d'élargir la CSP.

   Recommandation : la seconde. À valider en Gate 3.
3. **TLS sur QNAP** : le PRODUCT impose de valider TLS, mais un NAS sert souvent un certificat auto-signé. Il faudra une politique explicite : certificat valide (Let's Encrypt via QNAP), ou épinglage d'empreinte confirmé par l'utilisateur. Jamais de désactivation silencieuse.

## Informations manquantes (nécessaires pour la Gate 2)
- Version d'Emby Server, modèle du QNAP, URL LAN et URL distante (HTTPS ? quel certificat ?).
- Un **compte Emby de test non administrateur** et quelques médias de test représentatifs, idéalement avec plusieurs pistes audio et sous-titres, du HEVC et des sous-titres PGS.
- Ton autorisation explicite pour interroger le serveur : lecture seule d'abord, puis rapport de progression sur le compte de test.
- **Licence du projet** : la DLL libmpv utilisée est GPL. Une distribution exigera GPL-3.0 ou une libmpv LGPL compilée par nos soins (ADR 0002).
- Droits Emby Premiere pour un client tiers : non vérifiés (PRODUCT.md le signale déjà).

## Particularités du poste de développement
- VS 18 Community sans bibliothèques C++ x64 → LNK1104 `msvcrt.lib`. Contournement : `scripts/with-msvc.ps1` (Build Tools 2019). Correctif durable : ajouter le composant MSVC x64 à VS 18.
- Windows SDK installé sur `D:\Windows Kits\10`.
- Git 2.24 (2019), ancien mais fonctionnel. Node 26 en local, Node 24 LTS en CI (`engines: >=22`).
