# ADR 0111 — Dépôt et releases sur le Gitea du propriétaire

Statut : Accepté (2026-11-13). Remplace la partie « release GitHub » des ADR 0106 et 0107.

## Contexte
Le propriétaire veut un auto-updater sur le desktop et la Shield. Le dépôt GitHub est privé : les apps ne pourraient télécharger les releases qu'avec un jeton GitHub stocké sur chaque appareil. Il a choisi d'héberger ses dépôts sur un Gitea à lui (Docker, sur le mini-PC `192.0.2.14`, port 3300, Gitea 1.24.7 à la création, 28.3.0 depuis novembre 2026 : endpoints utilisés revérifiés, inchangés), joignable seulement sur son réseau local.

## Décision
- **Migration** : dépôt privé `QuentinPN/Viewer` créé sur Gitea, `git push --mirror` depuis GitHub (branches et tags identiques ; les refs `refs/pull/*` de GitHub sont refusées par Gitea, sans conséquence), puis les 6 releases recopiées depuis le PC avec leurs notes et fichiers. Le jeton GitHub n'a pas été confié à Gitea.
- **Remotes** : `origin` = Gitea, `github` = GitHub gardé en secours (non poussé automatiquement).
- **Jeton Gitea** (droits `repository` et `user`) : dans le `debug.env` non versionné de la copie principale, clé `GITEA_TOKEN`, ou dans la variable d'environnement du même nom. Git le lit par un `credential.helper` limité à `http://git.example.com` dans la config locale du dépôt ; il n'est écrit ni dans `.git/config` ni dans les journaux. Il ne sert qu'à publier, jamais aux apps.
- **Publication** : `npm run release:publish` et `npm run android:publish` gardent leurs étapes ; seules la vérification « release déjà existante » et la création de release passent par l'API Gitea (`scripts/gitea.ts`). Le contrôle final retélécharge le fichier publié et compare son SHA-256 au build local (Gitea ne fournit pas d'empreinte).
- Gitea n'a pas de notion de release « Latest » par app : l'auto-updater lira un manifeste par app, décidé dans son propre ADR.

## Alternatives
- **Dépôt public de releases sur GitHub** : binaires visibles sur Internet. Écarté par le choix du propriétaire.
- **Jeton GitHub en lecture sur chaque appareil** : un secret de plus par appareil, à renouveler. Écarté.

## Conséquences
- Plus de sauvegarde hors site automatique : le volume Docker `gitea-data` est à sauvegarder par le propriétaire (NAS par exemple). Le remote `github` peut servir de copie manuelle.
- Les outils GitHub (`gh`, intégration GitHub) ne servent plus pour ce dépôt.
- Hors du réseau local, ni push ni mise à jour des apps.
- Liaison en HTTP sur le réseau local : l'intégrité des mises à jour reposera sur la signature (Tauri updater, signature APK Android), pas sur le transport.
