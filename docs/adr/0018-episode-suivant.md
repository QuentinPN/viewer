# ADR 0018 — Épisode suivant en fin de lecture

Statut : Accepté (2026-11-01).

## Contexte
L'utilisateur a choisi le 2026-10-28 la « Proposition avec compte à rebours » (ADR 0017, reportée) : à la fin d'un épisode, une carte « Épisode suivant » avec « Lire maintenant » et « Annuler », et un lancement automatique après 10 s.

Ce qui existe déjà :
- **Le suivant :** la fiche d'un épisode (`emby_item_detail`, ADR 0016) donne déjà l'épisode suivant de la série. Elle passe par `AdjacentTo` et traverse les saisons.
- **Le moteur :** `emby_play` charge un nouveau fichier dans le moteur déjà ouvert, sans le fermer ; la fenêtre garde donc son plein écran.
- **Les rapports :** chaque lecture a son propre rapport de début et d'arrêt (ADR 0008).

## Décision
- **Quand :** la carte apparaît à la **fin naturelle du fichier** (`endFile` `eof`), uniquement pour un épisode dont le suivant est connu.
  - Une fin anticipée (flux coupé, ADR 0012) reste une interruption, sans carte.
  - Un film, ou le dernier épisode d'une série, se ferme comme avant.
  - La carte n'apparaît pas au début du générique : Emby ne fournit des marqueurs de générique que si la détection est activée, et ce n'est pas vérifié sur ce serveur. C'est une amélioration possible, qui demandera de vérifier les marqueurs.
- **Le suivant :** il est cherché en arrière-plan au début de chaque épisode, avec `emby_item_detail` (`episode.next`) : pas de nouvelle commande ni de requête construite par l'interface.
  - Si la recherche échoue, il n'y a pas de carte et la fin se passe comme avant ; la lecture n'est jamais gênée.
  - Une réponse arrivée après un changement d'épisode est ignorée.
- **La carte :**
  - elle remplace la barre de commandes du bas ; le titre et « Revenir » restent en haut ;
  - elle montre « Épisode suivant », le code (« S1 · É2 ») et le titre, « Lecture dans N s » avec une jauge qui se vide, puis « Lire maintenant » (focalisé) et « Annuler » ;
  - c'est un `role="dialog"` nommé « Épisode suivant » ; le décompte n'est pas annoncé à chaque seconde ;
  - avec le mouvement réduit, la jauge change par pas, sans transition.
- **Actions :**
  - « Lire maintenant », ou la fin du décompte, lance le suivant avec reprise (`emby_play(id, true)`) : un épisode commencé reprend où il s'était arrêté.
  - « Annuler », Échap ou « Revenir » ferment le lecteur et reviennent à la page d'origine, comme une fin sans suivant.
  - Le lecteur reste monté et garde son abonnement aux événements : les événements du nouveau fichier ne sont pas perdus, le plein écran et le volume restent.
  - L'enchaînement peut se répéter d'épisode en épisode. Le focus revient, à la fin, sur l'élément qui avait lancé la première lecture.
- **Échec du lancement :** la carte disparaît ; le lecteur montre le message sur son écran d'ouverture, avec « Retour » pour revenir à l'application. Une session refusée (401) est signalée à l'application, comme partout ailleurs.

## Conséquences
- Lire jusqu'à la fin marque l'épisode comme vu sur le serveur, ce qui est normal. Le scénario E2E qui le vérifie doit donc remettre l'état « non vu » de l'épisode de test, avec l'accord de l'utilisateur.
- Il n'y a pas de réglage pour désactiver l'enchaînement automatique. C'est à ajouter avec l'écran Réglages si le besoin apparaît.
