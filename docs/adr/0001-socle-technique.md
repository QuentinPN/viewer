# ADR 0001 — Socle technique et outillage qualité

Statut : Accepté (2026-10-27)

## Contexte
CLAUDE.md fixe Tauri 2 + Svelte 5 + TypeScript strict + Vite (sans SSR), Biome, svelte-check, Vitest et cargo fmt/clippy/test. La Gate 0 doit poser ce socle, de façon reproductible en local et en CI.

## Décision
- **Svelte 5 + Vite sans SvelteKit.** Le template officiel `create-tauri-app` (svelte-ts) génère un projet SvelteKit (adapter-static). Pour une application desktop sans SSR ni routage d'URL, SvelteKit ajoute une couche (routing, `svelte-kit sync`, adapter) sans bénéfice actuel. La navigation sera gérée par un état applicatif simple, à réévaluer en Gate 3 si le nombre d'écrans le justifie.
- **Versions épinglées exactement** dans `package.json` (Biome 2.6.3, Svelte 5.57.3, Vite 8.3.5, Vitest 5.0.5, svelte-check 4.7.7, Tauri CLI/API 2.12.4). `package-lock.json` est commité.
- **TypeScript 6.0, pas 7.0.** TS 7 (portage natif) est disponible, mais svelte-check s'appuie sur l'API du language service TypeScript. Le template officiel Tauri reste sur `~6.0`. À réévaluer quand svelte-check annoncera la compatibilité.
- **Tauri 2 stable.** Tauri 3 n'existe qu'en alpha.
- **Biome** : preset `recommended`. Les règles `noUnusedVariables` et `noUnusedImports` sont désactivées pour `*.svelte` : Biome n'analyse que le bloc `<script>` et ne voit pas les usages du template, et son correctif « unsafe » supprimerait du code utilisé. svelte-check (`--fail-on-warnings`) couvre les diagnostics Svelte. `biome migrate` a converti `recommended: true` en `preset: "none"`, ce qui désactivait toutes les règles. Corrigé à la main et vérifié avec un fichier sonde.
- **Markdown exclu de Biome** (`!**/*.md`) : Biome 2.6 formate le Markdown en alignant les tableaux, ce qui rend les diffs des specs illisibles pour un gain nul.
- **Rust** : édition 2024, `unsafe_op_in_unsafe_fn = deny`, `clippy::undocumented_unsafe_blocks = warn`, clippy en `-D warnings`.
- **Script unique** `npm run verify` (= `verify:web` + `verify:rust`).
- **CI GitHub Actions** (`.github/workflows/ci.yml`) avec deux jobs :
  - `web` sur ubuntu ;
  - `rust` sur windows-latest, avec libmpv épinglée et le test moteur réel en headless, rendu obligatoire par `VIEWER_REQUIRE_LIBMPV=1`.

  Les actions sont épinglées par SHA de commit et la permission par défaut est `contents: read`.

## Alternatives
- SvelteKit + adapter-static : rejeté pour l'instant (voir ci-dessus).
- ESLint + Prettier avec eslint-plugin-svelte : couverture Svelte plus complète, mais double outillage. Non retenu tant qu'aucun manque concret n'est constaté (skill quality-gates).

## Conséquences
- La CI ne valide **pas** le rendu vidéo à l'écran ni la composition WebView, qui exigent un bureau réel. Ces points relèvent du scénario Gate 1 manuel/semi-automatisé (`scripts/spike/run-gate1.ps1`).
- Particularité locale : sur ce poste, l'installation VS 18 n'a pas les bibliothèques C++ x64 (`msvcrt.lib`) et rustc la choisit par défaut (LNK1104). `scripts/with-msvc.ps1` importe l'environnement d'une installation complète (Build Tools 2019). Correctif durable : ajouter le composant « MSVC x64/x86 build tools » à VS 18.

## Validation
`npm run verify` exécuté le 2026-10-27 sur Windows 10 22H2 : Biome 0 diagnostic, svelte-check 0 erreur / 0 warning, Vitest 8/8, build Vite OK, cargo fmt OK, clippy `-D warnings` OK, cargo test 10/10 (dont le moteur libmpv réel). **La CI GitHub n'a pas encore tourné** : aucun dépôt distant n'est configuré.
