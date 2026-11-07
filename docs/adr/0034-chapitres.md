# ADR 0034 — Chapitres dans le lecteur

Statut : Accepté (2026-11-07).

## Contexte
`PRODUCT.md` met les chapitres en P1. Il faut savoir d'où les lire, comment les montrer et avec quelles touches.

Sources vérifiées le 2026-11-07 (`docs/evidence/chapitres-2026-11-07.md`), en lecture seule sur le serveur de test (Emby 4.10.2.0) :
- **Spécification OpenAPI d'Emby :** `ChapterInfo` a les champs `StartPositionTicks` (int64), `Name`, `ImageTag`, `MarkerType` (`Chapter`, `IntroStart`, `IntroEnd`, `CreditsStart`) et `ChapterIndex`. Les images de chapitre passent par `/Items/{Id}/Images/Chapter/{Index}`.
- **Serveur réel :**
  - `/Users/{Id}/Items/{Id}` renvoie `Chapters` sans `Fields`, sur l'élément et sur chaque `MediaSources[i]`. En revanche, la réponse de `PlaybackInfo` a `"Chapters": []`.
  - 398 films sur 400 et 397 épisodes sur 400 ont plus d'un chapitre.
  - **Aucun** chapitre n'a d'`ImageTag` : l'extraction des images de chapitre est désactivée sur le serveur. `/Images/Chapter/0` répond 404.
  - Noms dans les films : 6890 génériques (« Chapitre N », « Chapter N ») et 1743 nommés (par exemple « Opening Credits »). Dans les épisodes : 2848 génériques et 313 nommés.
  - Certains épisodes ont des chapitres exactement toutes les 300 s, nommés « Chapitre N ». Ce sont probablement des chapitres générés par Emby, mais ce n'est pas vérifié.
- **mpv :** `chapter-list` et `chapter` lisent les chapitres du conteneur. En Lecture directe, mpv en trouve autant qu'Emby : 16 et 16 sur le film 30207. Un flux HLS transcodé par Emby ne devrait pas en porter : c'est attendu, mais pas mesuré dans le lecteur.

## Décision
- **Source de vérité : les `Chapters` de l'élément Emby.**
  - Ils ne dépendent pas du mode de lecture, transcodage compris.
  - Ils sont connus avant l'ouverture du fichier.
  - Ils portent des noms.
  - Seuls les `MarkerType` `Chapter` ou absents sont gardés. Les marqueurs d'intro et de générique restent à l'ADR 0030, qui n'est pas modifiée.
  - Le nombre de chapitres vu par mpv (`chapters`) n'est exposé que dans les diagnostics, pour comparer.
- **Commande :** `emby_chapters(item_id)` renvoie des `Chapter { start, name }`, en secondes. Le nettoyage se fait en Rust (`emby/chapters.rs`) :
  - les chapitres sont triés, et ceux qui sont avant 0 ou après la fin sont retirés ;
  - un chapitre à moins de 1 s du précédent est fusionné ;
  - les noms sont nettoyés : espaces regroupés, caractères de contrôle retirés, 120 caractères au plus. Un nom vide devient « Chapitre N » ;
  - s'il reste moins de deux chapitres, la commande ne renvoie rien.
- **Interface :**
  - Des marques fines et sombres sont posées sur la barre de lecture, une par début de chapitre, sauf au début du fichier.
  - Le nom du chapitre en cours s'affiche au centre, sous la barre, et s'ajoute au `aria-valuetext` de la barre.
  - Un bouton rond « Chapitres » est placé en premier à droite, seulement quand il y a des chapitres. Il ouvre un panneau qui liste chaque chapitre avec son nom et son heure de début. La ligne en cours est marquée (`aria-current`) et prend le focus. ↑ ↓, Début et Fin passent d'une ligne à l'autre, et Entrée ou Espace va au chapitre et ferme le panneau.
  - Les chapitres arrivent en arrière-plan, comme les segments. Un échec ne montre rien, et une réponse arrivée pour une autre lecture est ignorée.
- **Touches :** Pg préc. pour le chapitre précédent et Pg suiv. pour le suivant, comme mpv. Aucune lettre n'est prise, pour ne pas gêner les raccourcis existants ou à venir.
  - Pg préc. plus de 3 s après le début d'un chapitre revient à son début. Sinon, il va au chapitre d'avant. Au premier chapitre, il revient à son début.
  - Un saut qui atterrit jusqu'à 0,5 s avant un début compte dans ce chapitre. Une deuxième pression avance donc bien.
- **Pas de vignettes :** le serveur n'en produit aucune. Les activer est un réglage du serveur, que seul le propriétaire peut changer. Les vignettes pourront être ajoutées si les `ImageTag` apparaissent.
- **Plusieurs versions :** les chapitres sont ceux de l'élément, c'est-à-dire de sa version par défaut. Les chapitres propres à chaque `MediaSource` sont reportés, car `PlaybackStart` ne porte pas encore la source choisie.

## Alternatives
- **Les chapitres de mpv (`chapter-list`) :** ils sont absents en transcodage et inconnus avant le chargement. Rejeté comme source principale.
- **Les chapitres de `PlaybackInfo` :** ils sont vides sur le serveur réel. Rejeté.
- **Des lettres comme raccourcis (par exemple `[` et `]`) :** ces touches varient selon le clavier et risquent de gêner d'autres raccourcis. Pg préc. et Pg suiv. sont libres et ce sont celles de mpv.

## Conséquences
- Une requête Emby de plus par lecture (`/Users/{Id}/Items/{Id}`), en arrière-plan.
- Les chapitres génériques toutes les 5 minutes sont montrés tels quels. Ils restent utiles pour naviguer.
- Sur un élément à plusieurs versions, les chapitres d'une version autre que celle par défaut peuvent être décalés.
- Réversible : retirer le bouton et la commande ne touche à rien d'autre.

## Validation
- Tests Rust (`emby/chapters.rs`, `emby/tests.rs`) : filtrage des marqueurs, tri, fusion, nettoyage des noms, données réelles du film 43189, chemin de requête, conversion des ticks en secondes, série sans chapitres, élément introuvable, session révoquée.
- Tests Vitest (`chapters.test.ts`, `keyboard.test.ts`) : chapitre en cours, cible de Pg préc. et Pg suiv., marques en pourcentage, touches.
- Scénario `gate3-chapters` sur le compte de test (2026-11-07), film 30207 en Lecture directe MKV : 16 chapitres chez Emby et 16 chez mpv, 15 marques, le panneau avec la ligne en cours marquée et focalisée, ↓ ↓ Entrée qui saute au chapitre 3, Pg suiv. et Pg préc., le nom affiché, Échap qui ferme le panneau, et la lecture remise à 0 sans point de reprise : tout est passé.
- Non vérifié : les chapitres pendant un transcodage dans le vrai lecteur, un élément à plusieurs versions, le rendu vidéo à l'œil (la fenêtre de test est hors écran).
