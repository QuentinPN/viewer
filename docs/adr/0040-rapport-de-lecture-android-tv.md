# ADR 0040 — Rapport de lecture sur Android TV

Statut : Accepté (2026-11-09).

## Contexte
L'app Android TV (ADR 0038, 0039) lisait sans rien dire au serveur : pas de reprise, pas de statut « vu ». Le contrat est celui de l'ADR 0008, vérifié par le client Windows sur Emby 4.10.1.0 : `POST /Sessions/Playing`, `/Sessions/Playing/Progress`, `/Sessions/Playing/Stopped`, corps JSON en PascalCase, jeton dans l'en-tête.

## Décision
- Même automate que sous Windows, porté en Kotlin pur et testé (`emby/PlaybackTracker.kt`) : début au chargement du fichier, à la position de départ ; progression `Pause` / `Unpause` immédiate, `TimeUpdate` après un saut et toutes les 10 s (aussi en pause) ; arrêt à la fin, à la sortie du lecteur ou quand une autre lecture commence. Un fichier qui ne se charge pas n'est jamais annoncé.
- Une fin de fichier à plus de 30 s de la durée connue est signalée `Failed` (flux coupé) ; quitter le lecteur ne l'est pas. Le serveur décide seul du « vu » et de la reprise selon ses seuils.
- Les rapports partent dans l'ordre depuis une seule coroutine de processus (`PlaybackReporter`, dans `AppServices`), pas depuis l'activité : l'arrêt part encore après `onDestroy`. L'arrêt est réessayé une fois après 1 s si le serveur est injoignable ou répond 5xx.
- Événements du moteur : `time-pos` observé en entier (une notification par seconde, pas une par image), `duration`, `eof-reached` (avec `keep-open`, la fin d'un fichier ne produit pas de `END_FILE`), `MPV_EVENT_SEEK`.
- Le lecteur se ferme à la fin du fichier et quand il passe en arrière-plan (bouton Accueil) : la lecture ne continue pas sans image.
- Après chaque arrêt, un compteur (`stopped`) change ; l'accueil et la page série relisent leurs données en place, sans écran de chargement, focus gardé. Un échec de cette relecture garde l'affichage précédent.

## Alternatives
- **Rapports depuis `PlayerActivity` (lifecycleScope)** : la portée est annulée à la destruction, l'arrêt serait perdu. Écarté.
- **Renvoi différé d'un arrêt perdu (ADR 0024)** : pas dans cette tranche ; le serveur garde la dernière progression (au plus 10 s de retard) et ferme lui-même la session.
- **Relire la position de reprise au lancement (comme Windows)** : la position vient des données d'utilisateur de l'écran, relues après chaque arrêt. Suffisant tant qu'un seul appareil lit ; à revoir avec la page de détails.

## Conséquences
- La reprise et le « vu » sont partagés avec les autres clients Emby.
- Un processus tué n'envoie pas d'arrêt ; un arrêt perdu par une coupure réseau n'est pas renvoyé.
- Les pistes audio et sous-titres choisies ne sont pas signalées (indices mpv différents de ceux d'Emby, ADR 0008).
