# ADR 0009 — Transcodage HLS

Statut : Accepté (2026-10-28).

## Contexte
L'ADR 0007 a reporté le transcodage : sans rapport d'arrêt, un ffmpeg serait resté actif sur le NAS. L'ADR 0008 a ajouté ce rapport. Il faut maintenant lire ce que le serveur refuse de servir tel quel, par exemple un débit au-delà du plafond, un conteneur ou un codec non pris en charge.

Ce qui a été observé sur Emby 4.10.1.0 (QNAP), avec un compte de test, un débit plafonné à 3 Mbit/s et des sessions courtes autorisées le 2026-10-28 :
- `PlaybackInfo` renvoie `SupportsDirectPlay` et `SupportsDirectStream` à faux. `TranscodingUrl` vaut `/videos/{Id}/master.m3u8?…&api_key=…&TranscodeReasons=ContainerBitrateExceedsLimit`, avec `TranscodingSubProtocol` `hls` et `TranscodingContainer` `ts`.
- La liste principale renvoie vers une variante `main.m3u8` sans `api_key`. La liste VOD est complète, avec des segments `ts` de 3 s.
- **Les segments sont servis sans authentification**. Seul le `PlaySessionId` les protège. C'est une propriété du serveur ; le client n'y peut rien.
- Par défaut, le serveur choisit le sous-titre forcé et l'incruste dans l'image (`SubtitleMethod=Encode`). Sur ce NAS, l'incrustation échoue : libass et fontconfig ne trouvent pas de polices, et ffmpeg renvoie une erreur 500 après environ 15 s. Retirer `SubtitleStreamIndex` ne change rien.
- Avec des `SubtitleProfiles` `External` pour les formats texte, l'incrustation disparaît. Chaque piste reçoit `DeliveryMethod: External` et une `DeliveryUrl` (`/Videos/{Id}/{MediaSourceId}/Subtitles/{Index}/0/Stream.subrip?api_key=…`). Le premier segment arrive en 4 à 6 s (x264 logiciel, 1280×533, AC3 copié).
- Le **premier** accès à un sous-titre externe peut prendre jusqu'à environ une minute : Emby l'extrait du fichier. Les accès suivants sont immédiats.
- `POST /Sessions/Playing/Stopped` avec le `PlaySessionId` arrête l'encodage. Ensuite `TranscodingInfo` disparaît de `/Sessions`, et un segment demandé renvoie 404.

## Décision
- Si la source n'autorise ni Direct Play ni Direct Stream mais autorise le transcodage, le plan devient `Transcode`.
  - L'URL est la `TranscodingUrl`, contrôlée comme les autres : chemin sous `/videos/`, `api_key` retiré, jeton passé à mpv dans l'en-tête `X-Emby-Token`.
  - Le conteneur est `TranscodingContainer`.
  - Les raisons (`TranscodeReasons`) sont traduites en français pour le badge du lecteur, par exemple « Transcodage · débit trop élevé ».
- Profil d'appareil : sous-titres `Embed` pour tous les formats, plus `External` pour `ass`, `ssa`, `subrip`, `srt` et `vtt`. En lecture directe, mpv lit les pistes intégrées. En transcodage, les pistes texte sont livrées à part.
- Sous-titres externes (transcodage seulement) :
  - chaque `DeliveryUrl` passe par le même contrôle que l'URL du flux, ce qui refuse toute URL hors du serveur et retire l'`api_key` ;
  - les pistes sont ajoutées à mpv par `sub-add` **asynchrone** (`mpv_command_async`) à `FILE_LOADED` ;
  - la piste `DefaultSubtitleStreamIndex` du serveur est sélectionnée, les autres sont ajoutées en `auto` ;
  - un échec ne fait que journaliser un avertissement.
  - L'appel est asynchrone parce que l'extraction peut durer une minute : un `sub-add` synchrone bloquerait le cœur de mpv pendant ce temps.
- Débit maximal : un champ de `EmbyService`, 400 Mbit/s par défaut. En build debug seulement, la variable `VIEWER_E2E_MAX_BITRATE` le remplace, pour forcer un transcodage dans les scénarios. Elle est ignorée en release.
- L'erreur `transcodeRequired` disparaît : un transcodage n'est plus un échec.
- Pas d'appel à `DELETE /Videos/ActiveEncodings` : le rapport d'arrêt (ADR 0008) suffit, et c'est vérifié sur le serveur réel.

## Alternatives
- **Laisser le serveur incruster les sous-titres** : échoue sur ce NAS (pas de polices), et coûte cher en CPU. Écarté pour le texte.
- **Désactiver les sous-titres en transcodage** : perd les sous-titres forcés choisis par le serveur. Écarté.
- **`sub-add` synchrone** : plus simple, mais l'image se fige jusqu'à une minute lors de la première extraction. Écarté.
- **Réglage de débit visible dans l'UI** : utile plus tard pour l'accès distant, mais il demande une page de réglages. Reporté.

## Conséquences
- Les sous-titres image (PGS, VOBSUB) d'un média transcodé restent incrustés par le serveur. C'est non vérifié, et probablement en échec sur ce NAS si l'incrustation passe aussi par libass.
- Un sous-titre externe peut apparaître dans le menu jusqu'à une minute après le début de la lecture, la première fois.
- Les segments HLS sont accessibles sans jeton à qui connaît le `PlaySessionId` : propriété du serveur, sans effet tant que l'accès reste local.
- Le saut dans un flux transcodé relance l'encodeur à la position demandée : 3,4 s mesurées pour un saut à 10 min.
- Les pistes audio et sous-titres choisies dans mpv ne sont toujours pas signalées au serveur (ADR 0008).
