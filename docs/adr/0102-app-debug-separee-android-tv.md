# ADR 0102 — Version debug séparée sur Android TV

Statut : Accepté (2026-11-11). Remplace le point « Un `applicationIdSuffix` pour la version debug » de l'ADR 0095.

## Contexte
La version debug et la release avaient le même identifiant (`io.github.quentinpn.viewer.tv`). Chaque `installDebug` des tests remplaçait donc la release du propriétaire, et les tests sur la Shield tournaient sur la session installée, quel que soit le compte. Le propriétaire demande une seconde app pour les tests, connectée à un autre compte que le sien.

## Décision
- Le type de build `debug` prend `applicationIdSuffix = ".debug"` (`io.github.quentinpn.viewer.tv.debug`) et `versionNameSuffix = "-debug"`.
- Le libellé vient d'un placeholder `appLabel` : « Viewer » en release, « Viewer Debug » en debug.
- La bannière debug (`app/src/debug/res/drawable/banner.xml`) reprend celle de la release avec une bande orange en bas, pour distinguer les deux tuiles dans le lanceur.
- Les deux apps ont leurs propres données : session, réglages, et identifiant d'appareil Emby (UUID tiré à l'installation, `SessionStore.deviceId`). Le serveur les voit comme deux appareils.
- La connexion de la version debug (compte de test « claude ») est faite une fois par le propriétaire : les tests ne saisissent pas de mot de passe.
- Les tests sur l'appareil utilisent la version debug (`installDebug`, `io.github.quentinpn.viewer.tv.debug/io.github.quentinpn.viewer.tv.MainActivity`) et ne touchent plus à la release. Le script `scripts/spike/run-gate-tv1.ps1` lance l'outil de test sous le nouvel identifiant.

## Conséquences
- La release n'est plus remplacée par les tests et garde le compte du propriétaire.
- Les noms de classes ne changent pas : seul l'identifiant d'application change, donc les composants se lancent avec leur nom complet (`io.github.quentinpn.viewer.tv.debug/io.github.quentinpn.viewer.tv.MainActivity`).
- La première installation de la version debug demande une connexion.
