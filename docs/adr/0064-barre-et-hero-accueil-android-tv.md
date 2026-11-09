# ADR 0064 — Barre flottante et hero refondu sur l'accueil (Android TV)

Statut : Accepté (2026-11-09). Révise la mise en page de l'ADR 0059 ; le comportement du hero (titres, ◀ ▶ sur les boutons, focus au retour) reste celui de l'ADR 0059.

## Contexte
L'accueil TV affichait en haut un grand titre « Accueil » et trois boutons contour (Rechercher, Réglages, « Déconnecter claude »), puis un hero à 85 % de la hauteur avec un surtitre « Reprendre », le nom en texte, des points accompagnés de ◀ ▶ et de « 1 sur 5 ». Le propriétaire a demandé une présentation plus proche des clients vidéo de salon. Une maquette 1920×1080 a été faite et **validée par le propriétaire** le 2026-11-09 (artefact privé, décisions notées dans la session de maquette).

## Contrat Emby vérifié
Le logo d'un titre vient du même contrat que sous Windows (ADR 0015, vérifié sur le serveur) : avec `EnableImageTypes` contenant `Logo`, `/Users/{UserId}/Items/Resume` renvoie `ParentLogoItemId` / `ParentLogoImageTag` pour un épisode, et chaque élément porte `ImageTags["Logo"]`. L'image est lue par `/Items/{Id}/Images/Logo?tag=…&maxWidth=640`, comme les autres images (`EmbyClient.imageUrl`, sans jeton dans l'URL). Les requêtes « Reprendre » et « derniers ajouts » du hero demandent désormais `Primary,Backdrop,Thumb,Logo` et `Primary,Backdrop,Logo`.

## Décision
- **Barre** (`ui/HomeBar.kt`) : une pilule translucide centrée en haut, posée sur le fond du hero : Accueil, Films, Séries, Rechercher (icône), Réglages (icône), séparateur, avatar (initiale de l'utilisateur). Pas de marque d'application ni de grand titre.
  - Sans focus dans la barre, la section active (Accueil) est en blanc plein ; avec le focus, l'élément ciblé est blanc, agrandi à ×1,08 et cerclé de l'accent, la section active garde un fond translucide.
  - Films et Séries ouvrent la grille de bibliothèque (`sectionLibrary` : la bibliothèque du type nommée « Films » / « Séries », sinon la première du type ; aucune cache l'entrée).
  - « Se déconnecter » passe dans le menu de l'avatar (popup focalisable : « Connecté : … » et le bouton ; Retour le ferme).
  - Au retour d'un écran ouvert depuis la barre, le focus revient sur son entrée (`HomeMemory.focusHeader: BarItem`).
- **Hero** (`ui/HomeHero.kt`) : toute la hauteur moins 76 dp, barre comprise ; seule une bande des cartes de la première rangée dépasse en bas, sans son titre tant que le hero ou la barre a le focus.
  - Logo Emby (320 × 110 dp au plus, aligné à gauche) ; le nom en texte si le titre n'a pas de logo ou si l'image échoue.
  - Plus de surtitre « Reprendre ». Sous le résumé, une ligne de progression et « Reste 22 min » quand un point de reprise et une durée existent (`heroRemaining`, durée de ce que joue le bouton : l'épisode ou le film).
  - Boutons de 40 dp : principal large (220 dp min.) avec un triangle, « Plus d'infos » ; translucides au repos, blancs à texte noir, ×1,06 et cerclés de l'accent au focus.
  - Points seuls, l'actif en pilule, alignés à droite au niveau des boutons ; « 1 sur 5 » ne reste que dans la description d'accessibilité.
- **Focus** : ▲ depuis les boutons du hero → « Accueil » ; ◀ ▶ dans la barre ; ▼ depuis la barre → le bouton principal (« Plus d'infos » s'il n'y a rien à lire) ; ▼ depuis le hero → les rangées, la barre partant avec le hero ; ▲ depuis la première rangée → le hero, la colonne remontant en haut.
- **Sans hero** (aucun titre éligible) : la barre seule en tête de colonne ; ▼ suit la recherche de focus par défaut.

## Alternatives
- **Barre fixe au-dessus de la colonne** : toujours visible, mais elle masque le haut des rangées une fois descendu ; le propriétaire a choisi qu'elle parte avec le hero. Écarté.
- **Garder les boutons texte et le titre « Accueil »** : aucun changement de focus, mais ne répond pas à la demande. Écarté.
- **Lire la durée restante dans `UserData.PlayedPercentage`** : non demandée par les requêtes actuelles et absente des réponses déjà vérifiées ; la position et `RunTimeTicks` suffisent. Écarté.

## Conséquences
- Le bouton « Déconnecter » quitte l'écran principal : deux pressions de plus pour se déconnecter, ce qui limite aussi un appui accidentel.
- Le logo ajoute une image par titre du hero (5 au plus), mise en cache par Coil comme les autres.
- Non vérifié sur l'appareil : le repli texte (les cinq titres du compte de test ont un logo), l'accueil sans hero, une bibliothèque nommée autrement que « Films ».
