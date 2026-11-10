# ADR 0073 — Heure, heure de fin et minuterie dans le lecteur (Android TV)

Statut : Accepté (2026-11-10). S'ajoute aux commandes du lecteur et au titre « Lecture » du menu ▼ (ADR 0072) ; « Informations de lecture » (ADR 0061) reste la dernière ligne. Utilise les segments de générique (ADR 0030, 0048) et la carte « Épisode suivant » (ADR 0043).

## Contexte
Deux demandes du propriétaire pour la TV : savoir, sans quitter le film, quelle heure il est et à quelle heure il se termine ; et s'endormir devant une série sans qu'elle enchaîne les épisodes toute la nuit.

Le lecteur a déjà ce qu'il faut : la position, arrivée environ une fois par seconde pendant la lecture (`PlayerListener.onPosition`, rien en pause), la durée, la vitesse relue de mpv (ADR 0072), les segments de générique connus de l'élément (`SkipPlan`), et un seul chemin de pause, `PlayerEngine.togglePause`, que le suivi de lecture rapporte au serveur (ADR 0040).

## Décision
- **Heure et heure de fin** dans les commandes, visibles quand elles le sont :
  - l'heure courante, `HH:mm` sur 24 h, fuseau de l'appareil (`ZoneId.systemDefault()`), à droite du titre, même style (`headlineSmall`, `Palette.text`) ;
  - « Fin à HH:mm » à droite du temps restant : maintenant + (durée − position) ÷ vitesse, la vitesse ramenée à la vitesse proposée la plus proche (ADR 0072) ; à 2×, 40 min restantes finissent dans 20 min. Masqué quand la durée est inconnue (direct, ou avant que mpv la donne).
  - **Rafraîchissement** : `PlayerUi.now` est mis à jour à chaque position (déjà une recomposition par seconde) ; tant que les commandes sont affichées, une attente jusqu'au début de la minute suivante le met à jour en pause, où aucune position n'arrive. Pas de boucle supplémentaire : un réveil par minute, et seulement commandes visibles.
- **Minuterie** : ligne « Minuterie » sous « Mode nuit », titre « Lecture ». Valeurs : Désactivée, 15, 30, 45, 60, 90 min, puis « Fin de l'épisode » pour un épisode, « Fin du film » pour un film ; rien d'autre pour les autres types. ◀ ▶ avancent ou reculent, bloqués aux deux bouts comme « Vitesse » ; OK passe à la valeur suivante et revient à Désactivée après la dernière. Choisir une durée la fait partir de maintenant ; rester sur la même valeur à un bout garde l'échéance.
  - **Pastille** à côté de celle de la vitesse, tant que la minuterie est réglée : « Arrêt dans 12 min » (minutes restantes arrondies au-dessus), ou « Arrêt : fin de l'épisode » / « Arrêt : fin du film ».
  - **Temps compté** : horloge murale (`System.currentTimeMillis()`), **pause comprise**, comme la minuterie d'un téléviseur : elle sert à s'arrêter à une heure, pas après tant de minutes vues. Une durée écoulée pendant une pause s'éteint simplement à la reprise, sans remettre en pause aussitôt. Un changement d'heure du système (NTP) décale l'échéance d'autant ; accepté.
  - **À l'échéance** (vérifiée à chaque position) : pause par `togglePause`, rapportée au serveur comme toute pause ; la carte « Épisode suivant » et le bouton de saut disparaissent, les menus se ferment, et « Minuterie écoulée : lecture en pause » s'affiche au centre jusqu'à la reprise. La minuterie revient à Désactivée. Si elle s'écoule pendant le compte à rebours de la carte (aucune position n'arrive alors), le lancement de l'épisode suivant est annulé de la même façon.
  - **« Fin de l'épisode / du film »** : pause au début du premier générique connu après la position où elle a été réglée (réglée pendant le générique, c'est la fin du fichier qui compte) ; sans générique connu, une seconde avant la fin (les positions arrivent environ chaque seconde). Tant qu'elle est réglée, `SkipPlan` reçoit « pas d'épisode suivant » : la carte n'apparaît ni au générique ni à la fin, le générique garde son bouton « Passer le générique ». Si la fin du fichier arrive sans position qui l'ait déclenchée, l'échéance se fait à `onEndReached` : le lecteur reste ouvert, sans épisode suivant ni fermeture, et Retour le quitte.
  - **Portée** : la session du lecteur, rien n'est enregistré. Une durée continue d'un épisode au suivant (`PlayerUi.reset()` ne la remet pas à zéro) ; « Fin de l'épisode » est retirée au changement de fichier.
- **Frontière** : l'interface passe uniquement par `PlayerEngine.togglePause` ; aucune propriété mpv nouvelle.
- **Logique pure**, heures passées en paramètre : `player/EndTime.kt` (heure, heure de fin, attente jusqu'à la minute suivante), testé par `EndTimeTest` ; `player/SleepTimer.kt` (valeurs offertes, pas, échéance, arrêt au générique, minutes restantes, libellés français), testé par `SleepTimerTest`.

## Alternatives
- **Ne compter que le temps de lecture** : « 30 min de film » plutôt que « dans 30 min ». Moins prévisible au moment de s'endormir, et une pause oubliée repousserait l'arrêt indéfiniment. Rejeté.
- **Arrêter (fermer le lecteur) plutôt que mettre en pause** : la pause garde la position à l'écran et la rapporte au serveur ; l'économiseur ou la mise en veille du Shield prennent ensuite le relais. Rejeté pour l'instant.
- **Minuterie mémorisée dans Réglages** : un choix du soir, pas une préférence ; la retrouver le lendemain surprendrait. Rejeté.
- **Ticker dédié chaque seconde** : inutile, la position arrive déjà au même rythme pendant la lecture. Rejeté.
- **Heure de fin à vitesse 1 seulement** : fausse dès que la vitesse change. Rejeté.

## Conséquences
- Les commandes gagnent l'heure, « Fin à » et une pastille ; le menu ▼ gagne une ligne ; l'aide des commandes cite la minuterie.
- La carte « Épisode suivant » n'apparaît plus tant que « Fin de l'épisode » est réglée ; après une échéance, reprendre la lecture rend le comportement normal (carte au générique comprise).
- Réversible : retirer la ligne, la pastille et les deux fichiers ne touche à rien d'autre ; rien n'est stocké.

## Vérification
- Tests unitaires `EndTimeTest` (heure sur 24 h dans trois fuseaux, zéro initial, passage de minuit, vitesses 0,75× et 2×, vitesse illisible, durée nulle, négative, NaN, infinie, position au-delà de la fin, attente jusqu'à la minute suivante jamais nulle) et `SleepTimerTest` (valeurs offertes selon le type, pas bloqués et OK qui boucle, valeur plus offerte, échéance murale indépendante de la position, minutes arrondies au-dessus, arrêt au générique, à la fin sans générique, réglage pendant le générique, durée inconnue, libellés et pastilles). `testDebugUnitTest` (327 tests), `assembleDebug` et `lintDebug` passent, sans avertissement lint dans les fichiers modifiés.
- **NON VÉRIFIÉ sur le Shield** :
  - la lisibilité de l'heure et de « Fin à » sur le téléviseur, et la mise en page avec un titre long ou les deux pastilles ;
  - le fuseau et le format 24 h du Shield tels que les donne `ZoneId.systemDefault()` ;
  - l'échéance réelle (pause, rapport de progression, message), la suppression de la carte au générique, et l'échéance à `onEndReached` (pause de mpv en `keep-open` à la fin) ;
  - le comportement des touches et du focus sur la ligne « Minuterie ».
