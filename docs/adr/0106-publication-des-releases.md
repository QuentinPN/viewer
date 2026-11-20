# ADR 0106 — Publication automatique des releases GitHub

> Release GitHub remplacée par une release Gitea : voir ADR 0111.

Statut : Accepté (2026-11-08), à la demande du propriétaire. Publication remplacée par l'ADR 0126 (Gitea Actions) : `npm run release:publish` n'existe plus, `scripts/publish.ts` tourne en CI.

## Contexte
`npm run release` (ADR 0032) monte le numéro, écrit le journal, commite et tague, sans rien pousser. L'installateur était ensuite construit à la main, et seuls les tags arrivaient sur GitHub : aucune release ne gardait les installateurs des versions 0.1.0 à 1.0.0. La 1.0.1 a été la première release GitHub, créée à la main. Le propriétaire veut que la release soit créée automatiquement. Les GitHub Actions sont désactivées à sa demande.

## Décision
- **`npm run release:publish`** (`scripts/publish.ts`), lancé sur la machine du propriétaire après `npm run release`. Le dépôt reste privé et l'installateur ne sert qu'au propriétaire (ADR 0002).
- Le script refuse si l'arbre de travail est modifié, si HEAD n'est pas sur `main`, si HEAD ne porte pas le tag `vX.Y.Z` de `package.json`, si la release GitHub existe déjà ou si `CHANGELOG.md` n'a pas de section pour la version.
- Puis, dans l'ordre, chaque étape arrêtant tout en cas d'échec : `fetch-libmpv.ps1` (libmpv épinglé et vérifié), `verify:web`, `verify:rust` et `tauri build` via `with-msvc.ps1`, `git push origin main vX.Y.Z`, `gh release create` avec l'installateur.
- **Notes** : la section de la version dans `CHANGELOG.md`, puis une ligne sur l'installateur avec son nom, sa taille et son SHA-256 (`releaseNotes`, testé par Vitest).
- **Contrôle final** : le SHA-256 calculé par GitHub pour le fichier envoyé doit être celui du build local.
- **`--dry-run`** : les refus deviennent des avertissements, le script s'arrête après le build et affiche les notes, sans pousser ni publier.

## Alternatives
- **Workflow GitHub Actions sur le tag** : les Actions sont désactivées, et le runner devrait refaire l'environnement MSVC et libmpv. Rejeté.
- **Tout dans `npm run release`** : un échec des tests ou du build laisserait un tag déjà poussé sur une version cassée. Les deux commandes restent séparées : le tag local se corrige tant que rien n'est poussé.

## Conséquences
- Publier une version : `npm run release -- <patch|minor|major>`, puis `npm run release:publish`.
- Nécessite `gh` connecté au compte propriétaire du dépôt.
- Les versions 0.1.0 à 1.0.0 restent sans release : leurs installateurs ne sont plus sur le disque.
