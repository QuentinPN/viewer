# ADR 0007 — Négociation de la lecture avec PlaybackInfo

Statut : Accepté (2026-10-28). Le report du transcodage est levé par l'ADR 0009.

## Contexte
La fiche d'un film doit lancer la lecture dans le lecteur libmpv (Gate 1) à partir du serveur Emby. CLAUDE.md impose de négocier la lecture avec PlaybackInfo et les MediaSources, jamais en devinant d'après l'extension, et de ne jamais mettre le jeton dans une URL quand une autre voie existe.

Constats sur le serveur réel (Emby 4.10.1.0, compte de test, lecture seule ; contrat lu dans `/emby/openapi.json`) :
- `POST /Items/{Id}/PlaybackInfo?UserId=…` avec un `DeviceProfile` dans le corps renvoie `MediaSources[]` (`SupportsDirectPlay`, `SupportsDirectStream`, `SupportsTranscoding`, `DirectStreamUrl`, `TranscodingUrl`, `TranscodingSubProtocol`, `RequiredHttpHeaders`…), `PlaySessionId` et éventuellement `ErrorCode` (`NotAllowed`, `NoCompatibleStream`, `RateLimitExceeded`).
- `DirectStreamUrl` est un chemin relatif (`/videos/{Id}/original.mkv?DeviceId&MediaSourceId&PlaySessionId&api_key`) qui **contient le jeton** dans `api_key`. `TranscodingUrl` aussi (`/videos/{Id}/master.m3u8?…&api_key&…&TranscodeReasons`).
- Sans `api_key`, avec le jeton dans l'en-tête `X-Emby-Token`, le flux direct répond `206` et honore `Range` ; sans aucune authentification : `401`.
- Le serveur contient du mkv, mp4 et avi ; vidéo h264, hevc (dont HDR10 et Dolby Vision profil 8.1), av1, mpeg4, mpeg2video ; audio aac, ac3, eac3, dts, truehd, flac, opus, mp3 ; sous-titres subrip, ass, PGS, DVD. Avec le profil ci-dessous, les 35 combinaisons échantillonnées sortent toutes en lecture directe.
- Avec un profil restreint, le serveur bascule en transcodage HLS et explique pourquoi dans `TranscodeReasons`.

## Décision
- Le `PlaybackCoordinator` vit en Rust (`src-tauri/src/emby/playback.rs`). L'UI appelle une seule commande `emby_play(itemId)` et ne reçoit qu'un résumé : méthode, conteneur et raisons de transcodage. Aucune URL ni aucun jeton.
- Profil d'appareil libmpv : lecture directe des conteneurs et codecs que FFmpeg/libmpv décode, sous-titres intégrés (`Embed`) rendus par mpv. Un profil de transcodage HLS (ts, h264) reste déclaré pour que le serveur indique quand un transcodage serait nécessaire et pourquoi. Débit maximal : 400 Mbit/s, constante tant qu'il n'y a pas de réglage (réseau local).
- Ordre de choix : Direct Play, puis Direct Stream, puis Transcode, d'après les drapeaux de la source renvoyée par le serveur. L'URL vient toujours du serveur.
- URL de lecture : chemin relatif sous `/videos/` uniquement, préfixé par l'adresse du serveur (`/emby`). Une URL absolue ou un autre chemin est refusé. Les paramètres porteurs du jeton (`api_key`, `ApiKey`, `X-Emby-Token`, `AccessToken`) sont retirés ; le jeton part dans l'en-tête `X-Emby-Token` (option `http-header-fields` de mpv, déjà masquée dans les journaux).
- **Le transcodage n'est pas lancé dans cette tranche.** Sans le rapport de fin de lecture (`/Sessions/Playing/Stopped`) et l'arrêt de l'encodage, un ffmpeg resterait actif sur le serveur. La fiche affiche alors « transcodage nécessaire » avec les raisons du serveur. Il arrivera avec le suivi de lecture.
- Erreurs typées : `playbackRefused` (`NotAllowed`, `RateLimitExceeded`), `noCompatibleStream`, `transcodeRequired`.

## Alternatives
- **Construire `/Videos/{Id}/stream?static=true` à la main** : marche, mais contourne la décision du serveur (droits de l'utilisateur, limite de débit, sources multiples). Écarté.
- **Garder `api_key` dans l'URL** : plus simple, mais le jeton apparaîtrait dans les journaux de mpv, le cache HTTP et les diagnostics. Écarté, puisque l'en-tête est accepté.
- **Profil minimal (h264/aac seulement)** : provoquerait des transcodages inutiles sur le serveur alors que libmpv décode presque tout. Écarté.

## Conséquences
- La lecture directe couvre tout le catalogue actuel. Un média qui demande un transcodage n'est pas lisible tant que le suivi de lecture n'existe pas.
- Le profil déclare des formats que libmpv sait décoder, pas des formats testés un par un : HDR, Dolby Vision et l'audio sans perte restent à vérifier dans la matrice de lecture (Gate 1 / TESTING.md).
- `PlaySessionId` et `MediaSourceId` sont conservés côté Rust pour le futur rapport de lecture.
