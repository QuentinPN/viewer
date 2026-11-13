# ADR 0117 — Qualité pendant la lecture (desktop)

- Statut : accepté
- Date : 2026-11-13

## Contexte

L'app Android TV permet de changer le débit pendant la lecture (ADR 0081). Le lecteur desktop ne le permettait pas : il fallait passer par Réglages puis relancer la lecture.

## Décision

- « Options de lecture » propose un groupe « Qualité » : « Maximale (automatique) » puis les débits que le serveur propose (`emby_playback_quality`). Le changement n'est appliqué qu'au clic sur « Appliquer ». Parcourir les choix au clavier ne relance donc pas le flux.
- La commande Tauri `emby_change_quality` refait un PlaybackInfo pour la même version (`MediaSourceId`), avec ce plafond, à partir de la position atteinte. Elle charge ensuite le flux dans le même moteur mpv. L'interface ne construit aucune URL.
- Le rapport de session reprend la sémantique de la TV :
  - pas de `Stopped`, pas de nouveau `Playing` ;
  - une fois le nouveau fichier chargé, un `Progress` avec `EventName=QualityChange` qui porte le nouveau `PlaySessionId` ;
  - si l'ancien flux était un transcodage et que la session a changé, `DELETE /Videos/ActiveEncodings?DeviceId=&PlaySessionId=` arrête ce transcodage côté serveur.
- Si le nouveau flux échoue, l'ancienne lecture est arrêtée et signalée comme d'habitude.
- Réglages n'est pas modifié : le plafond ne vaut que pour cette lecture.
- Les décalages sous-titres et audio, la vitesse, le format et le mode nuit sont conservés.

## Conséquences

- Le comportement contre un vrai serveur n'a pas été vérifié : la lecture n'a pas été observée dans l'app.
- Le tracker de session gagne une phase `Switching`, couverte par des tests unitaires Rust.
