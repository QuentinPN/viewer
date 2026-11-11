# ADR 0107 — Releases GitHub de l'app Android TV

Statut : Accepté (2026-11-11), à la demande du propriétaire.

## Contexte
L'app Android TV (`android-tv/`) a son propre numéro de version (`versionName` et `versionCode` dans `app/build.gradle.kts`) et son propre journal (`android-tv/CHANGELOG.md`, ADR 0032). Les tags `vX.Y.Z` et les releases GitHub (ADR 0106) sont ceux de l'app desktop. Les APK n'étaient gardés nulle part : chaque version installée sur la Shield n'existait que sur le disque. Le propriétaire a demandé si l'app Android devait avoir son propre dépôt.

## Décision
- **Un seul dépôt.** Les deux apps partagent les contrats Emby, les ADR, le ROADMAP et les choix d'API ; un second dépôt obligerait à les synchroniser à la main.
- **Tags `android-vX.Y.Z`**, distincts des `vX.Y.Z` du desktop. `scripts/release.ts` ne cherche que les tags `v*`, donc le journal desktop ignore les tags Android.
- **`npm run android:publish`** (`scripts/publish-android.ts`), lancé sur la machine du propriétaire après avoir monté la version et écrit la section du journal (règle de chaque version livrée). Il refuse si l'arbre est modifié, si HEAD n'est pas sur `main`, si `android-tv/CHANGELOG.md` n'a pas de section pour la version, si la release GitHub existe déjà, ou si le tag existe sur un autre commit.
- Puis : `fetch-libmpv-android.ps1` (mpv-android épinglé et vérifié), `gradlew testDebugUnitTest assembleRelease lintRelease`, tag annoté `android-vX.Y.Z` sur HEAD, `git push origin main android-vX.Y.Z`, `gh release create --latest=false` avec l'APK renommé `Viewer-TV_X.Y.Z.apk`.
- **« Latest » reste au desktop** : GitHub n'en montre qu'une par dépôt, et la release Android est toujours créée avec `--latest=false`.
- **Notes** : la section de la version dans le journal Android, puis l'APK avec son code de version, sa taille et son SHA-256 (`androidReleaseNotes`, testé par Vitest, comme `gradleVersion`).
- **Contrôle final** : le SHA-256 calculé par GitHub pour l'APK envoyé doit être celui du build local.
- **`--dry-run`** : refus en avertissements, arrêt après le build, notes affichées ; rien n'est tagué, poussé ni publié.

## Alternatives
- **Dépôt séparé pour Android** : releases et « Latest » propres, mais contrats et documents en double. Rejeté.
- **Un tag commun aux deux apps** : les versions avancent à des rythmes différents (desktop 1.1.0, Android 1.0.1). Rejeté.

## Conséquences
- Publier une version Android : monter `versionName`/`versionCode`, ajouter la section au journal, commiter sur `main`, puis `npm run android:publish`.
- L'APK publié est signé avec la clé de debug de la machine (usage personnel, ADR 0095) : il s'installe par-dessus la version de la Shield tant que cette clé ne change pas.
- Les versions 0.1.0 à 1.0.0 restent sans release.
