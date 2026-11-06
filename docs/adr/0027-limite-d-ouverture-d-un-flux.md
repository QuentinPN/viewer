# ADR 0027 — Limite d'attente à l'ouverture d'un flux

Statut : Accepté (2026-11-06). Complète les ADR 0022 et 0023.

## Contexte
« Black Panther » reste indéfiniment sur « Ouverture du flux… », alors que son flux répond `500 Stale file handle`. La mesure de performance avait attribué ce blocage à la lecture directe. Une trace temporaire du moteur, sur le vrai film, montre autre chose :

1. **Lecture directe** : le `500` donne un `EndFile` en erreur en 20 ms, et le repli de l'ADR 0023 part aussitôt. L'ADR 0022 disait juste.
2. **Transcodage** : les listes HLS répondent, mais chaque segment répond `500`. Le démultiplexeur HLS de FFmpeg signale ``Segment N of playlist 0 failed too many times, skipping`` et passe au suivant. Sur une liste de film, il enchaîne ainsi environ 7 requêtes par seconde : 511 segments en 70 s, sans `FileLoaded` ni `EndFile`.

Rien ne borne cette attente : `StallWatch` n'est armé qu'une fois le fichier chargé. Un serveur qui accepte la connexion sans jamais répondre relève de la même lacune, à ceci près que les relances de FFmpeg finissent par échouer.

## Décision
**Une limite de 45 s entre le début d'un chargement et `FileLoaded`**, dans `StallWatch`, donc dans le moteur et indépendamment de l'interface. Elle part de `MPV_EVENT_START_FILE`, le premier signal de mpv pour un fichier, et une mise en pause ne la suspend pas : rien n'est lu pendant l'ouverture.

**À expiration, le chemin d'échec existant s'applique.** Le moteur demande `stop` à mpv, puis émet `EndFile { reason: Error }` avec le détail « le flux ne s'ouvre pas ». L'interface fait donc ce qu'elle fait déjà :
- source directe : repli sur le transcodage (ADR 0023) ;
- transcodage : « Le lecteur n'a pas pu ouvrir ce fichier. », le détail, et « Retour ».

Le `stop` part désormais avant l'événement. Le repli envoie son `loadfile` en réaction à l'événement : mpv traite ainsi toujours l'arrêt en premier, et cette commande ne peut pas annuler le nouveau chargement. Ce nouvel ordre vaut aussi pour la limite de 30 s de l'ADR 0022.

**Un échec arrête mpv.** Quand FFmpeg renonce à une liste, mpv la relit comme une playlist et essaie chaque segment comme un fichier, avec un `EndFile` en erreur par segment. Observé sur un relais local qui répond vite : le message s'affichait alors que les requêtes continuaient. Tout `EndFile` en erreur déclenche donc aussi `stop`, avant l'événement, ce qui vide la playlist.

**Pourquoi 45 s.** C'est un choix de prudence, pas une mesure : les ouvertures mesurées prennent de 1 à 6 s, mais ni un NAS qui sort de veille ni un transcodage 4K lent sur le mini-PC n'ont été mesurés. Se tromper dans ce sens fait attendre plus longtemps un flux en panne. Se tromper dans l'autre empêche de regarder un film lisible. Le pire cas est de 90 s : 45 s sur la source directe, puis 45 s sur le transcodage.

**L'écran dit la vérité pendant l'attente.** `PlayerView` passait `active` à vrai dès que le moteur répondait à ses diagnostics, même sans fichier ouvert : une source directe muette affichait le badge « Lecture directe » sur une image noire pendant toute l'attente. L'ADR 0023 avait relevé ce défaut pour le repli seulement. Le lecteur n'est désormais actif que si mpv a ouvert un conteneur, sinon « Ouverture du flux… » reste affiché jusqu'à `FileLoaded`.

## Alternatives
- **Détecter les segments en échec dans les messages de FFmpeg** : le message arriverait en 1 s environ au lieu de 45 s, et le serveur recevrait bien moins de requêtes. Mais une décision reposerait sur le texte d'un journal, qui peut changer avec FFmpeg, et ce cas seul ne couvre pas un serveur muet. La limite reste nécessaire. Cette détection pourra s'y ajouter : non retenue dans ce lot.
- **Limite dans `PlayerView`** : une minuterie de plus dans l'interface, qui ne pourrait pas arrêter mpv elle-même et dépendrait de la vue. L'ADR 0022 a déjà écarté ce choix. Rejeté.
- **Réutiliser les 30 s de l'ADR 0022** : plus simple, mais plus risqué pour une ouverture lente, et ces 30 s couvrent une autre attente, celle d'une position qui ne bouge plus. Rejeté.

## Conséquences
- L'utilisateur attend 45 s devant « Ouverture du flux… » sur un film dont les segments échouent, et le serveur reçoit environ 300 requêtes en échec pendant ce temps, avec cette cadence.
- Un transcodage réel plus lent que 45 s à livrer son premier segment affiche l'échec. « Retour » permet de réessayer. Le transcodage continue probablement côté serveur, ce qui peut rendre le deuxième essai plus rapide : non vérifié.
- Une ouverture réussie n'est pas modifiée : l'échappement et « Retour » arrêtent le lecteur comme avant, et cet arrêt désarme la limite.
- **Non couvert :** un segment qui échoue après le début de la lecture. FFmpeg le saute et l'image saute avec lui, sans erreur, comme avant cet ADR.

## Validation
Tests unitaires de `StallWatch`. Test sur le vrai libmpv, avec un serveur local dont tous les segments répondent `500` : fin en erreur à la limite, sans `FileLoaded`, puis plus aucune requête. Scénario `gate4-fallback`, étendu avec une source directe muette et des segments en erreur. Essai sur le vrai film tant que le fichier reste illisible. Résultats : `docs/evidence/gate4-ouverture-2026-11-06.md`.
