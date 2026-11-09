# ADR 0052 — Journal verbeux de mpv hors de logcat (Android TV)

Statut : Accepté (2026-11-09). Complète l'ADR 0038.

## Contexte
`libplayer.so`, le pont JNI de mpv-android, demande à libmpv son journal au niveau « v ». Il recopie lui-même chaque ligne dans logcat, en priorité VERBOSE, avant que l'observateur Kotlin ne masque les secrets. Lors d'un transcodage HLS, lavf liste au niveau « v » les options qu'il n'a pas utilisées, dont `headers` avec `X-Emby-Token`. Trouvé sur le Shield : 1 ligne de jeton dans logcat à la première lecture plafonnée (ADR 0051). Les vérifications précédentes étaient en lecture directe, où l'en-tête est consommé.

## Décision
Une petite bibliothèque C, `app/src/main/cpp/viewerlog.c`, est compilée par CMake via `externalNativeBuild`. Avant le premier `MPVLib.create`, elle appelle `__android_log_set_minimum_priority(ANDROID_LOG_DEBUG)`. La fonction est trouvée par `dlsym`, car elle n'existe qu'à partir d'Android 11 (API 30). Ce seuil vaut pour tout le processus : les lignes VERBOSE ne sont plus écrites, et la copie filtrée de Kotlin (`ViewerMpv`, INFO et plus) reste.

## Alternatives
- **`msg-level`** : sans effet, car le niveau demandé par le client libmpv l'emporte sur les tampons de journal.
- **Recompiler libplayer** avec un niveau « info » : il faut les buildscripts mpv-android, sous Linux (voir ADR 0038).
- **Jeton dans l'URL** (`api_key`) : contraire à la règle de sécurité du projet.
- **Transcodage progressif plutôt que HLS** : la recherche dans la vidéo est moins bonne, et cela ne protège pas des autres lignes verbeuses.

## Conséquences
- Sous Android 9 et 10 (minSdk 28), la fonction n'existe pas. L'application écrit un avertissement et la copie verbeuse reste. Le Shield est sous Android 11.
- La compilation demande CMake 3.22.1. Gradle l'installe dans le SDK au premier build.
- Les lignes VERBOSE du processus, y compris celles des bibliothèques, ne sont plus écrites. Le diagnostic passe par `ViewerMpv` et les étiquettes de l'application.
