# ADR 0081 — Qualité pendant la lecture (Android TV)

Statut : Accepté (2026-11-10). Complète le débit maximal de Réglages (ADR 0051), « Informations de lecture » (ADR 0061) et le titre « Lecture » du menu ▼ (ADR 0072, 0078). Le rapport de session suit ADR 0024.

## Contexte
Demande du propriétaire : changer de qualité sans quitter le lecteur, par exemple baisser le débit quand l'accès distant saccade, ou repasser en « Maximale » une fois de retour sur le réseau local. Aujourd'hui, le débit maximal ne se règle que dans Réglages (deux valeurs : réseau local et accès distant, ADR 0051) et ne s'applique qu'à la lecture suivante.

Ce que disent les sources (lues le 2026-11-10) :
- Référence de l'API Emby (`dev.emby.media`, `MediaInfoService`) : `POST /Items/{Id}/PlaybackInfo` accepte dans le corps `PlaybackInfoRequest` les champs `MaxStreamingBitrate`, `StartTimeTicks`, `MediaSourceId` et `CurrentPlaySessionId`, sans description de leur effet.
- Référence de l'API Emby (`HlsSegmentService`) : `DELETE /Videos/ActiveEncodings`, paramètres `DeviceId` et `PlaySessionId` (tous deux en requête, obligatoires), « Used to stop encoding processes when needed ».
- Wiki Emby « Playback Check-ins » (`github.com/MediaBrowser/Emby/wiki/Playback-Check-ins`) : les valeurs d'`EventName` d'un rapport de progression comprennent `AudioTrackChange`, `SubtitleTrackChange` et `QualityChange` ; la progression doit être envoyée « immédiatement après toute interaction de l'utilisateur avec le lecteur ».
- L'ancien client web libre d'Emby ajoutait lui-même `StartTimeTicks` à l'adresse d'un transcodage progressif et se déplaçait lui-même dans un flux HLS : l'adresse de transcodage rendue par le serveur ne contient donc a priori pas la position de départ.

Mesuré en ADR 0024 sur le serveur du propriétaire : un arrêt envoyé avec `Failed=true` n'enregistre pas la position ; une progression au-delà de 90 % marque l'élément vu quoi qu'il arrive.

## Décision
- **Une ligne « Qualité »**, première du titre « Lecture » du menu ▼. Valeurs : la liste de Réglages, « Maximale (automatique) » puis 40 Mbit/s jusqu'à 1,5 Mbit/s, mêmes libellés (`BITRATE_CHOICES`, `capLabel`, `offeredCap`, `mbits` réutilisés, rien de dupliqué). La lecture part du débit de Réglages pour le type d'adresse du serveur.
- **Touches** : ◀ baisse, ▶ monte, bloqués aux deux bouts ; seule la valeur affichée change. **OK applique** la valeur affichée. Ligne d'aide : mode de lecture et « ◀ ▶ puis OK pour changer » ; après un choix non appliqué, « OK applique ce débit à cette lecture » ; pendant le changement, « Changement en cours… » ; après un refus, « Échec : la qualité n'a pas changé ».
- **Pas de minuterie (anti-rebond)** : parcourir les valeurs ne recharge rien, seul OK déclenche un rechargement. Une minuterie rechargerait le flux pendant qu'on passe d'une valeur à l'autre, et une validation explicite est la règle des autres actions coûteuses du menu. Un seul changement à la fois : ◀ ▶ et OK sont ignorés tant que le précédent n'est pas fini.
- **Négociation** : nouvel appel `PlaybackInfo` par le chemin existant (`EmbyClient.playbackInfo`, `plan`), avec le profil d'appareil au nouveau `MaxStreamingBitrate` (« Maximale » = le plafond habituel), la version en cours (`MediaSourceId`) et la position courante (`StartTimeTicks`). Le serveur décide du mode (lecture directe, flux direct, transcodage) ; l'interface ne construit jamais d'adresse et rien n'est déduit de l'extension. `CurrentPlaySessionId` n'est pas envoyé (voir Alternatives).
- **Rechargement** : par le chemin de l'épisode suivant (`replacedEnds`, `PlayerEngine.load(source, position)`), à la position atteinte au retour de la réponse. Les pistes audio et sous-titres choisies au menu sont remises par les préférences mémorisées (`applyTracks`, ADR 0062) ; la vitesse, les décalages et le format, que `loadfile` remet par défaut, sont reposés une fois le fichier chargé. Le mode nuit et le style des sous-titres restent posés. La pause n'est pas gardée : la lecture reprend.
- **Rapport de session** (wiki « Playback Check-ins ») : ni arrêt ni nouveau démarrage. Une fois le nouveau fichier chargé, un rapport de progression `EventName=QualityChange` porte le nouveau `MediaSourceId`, le nouveau `PlaySessionId`, le nouveau `PlayMethod` et la position ; les rapports suivants et l'arrêt final utilisent la nouvelle session. Aucun arrêt n'est envoyé au changement : il ne peut donc pas marquer l'élément vu.
- **Transcodage remplacé** : si l'ancien flux était transcodé et que la session change, `DELETE /Videos/ActiveEncodings?DeviceId=…&PlaySessionId=<ancienne>` est envoyé une fois le nouveau fichier chargé, jamais avant (mpv lit encore l'ancien flux). Le jeton reste dans l'en-tête `X-Emby-Authorization`, jamais dans l'adresse. Un échec est journalisé sans autre effet. Sur Windows (ADR 0009), l'arrêt suffisait ; ici aucun arrêt n'est envoyé, d'où cet appel.
- **Portée** : cette lecture seulement. Réglages n'est pas modifié ; l'épisode suivant et toute nouvelle lecture repartent du débit de Réglages (`PlayerUi.reset`).
- **« Informations de lecture »** : mode, raisons de transcodage et « Débit maximal » sont relus du nouveau flux ; ce dernier ajoute « · choisi pour cette lecture » quand la valeur diffère de Réglages.
- **Échec** : un refus du serveur ou une adresse de flux illisible laisse le flux en cours intact et la ligne revient au débit en cours. Un échec de chargement du nouveau fichier suit le chemin de fin existant (arrêt, `Failed` si la fin est précoce, ADR 0024).
- **Logique pure** dans `player/Quality.kt` (liste, pas bloqués, états choisi/en cours/appliqué/refusé, libellés, ligne « Débit maximal », présence de `StartTimeTicks` dans une adresse) ; `PlaybackTracker.switched` pour le rapport.

## Alternatives
- **Arrêt puis nouveau démarrage** : simple pour le serveur, mais un arrêt `Failed=true` perd la position (ADR 0024) et un arrêt `Failed=false` au-delà de 90 % marquerait l'élément vu. Rejeté.
- **`CurrentPlaySessionId`** dans la requête : pourrait laisser le serveur arrêter lui-même l'ancien transcodage, mais son effet n'est pas documenté et il pourrait le couper avant que mpv ait ouvert le nouveau flux. Rejeté au profit du `DELETE` après chargement.
- **Minuterie d'anti-rebond** (appliquer 1 à 2 s après le dernier ◀ ▶) : rechargements involontaires en parcourant la liste. Rejeté.
- **Enregistrer le choix dans Réglages** : une baisse ponctuelle deviendrait le réglage de toutes les lectures. Rejeté ; Réglages reste le défaut.
- **Retenir la pause** : une pause posée avant le rechargement demanderait un état de plus à suivre pendant le chargement ; rare pendant un changement de qualité. Reporté.

## Conséquences
- Le menu ▼ gagne une ligne ; l'aide des commandes cite la qualité.
- Une coupure de quelques secondes à chaque changement (nouvel appel au serveur et rechargement).
- En transcodage, la piste audio est celle que le serveur a mise dans le flux : le choix fait au menu n'est remis que s'il existe dans le nouveau flux.
- Réversible : retirer la ligne, `Quality.kt`, `switched` et `stopEncoding` ne touche à rien d'autre ; rien n'est stocké.

## Vérification
- Tests unitaires : `QualityTest` (liste identique à Réglages, pas bloqués aux deux bouts et cap non proposé, choix en attente, changement en cours, appliqué et refusé, libellés, « Débit maximal », `StartTimeTicks` repéré par son nom) ; `PlaybackTrackerTest` (rien avant le nouveau fichier, puis une progression `QualityChange` avec la nouvelle session, sans arrêt ; arrêt final sur la nouvelle session ; changement ignoré pour un autre élément, après un nouveau démarrage ou un arrêt) ; `EmbyClientTest` sur MockWebServer (`StartTimeTicks` dans le corps et absent par défaut ; `DELETE /emby/Videos/ActiveEncodings` avec `DeviceId` et `PlaySessionId`, jeton dans l'en-tête seulement ; session invalide jamais envoyée). `testDebugUnitTest`, `assembleDebug` et `lintDebug` passent.
- **NON VÉRIFIÉ sur le Shield et le serveur** :
  - que le serveur accepte une progression `QualityChange` portant un nouveau `PlaySessionId` et l'affiche dans le tableau de bord ;
  - l'effet du `DELETE /Videos/ActiveEncodings` (transcodage réellement arrêté) ;
  - que `StartTimeTicks` dans la requête ne décale pas le flux une seconde fois avec l'option `start` de mpv (le journal `startInUrl=` le dira) ;
  - la durée de la coupure, le retour des pistes, de la vitesse, des décalages et du format, et le focus du menu pendant et après le changement.
