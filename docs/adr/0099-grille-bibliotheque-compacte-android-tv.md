# ADR 0099 — Grille de bibliothèque compacte (Android TV)

Statut : Accepté (2026-11-11). Remplace la barre de pastilles des ADR 0045, 0067, 0070 (pastille « Lettre » et son panneau), 0077 (pastille « Au hasard ») et 0082 (pastille « Années ») ; garde leur logique (requêtes, pagination, saut à une lettre, persistance).

## Contexte
Capturée sur la Shield (Séries TV, 239 titres), la grille ouvrait sur une rangée de pastilles « Tri : Titre », « A → Z », « Lettre », « Visionnage : Tous », « Favoris », « Genre : Tous », « Années : Toutes », « Réinitialiser », « Au hasard » qui débordait de l'écran, puis cinq affiches d'environ 180 dp par rangée. Le propriétaire trouve l'application Emby officielle pour Android TV meilleure : « il y a trop de boutons, eux c'est plus compact ». On s'inspire de sa disposition (lettres à gauche, icônes à droite, compteur en bas) sans reprendre ses ressources graphiques.

## Décision
- **En-tête réduit** : nom de la bibliothèque (ou du studio, ADR 0087) en `titleLarge`, nombre de titres et message éventuel (« Aucun titre avec ces filtres. ») à côté. Plus de rangée de pastilles.
- **Rail de lettres à gauche** (`LetterRail`, ex-`LetterPicker`) : « # » et A–Z en colonne de 24 dp, texte 11 sp, présent seulement pour le tri par titre (ADR 0070 ; la colonne reste réservée sinon, la grille ne bouge pas). L'index `SortName` est lu dès que le rail s'affiche pour une requête, et non plus à l'ouverture du panneau : une requête de plus par ouverture de bibliothèque triée par titre, sans image, déjà jugée raisonnable par l'ADR 0070. Pendant le chargement, les lettres sont estompées ; une lettre sans titre reste estompée et sautée par le focus ; si l'index échoue, toutes les lettres redeviennent atteignables et OK relit l'index. OK sur une lettre appelle le même `jumpTo(LetterStart)` : la grille charge la page, montre et focalise le premier titre. La dernière lettre choisie est dessinée en accent. Le focus ne bascule pas sur une lettre au simple passage (OK seulement) : plus sûr qu'un délai qui sauterait pendant qu'on descend le rail.
- **Rail d'icônes à droite** (`IconRail`) : boutons ronds de 40 dp sans texte, de haut en bas Tri, Visionnage, Favoris, Filtres, Aléatoire, puis Réinitialiser seulement quand le tri, le sens ou un filtre diffère des valeurs par défaut (`canReset`, même règle que `resetOptions` : le studio d'une grille de studio n'est pas réinitialisé). Réinitialiser est en dernier, les autres icônes ne bougent jamais. Le bouton focalisé affiche à sa gauche, par-dessus la grille, une étiquette (« Tri : Titre, A → Z », « Visionnage : Non vus », « Favoris seulement : oui », « Filtres : Drame, années 1990 », « Aléatoire », « Réinitialiser ») qui sert aussi de `contentDescription`. Une icône active est dessinée en accent : filtre de visionnage, favoris, genre ou décennie posés, ou tri autre que Titre A → Z (pour expliquer la présence de Réinitialiser).
  - Tri : panneau latéral « Trier par » (Titre, Date d'ajout, Année, Note ; une autre clé prend son sens habituel, la clé courante garde le sien) et « Ordre » (A → Z / Z → A pour le titre, Croissant / Décroissant sinon).
  - Visionnage : parcourt le cycle existant (`nextWatch` ; « En cours » pour les films seulement).
  - Favoris : bascule.
  - Filtres : petit panneau « Genre : … » et « Années : … », chacun ouvre la liste latérale existante (`SidePicker`, ADR 0045, 0082).
  - Aléatoire : `pickRandom` inchangé (ADR 0077) ; au retour du titre tiré, le focus revient sur cette icône.
  - Après un panneau ou une réinitialisation, le focus revient sur l'icône d'origine (Tri après Réinitialiser, qui disparaît).
- **Icônes** : tracés vectoriels de 24 unités (`ImageVector`, comme la barre d'accueil), sans dépendance `material-icons`.
- **Grille** : `GridCells.Fixed(6)`, carte propre à la grille (`LibraryCard`, sans toucher `ItemCard` de l'accueil) : affiche 2:3, marque de coin en haut à droite (✓ ou épisodes restants, `badge`), barre de progression, titre sur une ligne en 13 sp, puis année · note communautaire en 11 sp. Focalisée : agrandie (sauf animations coupées) avec bord d'accent.
- **Gabarit à 960 × 540 dp** (1920 × 1080 à densité TV) : marges latérales de 32 dp, rail de lettres 24 dp, grille, puis 84 dp réservés au rail d'icônes (40 dp + 12 dp + marge). Avec les marges internes (14 + 10 dp) et cinq espaces de 14 dp, chaque carte fait environ 121 dp de large et 181 dp de haut. La hauteur utile (~460 dp) donne environ 17 dp par lettre.
- **Compteur** en bas à droite : « 12 | 239 » (position de la carte focalisée | total), absent tant qu'aucune carte n'a eu le focus ou que le total est inconnu.

## Chemins à la télécommande
- Grille, première colonne → Gauche → rail de lettres (la lettre la plus proche verticalement) ; Haut/Bas entre les lettres disponibles ; OK → saut, le focus passe au premier titre de la lettre ; Droite → retour à la grille (recherche géométrique de Compose).
- Grille, dernière colonne → Droite → rail d'icônes ; Haut/Bas entre les icônes ; Gauche → retour à la grille.
- Retour : ferme le panneau ouvert (focus sur l'icône d'origine), sinon quitte la grille comme avant.

## Alternatives
- **Garder les pastilles en les raccourcissant** : la rangée déborde encore avec neuf choix et mange une ligne d'affiches.
- **Saut à la lettre au focus après un délai** : plus proche d'Emby, mais descendre le rail ferait défiler la grille à chaque lettre traversée ; écarté.
- **Dépendance `material-icons-extended`** : plusieurs mégaoctets pour six icônes simples ; écartée.

## Conséquences
- L'ADR 0070 écartait un rail vertical pour des cibles d'environ 14 dp ; le gabarit compact donne environ 17 dp, ce qui reste petit : à vérifier sur la Shield.
- `CommunityRating` n'est pas demandé dans `Fields` de `libraryItems` (fichier `EmbyClient.kt` non modifié dans cette tranche) ; la note s'affiche si le serveur la renvoie par défaut, ce qui n'a pas été vérifié sur le serveur réel.
- Non vérifié sur la Shield : rendu, lisibilité à 3 m, chemins du focus entre la grille et les rails, comportement de l'étiquette par-dessus la dernière colonne.
