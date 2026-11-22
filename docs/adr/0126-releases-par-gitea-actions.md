# ADR 0126 — Releases automatiques par Gitea Actions

Statut : Accepté (2026-11-20), à la demande du propriétaire. Remplace la publication manuelle des ADR 0032, 0106, 0107 et 0111 ; l'hébergement et les manifestes de l'ADR 0112 ne changent pas.

## Contexte
Publier demandait de lancer à la main `npm run release`, `npm run release:publish` et `npm run android:publish` depuis `main` sur le PC du propriétaire. Le Gitea local (ADR 0111) a maintenant deux runners Gitea Actions.

## Décision
- **Déclencheur** : un merge dans `main` (merge commit, Conventional Commits) lance `.gitea/workflows/release.yml`. Une pull request vers `main` n'affiche que les prochaines versions (mode `check`). `workflow_dispatch` avec `dry-run` construit les deux artefacts sans rien publier.
- **Deux pistes**, chacune par l'action partagée `QuentinPN/claude-boilerplate/release@v2` (miroir public sur le Gitea), sur le runner `homeserver` (mini-PC, docker, label `ubuntu-latest`) :
  - desktop : tags `vX.Y.Z`, `package.json`, `package-lock.json`, `src-tauri/Cargo.toml` (+ `Cargo.lock`), `CHANGELOG.md`, tous les fichiers sauf `android-tv/**` ;
  - Android TV : tags `android-vX.Y.Z`, `versionName` et `versionCode + 1` de `android-tv/app/build.gradle.kts`, `android-tv/CHANGELOG.md`, seulement `android-tv/**`.
  L'action calcule la version depuis les commits, écrit le journal, commite `chore(release): …`, tague, crée la release sur le dépôt de code et refusionne dans `dev`.
- **Build et publication** sur le runner `viewer-pc` (ce PC Windows, mode hôte, label `windows`, `pwsh`) : `node scripts/publish.ts` et `node scripts/publish-android.ts`, sur le tag créé. Mêmes étapes qu'avant (libmpv, vérifications, build signé, release sur `Viewer-releases`, contrôle SHA-256 en ligne, `latest.json`), sans les contrôles de branche, de tag ni de push, faits par le workflow. `--dry-run` s'arrête après le build. `scripts/release.ts` et les scripts npm de publication sont supprimés.
- **Secret** : `RELEASE_TOKEN` (le jeton Gitea du `debug.env`) en secret Actions du dépôt ; Gitea refuse les noms commençant par `GITEA_`. Le workflow le passe en `GITEA_TOKEN`, que `scripts/gitea.ts` lit en premier. Il ne sert qu'à écrire sur `Viewer-releases` : le job de release pousse avec le jeton du job (aucune protection de branche ; ses pushs ne relancent pas de workflow) et les deux pistes tournent l'une après l'autre dans ce même job, pour que la piste Android voie le commit de la piste desktop.
- **Republier** : la publication est idempotente (release existante réutilisée, fichier identique gardé, sinon remplacé, puis `latest.json`). `workflow_dispatch` avec `publish` = `desktop`, `android` ou `both` (et `dry-run` décoché), sur `main` seulement, reconstruit et publie la version courante depuis son tag, sans commit de release.
- **Durée du build** : le runner hôte supprime son dossier de travail après chaque job, donc le job `desktop` fixe `CARGO_TARGET_DIR` à `~/.gitea-runner/cache/viewer-target` (lu aussi par `publish.ts`) pour garder les dépendances compilées d'une release à l'autre. Le profil release passe en LTO `thin` avec les codegen units par défaut (build bien plus court, binaire un peu plus gros). Le dossier grossit avec les montées de dépendances : le supprimer suffit à repartir de zéro.
- La clé updater (`~/.viewer/updater.key`) et la clé debug Android (`~/.android/debug.keystore`) restent sur le PC : seul `viewer-pc` peut publier.

## Alternatives
- **Garder la publication manuelle** : étapes oubliées, versions écrites à la main. Écarté.
- **Tout sur le mini-PC** : pas de MSVC ni de clés de signature. Écarté.

## Conséquences
- Versions et journaux ne s'éditent plus à la main, sauf un saut de version voulu ; le journal reprend les messages de commit, qui doivent donc être lisibles.
- Le PC doit être allumé, runner lancé, pour qu'une release soit construite ; sinon le job attend.
- Avant une release risquée : `workflow_dispatch` avec `dry-run` sur `dev`.
