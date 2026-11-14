# ADR 0124 — Lecture liée ordi ↔ TV : protocole et comportement

- Statut : accepté
- Date : 2026-11-14

## Contexte

L'ADR 0123 retient le relais Emby (`SendString`) comme transport. Cette ADR fixe le contenu des messages, les rôles, la correction de dérive et le comportement de l'interface. Elle sert de spécification commune au desktop (Rust + Svelte) et à l'app Android TV (Kotlin). Les vecteurs de test partagés sont dans `docs/sync/vectors.json` : les deux suites de tests lisent ce même fichier.

## Rôles

- **Leader = ordi** : c'est sa lecture que voient les amis sur Discord. Il applique toutes les commandes et diffuse son état.
- **Suiveur = TV** : il recale sa lecture sur l'état du leader. Ses actions (pause, saut, passer l'intro…) sont envoyées au leader sous forme de commande.
- Un ordi suit au plus une TV à la fois, et une TV suit au plus un ordi.
- Le volume n'est jamais transmis. Les pistes audio et sous-titres ne sont alignées qu'au chargement d'un titre (`load`).

## Session Emby

- Sur les deux apps, la socket `/embywebsocket` reste ouverte tant qu'une session est connectée, pas seulement sur l'accueil. Les règles de l'ADR 0110 s'appliquent toujours : `LibraryChanged` ne relit l'accueil que s'il est affiché ; keep-alive 30 s ; reconnexion après 2, 5, 15 puis 30 s.
- À chaque ouverture de socket : `POST /Sessions/Capabilities/Full` `{"PlayableMediaTypes":["Video"],"SupportedCommands":["SendString"],"SupportsMediaControl":true}`.
- **Id de sa propre session** : l'entrée de `GET /Sessions?ControllableByUserId={UserId}` dont le `DeviceId` est le sien. On le relit après chaque reconnexion.
- **Appareils** : les entrées de cette liste autres que la sienne, dont `Client` vaut `"Viewer TV"` (vu de l'ordi) ou `"Viewer"` (vu de la TV), avec `SupportsRemoteControl: true`. Le nom affiché est `DeviceName`, tiré de la liste du serveur, jamais d'un message.
- **Envoi** : `POST /Sessions/{IdPair}/Command`, corps `{"Name":"SendString","Arguments":{"String":"<enveloppe>"}}`.
- **Réception** : `{"MessageType":"GeneralCommand","Data":{"Name":"SendString","Arguments":{"String":"<enveloppe>"}}}`. Tout autre `GeneralCommand` ou `Playstate` est ignoré.

## Enveloppe

Un objet JSON sur une ligne, au plus 1024 octets en UTF-8 :

```json
{"tv":"sync","v":1,"from":"<Id de session>","link":"<id>","seq":12,"kind":"state", ...champs du kind}
```

- `viewer` vaut `"sync"`, `v` vaut `1`.
- `from` : Id de session de l'émetteur, 1 à 64 caractères `[A-Za-z0-9_-]`.
- `link` : identifiant de la liaison, 8 à 64 caractères `[A-Za-z0-9_-]`, tiré au hasard par celui qui lance la liaison (`join` ou `load` hors liaison).
- `seq` : entier de 0 à 2^31−1, croissant pour chaque émetteur.
- `kind` : une des valeurs ci-dessous.
- Un champ inconnu est ignoré, ce qui permet d'ajouter des champs plus tard.
- **Rejet sans erreur** (aucune réponse, rien dans les journaux hormis le motif) : JSON invalide ou trop long, `viewer`/`v` différents, champ obligatoire absent ou hors bornes, `kind` inconnu.
- **Hors sujet** : en liaison, un message dont `from` n'est pas le pair ou dont `link` n'est pas la liaison courante. Il en va de même d'un `seq` inférieur ou égal au dernier reçu de ce pair dans la liaison. Exception : un `join` ou un `load` d'une autre session reçoit `bye busy`, envoyé à cette session et sous son `link`. Hors liaison, seuls `join` (côté ordi) et `load` (côté TV) sont lus ; ils ouvrent la liaison.
- Les positions sont en millisecondes (`positionMs`, entier, 0 à 86 400 000). Les `itemId` et `mediaSourceId` font 1 à 64 caractères `[A-Za-z0-9_-]` (Emby donne par exemple `mediasource_104067`). Les index de piste sont des entiers de −1 à 999, −1 voulant dire « aucune ».

## Messages

| kind | sens | champs | rôle |
|---|---|---|---|
| `join` | TV → ordi | — | « Regarder avec l'ordi » : la TV demande à suivre. |
| `load` | ordi → TV | `itemId`, `mediaSourceId?`, `positionMs`, `audioStreamIndex?`, `subtitleStreamIndex?` | Ouvre ce titre à cette position, en pause, puis répond `ready`. Envoyé au début d'une liaison et à chaque changement de titre. |
| `ready` | TV → ordi | `itemId`, `positionMs` | Titre chargé, mis en mémoire tampon et en pause à cette position. |
| `state` | ordi → TV | `itemId`, `positionMs`, `paused`, `buffering` | État du leader au moment de l'envoi. Envoyé toutes les secondes et tout de suite après chaque changement : pause, reprise, saut, mémoire tampon. |
| `cmd` | TV → ordi | `action` ∈ `pause`, `play`, `seek`, `next`, `previous` ; `positionMs` si `seek` | Action faite sur la TV. Les sauts sont toujours en position absolue. |
| `buffering` | TV → ordi | `on` (booléen) | La TV remplit sa mémoire tampon, ou a fini. |
| `ping` | TV → ordi | `t` (horloge monotone de la TV, ms), `driftMs?` (dernier écart mesuré) | Toutes les secondes. |
| `pong` | ordi → TV | `t` (renvoyé tel quel) | Réponse immédiate à `ping`. |
| `bye` | les deux | `reason` ∈ `stopped`, `left`, `idle`, `busy`, `timeout` | Fin de la liaison. |

## Déroulé

**Démarrage depuis la TV.** La TV envoie `join` avec un nouveau `link`.

- Si l'ordi ne lit rien, il répond `bye idle`. La TV affiche « L'ordi ne lit rien pour l'instant. »
- S'il suit déjà une autre TV, il répond `bye busy`.
- Sinon, il se met en pause et envoie `load` avec le titre, la position et les pistes en cours.

**Démarrage depuis l'ordi.** L'ordi, en lecture, se met en pause et envoie `load` avec un nouveau `link`.

- Si l'app TV est ouverte, elle accepte sans rien demander. Le propriétaire est le seul utilisateur, et l'action vient de lui. Si la TV lisait autre chose, cette lecture s'arrête (rapport d'arrêt normal).
- Si la TV suit déjà un autre ordi, elle répond `bye busy`.

**Chargement.** La TV fait sa propre négociation `PlaybackInfo` (ADR 0039) pour `itemId` / `mediaSourceId`. Elle applique les pistes reçues si elles existent, va à `positionMs`, reste en pause et répond `ready` une fois la mémoire tampon pleine.

- Pendant ce temps, l'ordi affiche « En attente de la TV… » avec « Continuer sans la TV ».
- À `ready`, l'ordi reprend la lecture et envoie `state` avec `paused: false`. Seule une pause faite par la liaison est levée : si l'ordi était déjà en pause avant, il y reste.
- **Pistes** : `audioStreamIndex` et `subtitleStreamIndex` sont l'index des pistes intégrées au fichier, qui est le même chez Emby (`MediaStream.Index`) et chez FFmpeg (`ff-index` de mpv). L'ordi ne les envoie qu'en Direct Play, et n'envoie pas un sous-titre externe. Sans index, la TV garde ses choix par défaut.
- Sans `ready` après 30 s, l'ordi envoie `bye timeout` et reprend seul.

**En liaison.**

- L'ordi applique chaque `cmd` comme une action locale. Il ignore les `cmd` reçus pendant un chargement.
- La TV applique tout de suite pause, reprise et saut sur elle-même, pour réagir au doigt. Elle envoie aussi `cmd` ; le `state` suivant fait foi.
- `next` / `previous` : l'ordi passe à l'épisode suivant ou précédent et envoie un nouveau `load` (même `link`). La TV ne passe jamais seule au suivant en liaison : sa carte « Épisode suivant » est masquée et elle attend un `load`.
- À la fin naturelle d'un épisode sur l'ordi, l'enchaînement de l'ordi décide. S'il passe au suivant, il envoie `load` ; sinon, `bye stopped`.
- Passer l'intro, le générique ou un chapitre depuis la TV : la TV calcule la position absolue avec ses propres segments ou chapitres et envoie `cmd seek`.
- `buffering on` reçu : l'ordi se met en pause et affiche « En attente de la TV… ». Il reprend à `buffering off` s'il n'a pas été mis en pause entre-temps par l'utilisateur. Après 15 s sans `off`, il envoie `bye timeout` et reprend seul.

**Fin.**

- Arrêter la lecture d'un côté (Retour, Stop, fermeture du lecteur) envoie `bye stopped`, et l'autre côté arrête aussi.
- « Délier » envoie `bye left`, et l'autre continue seul.
- **Perte du lien** : plus aucun message du pair pendant 5 s (`ping` pour l'ordi ; `state` ou `pong` pour la TV). Une coupure de socket seule ne suffit pas : la socket se reconnecte (ADR 0110), et le lien ne tombe qu'après ces 5 s de silence. Chaque côté affiche « Lien perdu avec <appareil>. La lecture continue ici. » et continue seul. Il n'y a pas de reprise automatique : on relance la liaison à la main.

## Recalage de la TV

Tous les temps viennent d'une horloge monotone locale ; on ne compare jamais l'heure de deux appareils.

- **Aller-retour** : `rttMs` est la médiane des 5 derniers `now − t` mesurés à chaque `pong` reçu. Pour un nombre pair, on prend la valeur triée d'index `n / 2`. Il vaut 0 tant qu'aucun `pong` n'est arrivé.
- **Ancre du leader** : le délai du relais Emby varie d'un `state` à l'autre, de quelques dizaines à quelques centaines de ms. Pour chaque `state` en lecture, la TV calcule l'ancre `positionMs − recvAt`. Elle garde les 5 dernières et retient la plus grande, celle du `state` le moins retardé ; la position utilisée est `ancre + recvAt`. Les ancres repartent de zéro sur une pause, une mise en mémoire tampon ou un autre titre. Elles repartent aussi quand une ancre tombe plus d'1 s sous la meilleure : le leader est revenu en arrière.
- **Position projetée du leader**, à l'instant `now`, pour le dernier `state` reçu à `recvAt` : `projected = positionMs + (paused || buffering ? 0 : (now − recvAt) + rttMs / 2)`. La division est entière, arrondie vers le bas.
- **Cible** : `target = max(0, projected − offsetMs)`. `offsetMs` est le réglage « Décalage TV », de 0 à 1500 ms par pas de 100, à 0 par défaut. Il retarde la TV pour coller à l'image que voient les amis sur Discord.
- **Écart** : `e = positionTv − target` ; positif quand la TV est en avance.
- **Décision**, `decide(e, leaderPaused, tvPaused, correcting, sinceSeekMs)` :
  1. Si `sinceSeekMs < 1500`, rien : un saut est en cours.
  2. Si le leader est en pause ou en mémoire tampon : la TV se met en pause si elle ne l'est pas, à la vitesse 1. Si `|e| > 60`, elle saute à `target`.
  3. Sinon, si la TV est en pause : reprise.
  4. Si `|e| > 1000`, saut à `target`, vitesse 1.
  5. Si une correction est en cours et `|e| ≤ 20`, retour à la vitesse 1.
  6. Sinon, si une correction est en cours ou si `|e| ≥ 60`, vitesse 0,96 quand `e > 0` et 1,04 quand `e < 0`.
  7. Sinon, rien.
- La décision est évaluée toutes les 250 ms et à chaque `state` reçu. La vitesse passe par la propriété `speed` de mpv ; la correction de hauteur du son de mpv (`audio-pitch-correction`, active par défaut) évite tout effet audible.
- **Comportement de mpv mesuré sur la Shield** (14/11/2026) :
  - mpv retire scaletempo2 de la chaîne audio à la vitesse 1 exacte, ce qui fait sauter `time-pos` d'environ 300 ms. Pendant un lien, la « vitesse 1 » de la TV est donc 1,0001 : le filtre reste en place, et le 0,1 ms/s gagné se corrige comme n'importe quel écart. La vraie vitesse 1 revient à la fin du lien.
  - Chaque changement de vitesse fausse aussi `time-pos` pendant un moment. L'attente de 1500 ms qui suit un saut s'applique donc aussi après un changement de vitesse : ni décision ni mesure pendant ce temps.
  - Un essai par impulsions de durée calculée (`|e| × 25 ms` à ±4 %) a été abandonné. L'effet de vitesse se prolonge environ 2 s après le retour au repos, d'où une sur-correction.
- **Avance au saut** : un saut prend environ 1 à 1,5 s pendant lequel le leader avance, et la TV atterrit en retard ; sans compensation, elle ressaute en boucle. Quand le leader joue, la TV saute donc à `target + avance`. L'avance part de 0 et s'ajuste après chaque saut : à la première mesure stabilisée, `avance = clamp(avance − e, 0, 3000)`. En pause, la TV saute à `target` exactement.
- `driftMs` du `ping` est le dernier `e` arrondi, mesuré hors des temps d'attente. L'ordi l'affiche dans sa pastille.

## Interface

- **Ordi, lecteur** : bouton « Envoyer aussi sur la TV » dans la barre du haut. Il ouvre la liste des TV en ligne (`DeviceName`), avec « Aucune TV Viewer n'est ouverte. » si la liste est vide.
  - En liaison, une pastille « Lié à <appareil> · ±40 ms » s'affiche dans l'OSD, avec le dernier `driftMs` reçu (« Lié à <appareil> » seul tant qu'aucun n'est reçu).
  - Le même bouton propose alors « Délier ».
- **TV, lecteur et pages de titre** : « Regarder avec l'ordi » ouvre la liste des ordis en ligne.
  - En liaison, la même pastille s'affiche dans l'OSD avec l'écart mesuré, et l'action devient « Délier ».
  - Le réglage « Décalage TV » est dans Réglages › Lecture.
- **Messages** (français, tutoiement) :
  - « En attente de la TV… »
  - « Continuer sans la TV »
  - « <appareil> suit ta lecture. »
  - « Lien perdu avec <appareil>. La lecture continue ici. »
  - « L'ordi ne lit rien pour l'instant. »
  - « <appareil> suit déjà une autre lecture. »
  - « <appareil> ne répond pas. »
- **Délai de réponse** : sans réponse à `join` ou à `load` (`load` ou `bye` attendu, `ready` ou `bye` attendu) après 10 s, la demande est abandonnée avec « <appareil> ne répond pas. » Exception : un `load` déjà accepté laisse 30 s à la TV pour charger.

## Sécurité

- Aucune charge utile ne porte de jeton, d'URL ni de chemin de fichier. Chaque côté construit ses URL de flux à partir d'`itemId` par sa propre négociation.
- Les journaux ne gardent que `kind`, `seq` et le motif d'un rejet, jamais l'enveloppe brute.
- Les messages ne sont acceptés que d'une session du même utilisateur, ce que le serveur garantit déjà. En liaison, seul le pair est écouté.

## Conséquences

- Les deux côtés déclarent `SupportsMediaControl`, donc le tableau de bord Emby les voit pilotables. Ses commandes sont ignorées.
- Les deux appareils envoient leur propre rapport de lecture à Emby. C'est sans conséquence : même épisode, même utilisateur.
- Les deux apps doivent être ouvertes : Emby ne réveille pas une Shield en veille.
- L'ADR 0110 est amendée : la socket est ouverte toute la session.
