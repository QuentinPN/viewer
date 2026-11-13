# ADR 0112 — Mises à jour automatiques (desktop et Android TV)

Statut : Accepté (2026-11-13). Complète l'ADR 0111.

## Contexte
Le propriétaire veut des mises à jour « à la Discord » : téléchargement en arrière-plan, puis l'app indique qu'une mise à jour est prête et demande s'il veut l'appliquer ; s'il accepte, l'app redémarre à jour. Objectifs : plus d'assistant NSIS en plusieurs étapes sur le PC, plus d'`adb install` pour la Shield. Design validé : pastille vitrée « Mise à jour prête » puis carte vitrée 440 px (desktop), carte focusable « Installer / Plus tard » (TV).

## Décision
### Hébergement
- Dépôt **public** `QuentinPN/Viewer-releases` sur le Gitea local (ADR 0111) : lisible sans compte (vérifié : 200 anonyme, alors que le dépôt de code renvoie 404). Il porte les releases (notes + fichiers) à partir de desktop 1.2.0 et Android TV 1.1.0, et deux manifestes réécrits à chaque publication :
  - `desktop/latest.json` au format du plugin updater de Tauri (`version`, `notes`, `pub_date`, `platforms.windows-x86_64.{url,signature}`) ;
  - `android/latest.json` : `versionCode`, `versionName`, `notes`, `url`, `sha256`, `size`.
- URL de base : `http://git.example.com/QuentinPN/Viewer-releases/raw/branch/main/`. Le dépôt de code garde le code et les tags ; les 6 releases recopiées y restent comme historique.

### Desktop
- `tauri-plugin-updater` 2.x, appelé **côté Rust** seulement (pas de permission JS) : vérification 15 s après le lancement puis toutes les 6 h, en build release ; téléchargement en mémoire ; événement `app://update-ready` (version, notes) ; commandes `app_update_pending` et `app_update_install`.
- Installateur NSIS en mode `quiet` (installation par utilisateur, sans fenêtre), l'app se ferme et l'installateur la relance.
- Intégrité : chaque installateur est signé (minisign) par une clé sans mot de passe, `C:\Users\user\.viewer\updater.key`, hors dépôt ; l'app n'accepte que la clé publique embarquée. `dangerousInsecureTransportProtocol` est activé car le Gitea est en HTTP local : la signature, pas le transport, garantit l'intégrité.
- La pastille n'apparaît jamais pendant la lecture.

### Android TV
- Vérification au lancement puis toutes les 6 h tant que l'app est au premier plan ; APK téléchargé dans le cache, SHA-256 vérifié, certificat de signature comparé à celui de l'app installée avant de proposer l'installation.
- Sous l'API 33 (la Shield est en Android 11), `getPackageArchiveInfo` laisse `signingInfo` vide pour un fichier APK : le certificat de l'archive est lu avec `GET_SIGNATURES`.
- Installation par `PackageInstaller` (permission `REQUEST_INSTALL_PACKAGES`). Sur la Shield (Android 11), Android impose une autorisation « sources inconnues » la première fois et un écran de confirmation système à chaque mise à jour ; une installation sans confirmation demande Android 12+.
- L'APK release est signé avec la clé debug de la machine du propriétaire (`~/.android/debug.keystore`) : la perdre empêcherait toute mise à jour (désinstallation requise). À sauvegarder.

## Alternatives
- Plugin updater appelé depuis le JS : demande des permissions IPC et met la logique dans l'UI ; écarté (ARCHITECTURE.md).
- Mode NSIS `passive` (petite barre de progression) : possible, mais le propriétaire veut un passage silencieux.
- Manifeste en asset de release « latest » : Gitea n'a qu'une release « latest » par dépôt pour deux apps ; un fichier par app est plus simple.

## Conséquences
- La première version avec l'updater (1.2.0 / 1.1.0) s'installe encore à la main ; les suivantes arrivent seules.
- Hors du réseau local, pas de mise à jour (vérification en échec silencieux, journalisée par catégorie).
- Deux secrets locaux à sauvegarder : la clé de signature Tauri et le keystore Android.
- Le numéro de version du manifeste n'est pas signé (`requireSignedVersion` du plugin désactivé) : qui peut servir un faux manifeste sur le réseau local peut faire installer une ancienne version authentique. Risque accepté pour un usage personnel sur le LAN ; à activer quand toutes les versions publiées porteront la version signée.
