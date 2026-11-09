# ADR 0057 — Chapitres dans le lecteur (Android TV)

Statut : Accepté (2026-11-09). Reprend sur TV la liste « Chapitres » de Windows (ADR 0034) ; les marqueurs d'intro et de générique restent à l'ADR 0048.

## Contexte
Le lecteur Windows liste les chapitres d'un film ou d'un épisode et y saute (ADR 0034). Sur TV, il n'y avait aucun moyen d'aller à un chapitre : seulement des sauts de 10 s.

Le lecteur TV lit déjà l'élément complet (`/Users/{Id}/Items/{Id}` sans `Fields`) pour le titre et les segments ; la réponse porte les `Chapters` (`StartPositionTicks`, `Name`, `MarkerType`), vérifiés sur le serveur de test le 2026-11-07 (`docs/evidence/chapitres-2026-11-07.md`). Aucun chapitre n'a d'image : `/Images/Chapter/0` répond 404.

Touches du lecteur : OK lecture / pause, ◀ ▶ 10 s, ▼ (et Menu) le menu audio et sous-titres. ▲ ne faisait qu'afficher les commandes.

## Décision
- **Source : les `Chapters` de l'élément**, déjà reçus par `describe`. Aucune requête nouvelle.
- **Mêmes règles que Windows** (`src-tauri/src/emby/chapters.rs`), portées dans `player/Chapters.kt` :
  - seuls les `MarkerType` `Chapter` ou absents sont gardés ; `IntroStart`, `IntroEnd` et `CreditsStart` restent aux segments ;
  - les chapitres sont triés ; ceux avant 0 ou après la fin (`RunTimeTicks`) sont retirés ; un chapitre à moins de 1 s du précédent est écarté ;
  - les noms sont nettoyés (espaces regroupés, caractères de contrôle retirés, 120 caractères au plus) ; un nom vide devient « Chapitre N » ;
  - moins de deux chapitres : aucun, et la liste n'est pas proposée.
- **Chapitre en cours** : le dernier dont le début est au plus 0,5 s après la position, comme `chapterIndex` de Windows.
- **Touche ▲** : ouvre la liste quand il y a des chapitres ; sinon, comme avant, affiche seulement les commandes. L'aide des commandes ajoute « ▲ chapitres » quand la liste existe.
- **Liste** : panneau à droite, comme le menu des pistes, titré « Chapitres ». Une ligne par chapitre : son nom sur une ligne et son heure de début (`m:ss` ou `h:mm:ss`, comme `formatTime`). La ligne en cours est marquée et prend le focus ; la liste s'ouvre défilée jusqu'à elle. OK va au début du chapitre et ferme la liste ; Retour la ferme. Les commandes restent affichées tant qu'elle est ouverte.
- **Menus** : `PlayerUi.menuOpen` et `closeMenus()` couvrent les deux menus. Retour, la carte « Épisode suivant », le saut automatique et le focus du bouton « Passer » les traitent de la même façon.
- **Pas de vignettes** : le serveur n'en produit pas.

## Alternatives
- **Une entrée « Chapitres » dans le menu ▼** : un menu de plus à traverser, et un mélange pistes / navigation. Rejeté.
- **Menu (≡) ou une touche média** : Menu ouvre déjà les pistes, et beaucoup de télécommandes n'ont pas de touche chapitre. Rejeté.
- **▲ / ▼ pour chapitre précédent / suivant, comme Pg préc. / Pg suiv. sous Windows** : ▼ est déjà pris par les pistes, et un saut sans voir les noms est peu utile. Des sauts directs pourront s'ajouter à la liste si le besoin apparaît.
- **Marques de chapitre sur la barre de progression, comme Windows** : reporté ; la barre TV est fine (6 dp) et l'objet de ce lot est la navigation.
- **Les chapitres de mpv (`chapter-list`)** : absents en transcodage. Rejeté, comme sous Windows.

## Conséquences
- ▲ change de rôle sur les éléments chaptrés : il ouvre la liste au lieu d'afficher seulement les commandes (qu'il affiche aussi).
- Sur un élément à plusieurs versions, les chapitres sont ceux de la version par défaut, comme sous Windows.
- Réversible : retirer la touche et le panneau ne touche à rien d'autre.

## Vérification
- Tests unitaires `ChaptersTest` : vraie réponse du film 43189, filtrage des marqueurs, tri, nommage « Chapitre N », retrait des chapitres hors fichier et trop proches, moins de deux chapitres, coupe des noms longs, chapitre en cours (dont l'atterrissage 0,5 s avant un début), format des heures. `testDebugUnitTest`, `assembleDebug` et `lintDebug` passent, sans avertissement lint dans les fichiers modifiés.
- Non vérifié sur le Shield : l'ouverture par ▲, le focus et le défilement sur la ligne en cours (film 30207, 16 chapitres), le saut par OK, la fermeture par Retour, l'aide « ▲ chapitres », l'absence de liste sur un élément sans chapitres, et un transcodage.
