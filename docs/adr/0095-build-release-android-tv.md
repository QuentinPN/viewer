# ADR 0095 — Build release Android TV

Statut : Accepté (2026-11-10).

## Contexte
Le propriétaire n'utilisera plus que la Shield pour la TV. Jusqu'ici, seule la version debug était installée : elle est débogable et deux fois plus lourde, et elle contient l'outil de test de la Gate TV-1 (`PlaybackPocActivity`), exporté et lançable par n'importe quelle application de l'appareil. Le build release (R8 activé, signé avec la clé debug, usage personnel uniquement) n'avait jamais été construit ni lancé.

## Décision
- `PlaybackPocActivity` et sa déclaration passent dans le source set `debug` (`app/src/debug`). Le script `scripts/spike/run-gate-tv1.ps1` continue de marcher avec la version debug.
- La version release reste signée avec la clé debug, comme le prévoyait déjà `build.gradle.kts`. Elle s'installe donc par-dessus la version debug sans perdre la session, et inversement.
- Pour installer : `.\gradlew.bat assembleRelease`, puis `adb install -r app\build\outputs\apk\release\app-release.apk`.

## Alternatives
- **Une clé de release dédiée** : rien à protéger pour un usage personnel. En plus, passer de debug à release obligerait à désinstaller l'app, donc à se reconnecter. Rejeté.
- **Un `applicationIdSuffix` pour la version debug** : les deux versions cohabiteraient, mais la version debug demanderait une nouvelle connexion, et les tests sur l'appareil ne saisissent pas de mot de passe. Rejeté.

## Conséquences
- L'APK release pèse 33,6 Mo contre 59,8 Mo pour la version debug. Il n'est pas débogable et ne contient plus d'activité de test.
- Chaque `installDebug` des tests remplace la release sur la Shield : il faut la réinstaller à la fin d'une session de travail.

## Vérification
- `testDebugUnitTest` (476 tests, 0 échec), `assembleDebug`, `lintDebug`, `assembleRelease` et `lintRelease` passent.
- `aapt2 dump xmltree` : le manifeste release ne déclare que `MainActivity` et `PlayerActivity`. `dumpsys package` ne montre aucun drapeau `DEBUGGABLE`.
- Sur la Shield, la release installée par-dessus la version debug garde la session. L'accueil s'affiche, « Reprendre à 3:16 » lit Overgeared S1E7, et le menu ▼ liste les pistes audio. Retour envoie `report Stopped`. Logcat : 0 ligne avec un jeton, 0 `FATAL EXCEPTION`.
