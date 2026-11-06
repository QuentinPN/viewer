# ADR 0023 — Repli sur le transcodage quand la source directe ne s'ouvre pas

Statut : Accepté (2026-11-06). Complète les ADR 0007 et 0009.

## Contexte
Le profil de l'appareil déclare ce que libmpv sait lire « en général » (ADR 0007). Le serveur peut donc proposer le Direct Play d'un fichier que mpv n'arrive pas à ouvrir : conteneur abîmé, variante non gérée. Jusqu'ici, l'utilisateur restait devant `Lecture impossible : <message anglais de mpv>`, sans autre issue que de revenir en arrière, alors que le serveur aurait pu transcoder.

Un second défaut masquait ce cas : `PlayerView` passe `active` à vrai dès que le moteur répond à ses diagnostics, même si rien n'est chargé. Un échec d'ouverture pouvait donc être pris pour une interruption, et « Reprendre » rouvrait la même source.

## Décision
**Un seul essai, décidé par le lecteur.** Si la lecture se termine en erreur alors qu'aucune position n'a été vue et que la source n'était pas déjà un transcodage, `PlayerView` demande le même élément en transcodage (`emby_play` avec `transcodeFallback`). Une position vue signifie que le fichier a vraiment été lu : l'échec suit alors le chemin d'interruption de l'ADR 0012. `FileLoaded` ne suffit pas, il arrive avant tout décodage.

**Le serveur reste maître de la décision.** Le repli envoie `EnableDirectPlay` et `EnableDirectStream` à `false` dans le corps de `PlaybackInfo`. Si le serveur propose encore le fichier tel quel, le résultat est `NoCompatibleStream` : pas de boucle sur la même source, et aucune URL construite par l'application.

**Le choix de l'utilisateur est conservé.** `PlaybackStart.resume` est relu au moment du repli : « Reprendre » repart de la position du serveur, « Lire depuis le début » de 0. Le point de reprise n'est pas touché par l'échec, puisque le suivi n'envoie rien pour un fichier jamais chargé (`Pending` → `Idle`, ADR 0008).

**Ce que voit l'utilisateur.**
- Pendant le repli : « Lecture directe impossible, passage au transcodage… ».
- Une fois lancé : le badge « Transcodage · échec de la lecture directe ». Le serveur ne connaît que le refus, la vraie raison vient du lecteur.
- Si le transcodage échoue aussi : « Le lecteur n'a pas pu ouvrir ce fichier. », suivi du texte de mpv annoncé comme « Détail technique », et « Retour ».

## Alternatives
- **Prédire les formats refusés par le profil** : le serveur transcoderait d'emblée, mais les échecs dépendent du fichier et de la build de libmpv, pas d'une liste de codecs. Rejeté (ADR 0007).
- **Demander à l'utilisateur avant de transcoder** : un clic de plus pour une seule réponse raisonnable. Le badge dit ce qui s'est passé. Rejeté.
- **Passer par le paramètre `MaxStreamingBitrate`** : ne force rien pour un fichier de faible débit. Rejeté.

## Conséquences
- Un démarrage qui échoue sur une coupure réseau ou un jeton révoqué tente aussi le repli. `PlaybackInfo` échoue alors : l'erreur est transmise à la surveillance de connexion (ADR 0012) et le lecteur l'affiche avec « Retour ». Lu dans le code, aucun scénario ne l'exerce.
- Un fichier chargé mais figé sans aucune position (limite de 30 s de l'ADR 0022) tente aussi le repli, ce qui est le comportement voulu. Lu dans le code, aucun scénario ne l'exerce.
- **Non couvert :** un fichier dont seule la vidéo est illisible. mpv écarte la piste et continue avec le son : aucune erreur, donc aucun repli. Il faudrait comparer les pistes annoncées à ce qui est décodé.

## Validation
Tests contre le serveur factice (corps de la requête, raison affichée, pas de boucle), tests Vitest de la règle, scénario `gate4-fallback` sur le serveur réel. Le relais local répond par des octets illisibles aux demandes du fichier d'origine : c'est un échec d'ouverture simulé, pas un vrai fichier incompatible. Résultats : `docs/evidence/gate4-repli-2026-11-06.md`.
