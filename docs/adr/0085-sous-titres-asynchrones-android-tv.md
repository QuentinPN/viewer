# ADR 0085 — Sous-titres externes ajoutés hors du fil de mpv (Android TV)

Statut : Accepté (2026-11-10). Corrige un défaut vu en vérifiant l'ADR 0084 ; touche l'ajout des sous-titres externes (ADR 0009, 0055, 0084).

## Contexte
`sub-add` est synchrone : mpv lit le fichier en entier avant de rendre la main. Le lecteur TV l'appelait dans `onFileLoaded`, donc sur le fil des événements de mpv. La première fois qu'un fichier est demandé, le serveur extrait les sous-titres de la vidéo, ce qui peut prendre plusieurs minutes. Sur la Shield, après un passage en transcodage d'Overgeared S1E7 : changement à 19:17:27, `quality restored` à 19:21:44. Pendant ces 4 minutes, la vidéo avançait, mais plus aucun événement n'arrivait : position figée à l'écran, rapports de progression avec l'ancienne position, pistes et réglages non rétablis.

## Décision
- **Fil à part** : `onFileLoaded` choisit d'abord les pistes et rétablit vitesse, décalages et format (ADR 0081). Il ajoute ensuite les sous-titres externes dans une coroutine sur `Dispatchers.IO`, puis choisit les pistes une seconde fois, car le choix mémorisé peut être un sous-titre externe. Le second choix relit les préférences : un choix fait entre-temps dans le menu est gardé.
- **Fichier suivant** : charger un fichier (épisode suivant, changement de qualité) annule l'ajout en cours. mpv interrompt de lui-même un `sub-add` quand la lecture du fichier finit, et la coroutine s'arrête avant le sous-titre suivant.
- **Fermeture** : `MpvPlayerEngine` protège par un verrou lecture/écriture les appels qui peuvent venir d'un autre fil (`addSubtitle`, `tracks`, `selectTrack`, `property`). `release()` envoie `stop`, qui interrompt un sous-titre en chargement, puis attend le verrou au plus 2 s. Si un appel ne rend pas la main à temps, mpv est détruit sur son propre fil dès qu'il la rend : le fil de l'interface n'attend jamais le serveur. Détruire mpv pendant qu'un autre fil l'utilise ferait planter libplayer.
- Journal : `subtitles added=N in X ms`.

## Alternatives
- **Préfixe `async` de mpv** : `sub-add` rend la main tout de suite, mais il faudrait observer `track-list` pour savoir quand choisir les pistes. Plus de code pour le même résultat. Rejeté pour l'instant.
- **Ne rien ajouter en transcodage** : les sous-titres disparaîtraient. Rejeté.

## Conséquences
- Pendant l'ajout, le sous-titre choisi peut apparaître une à deux secondes plus tard qu'avant ; l'audio et le reste ne l'attendent plus.
- Si mpv est détruit sur son fil à part et qu'une nouvelle lecture commence avant, la création du lecteur échoue (un seul mpv à la fois). Ce cas demande un sous-titre bloqué et un retour au lecteur dans la même seconde.

## Vérification
- `testDebugUnitTest` (422 tests, 0 échec), `assembleDebug` et `lintDebug` passent. Aucun test unitaire nouveau : le changement porte sur les fils et le verrou autour de libmpv, pas sur une logique pure.
- Shield, Overgeared S1E7 en japonais, passage en transcodage à 1,5 Mbit/s : `quality restored` 1,6 s après la demande, `subtitles added=3 in 811 ms` ensuite sur un autre fil, puis les pistes choisies à nouveau. Le menu montre les trois sous-titres français et « Japonais · AAC stéréo ». Les rapports de progression continuent normalement. Le rapport d'arrêt part en quittant, sans `release waits for a subtitle`. Logcat : 0 ligne avec un jeton, aucun plantage.
- Non vérifié : un vrai blocage de plusieurs minutes (les sous-titres de cet épisode sont déjà extraits côté serveur), l'arrêt d'un `sub-add` par `stop`, et la destruction de mpv sur un fil à part.
