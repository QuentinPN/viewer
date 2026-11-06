# ADR 0024 — Renvoi du rapport d'arrêt après une coupure

Statut : Accepté (2026-11-06). Complète les ADR 0008, 0012 et 0022.

## Contexte
Pendant une coupure, mpv continue sur ce qu'il a lu d'avance : jusqu'à 385,7 s mesurées pendant un transcodage. Les rapports de progression échouent, et le rapport d'arrêt aussi, avec un seul nouvel essai après 1 s (ADR 0008). Le serveur garde donc la dernière progression reçue avant la coupure. Mesuré par `gate4-hls-cut` : 610,0 s enregistrées pour 758,3 s lues. « Reprendre » fait revoir tout cet écart.

Mesuré sur Emby 4.10.2.0 avec le compte de test, par des requêtes directes :
- un arrêt avec `Failed=true` n'enregistre pas sa position (610 s gardées pour un arrêt à 700 s) ;
- après 60 s environ sans rapport, le serveur ferme lui-même la lecture et enregistre une position extrapolée (670 s) ; un arrêt envoyé ensuite est ignoré, même avec `Failed=false` ;
- une progression envoyée ensuite enregistre sa position et rouvre la lecture ; l'arrêt qui suit la referme (800 s gardées, plus de lecture en cours sur le serveur).

Renvoyer l'arrêt seul ne suffit donc pas : le premier essai du scénario `gate4-hls-cut` restait à 610,0 s pour 888,6 s lues.

## Décision
**Le rapport d'arrêt perdu est gardé et renvoyé, précédé d'une progression.** Si l'arrêt échoue encore après le nouvel essai pour une cause passagère (serveur injoignable, délai dépassé, erreur 5xx), la tâche de suivi le garde en mémoire et le renvoie : après 5 s, 10 s, puis toutes les 15 s. Chaque essai envoie une progression à la dernière position atteinte par le lecteur, puis l'arrêt avec son `Failed` d'origine. Une fois reçu, la tâche relit l'état de l'élément et émet `emby://playback-stopped` : la fiche et l'accueil se mettent à jour sans autre code. Le renvoi est dans cette tâche, indépendant de l'interface.

**Un seul rapport, et jamais sur une autre session.** Le jeton de la session d'origine est gardé avec le rapport. S'il n'est plus celui de la session ouverte (déconnexion, changement de serveur, réauthentification), le rapport est abandonné sans requête.

**Il passe avant la lecture suivante.** `emby_play` demande d'abord un renvoi, et attend au plus 5 s, pour que « Reprendre » lise la bonne position. Une nouvelle lecture abandonne ensuite tout rapport encore en attente : un arrêt tardif ne doit jamais écraser la position d'une lecture plus récente.

**Abandon après 1 h.** Plus le rapport est ancien, plus il risque d'écraser une lecture faite ailleurs entre-temps. Le serveur reste la source de vérité.

La fermeture de la fenêtre tente un dernier renvoi, dans la limite d'attente déjà en place (3 s).

## Alternatives
- **Enregistrer la position sur disque** : le rapport survivrait à un redémarrage, mais il faudrait une écriture locale d'un état du serveur et un cas de plus à arbitrer. Le recul reste possible seulement si l'application est fermée pendant la coupure. Rejeté pour l'instant.
- **Afficher la position locale dans « Reprendre »** : le client deviendrait une source de vérité concurrente, contre l'ADR 0008. Rejeté.
- **Renvoyer aussi les progressions perdues** : seule la dernière position compte, et l'arrêt la porte. Rejeté.
- **Déclencher le renvoi depuis `coordinator.verify()`** : le renvoi dépendrait de l'interface, et le serveur peut être joignable sans qu'une vérification tourne. Rejeté.

## Conséquences
- Une lecture sur un autre appareil pendant l'heure de coupure peut être écrasée par la position, plus ancienne, de ce rapport.
- Application fermée pendant la coupure : le rapport est perdu et le serveur garde sa dernière progression, comme avant.
- Le statut « vu » reste décidé par le serveur, avec la position réellement atteinte. Mesuré : une progression à 97 % marque l'élément vu, même suivie de `Failed=true`. C'est aussi ce que ferait une progression envoyée sans coupure.
- Un arrêt `Failed=true` reçu sans coupure n'enregistre pas non plus sa position : le serveur garde la dernière progression, reçue il y a au plus l'intervalle de progression. Écart faible, non corrigé ici.

## Validation
Tests unitaires du calendrier de renvoi, test contre le serveur factice (aucune requête si la session a changé), scénario `gate4-hls-cut` sur le serveur réel. Résultats : `docs/evidence/gate4-renvoi-arret-2026-11-06.md`.
