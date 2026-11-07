# ADR 0028 — Installateur Windows

Statut : Accepté (2026-11-07). S'appuie sur l'ADR 0002 (licence) et l'ADR 0003 (chargement de libmpv).

## Contexte
La Gate 5 demande une build de production et un installateur Windows. L'application n'est installée que sur les PC du propriétaire, sans distribution (ADR 0002). Tauri sait produire un installateur NSIS ou MSI. Le lecteur charge `libmpv-2.dll` au démarrage, depuis le dossier de l'exécutable uniquement dans une build de production : jamais par un nom nu, que Windows chercherait dans le dossier courant et le `PATH`.

## Décision
- **Installateur NSIS, par utilisateur** (`installMode: currentUser`) : installation dans `%LOCALAPPDATA%`, sans droits d'administrateur, désinstallable depuis les paramètres de Windows. Interface de l'installateur en français.
- **`libmpv-2.dll` copiée à côté de l'exécutable**, par les `resources` du bundle : c'est le dossier où la build de production la cherche, sans changement de code.
- **WebView2 :** le programme d'amorçage de Microsoft, par défaut dans Tauri. Windows 10 et 11 l'ont en général déjà.
- **Pas de signature de code** : sans distribution, SmartScreen avertira au premier lancement de l'installateur, ce qui est accepté.
- **Pas de mise à jour automatique** : une nouvelle version s'installe par-dessus l'ancienne.

## Alternatives
- **MSI (WiX)** : installation pour toute la machine, avec droits d'administrateur. Rien ne le justifie pour un seul utilisateur. Rejeté.
- **Dossier portable sans installateur** : plus simple, mais ni raccourci ni désinstallation. Gardé comme solution de repli.
- **Signature de code** : coûte un certificat et ne sert qu'à d'autres utilisateurs. Reportée à une éventuelle distribution, qui rouvrirait aussi l'ADR 0002.

## Conséquences
- La DLL GPL-3.0 est livrée avec l'application, ce qui n'est acceptable que sans distribution (ADR 0002).
- Les comportements propres à la production (pas de prototype de lecteur, pas de variables `VIEWER_*`, pas de console) ne sont vérifiables qu'en lançant la build installée.

## Vérification
Build de production, installation, lancement, connexion et lecture sur le PC du propriétaire, puis désinstallation. Le test sur une machine propre reste à faire séparément.
