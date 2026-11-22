# Viewer — Design et UX

Règles durables pour tout nouvel écran, sur Windows et sur Android TV. Le détail d'un écran existant se lit dans son code, pas ici.

## Direction visuelle

Navigateur de médias cinématographique, dans l'esprit de l'application Apple TV : fond noir, grandes images, matériaux translucides pour ce qui flotte, focus montré par l'élévation de l'élément, mouvement calme. Identité propre à Viewer : aucun asset ni police d'Apple.

- Les images (affiches, fonds) portent l'écran ; l'interface s'efface autour.
- Un seul accent, bleu clair, réservé au focus, aux liens et aux petits repères. Jamais en grande surface.
- Pas d'esthétique de tableau de bord d'administration : pas de cartes encadrées partout, pas de dégradés décoratifs.
- Raccourcis Windows uniquement (Ctrl, Alt, Maj, Win), jamais ⌘ ni ⌥.

## Jetons

Ne jamais coder une couleur, un rayon, une durée ou une police en dur : utiliser un jeton, ou en ajouter un.

- **Windows** : variables CSS dans `src/app.css` (`:root`). Ce fichier est la référence : couleurs, matériaux, rayons, espacements, durées, ressort, ombre de focus, polices, hauteur de barre de titre. Une variable propre à un écran (comme `--gutter`, marge de page de 16 à 80 px, dans `src/lib/detail/ItemDetail.svelte`) reste locale ; si deux écrans la partagent, la remonter dans `src/app.css`.
- **Android TV** : l'objet `Palette` et le thème dans `android-tv/app/src/main/java/io/github/quentinpn/viewer/tv/ui/Theme.kt`. Il reprend les mêmes valeurs ; un changement de couleur se fait des deux côtés.

Principes :

- Fond `#000`. Surfaces pleines `#1C1C1E` et `#2C2C2E`, seulement pour les squelettes et les cartes sans image.
- Texte `#F5F5F7` ; secondaire et discret en blanc cassé translucide, contraste d'au moins 4,5:1 sur le noir.
- Bouton principal : blanc, texte noir. Bouton secondaire : verre (gris translucide).
- Matériaux (navigation, panneaux, menus) : gris translucide, flou et saturation, liseré fin. Le flou coûte du GPU : jamais sur une grande zone qui défile.
- États : succès vert, avertissement ambre, danger rouge ; toujours doublés d'un texte ou d'une icône, jamais la couleur seule.
- Rayons : 10, 14, 22 px ; pilules pour les commandes de navigation et les boutons de lecture.

## Typographie

- Windows : Segoe UI Variable Display pour les titres (interlettrage serré, interligne court), Segoe UI Variable Text pour le reste. Aucune police embarquée.
- Android TV : police système du thème Material TV (pas de police embarquée non plus).
- Une hiérarchie courte : titre, sous-titre, corps, légende. Pas de capitales espacées sauf les petits repères en accent (« Saison 2 · Épisode 14 »).
- En TV, le texte doit rester lisible à plusieurs mètres.

## Espacement et mise en page

- Échelle de 4 px (`--space-*`) ; rester sur l'échelle.
- Les rangées défilent jusqu'aux bords de la fenêtre et s'alignent sur la marge de page.
- Respecter les proportions des images (affiche 2:3, vignette 16:9, fond large) et réserver leur place avant chargement.
- Aucun débordement ni chevauchement à 100 %, 125 % et 150 % de mise à l'échelle Windows, ni dans une fenêtre de 720 px de haut. Compacter plutôt que déborder.
- Android TV : marge de sécurité d'environ 48 dp sur les côtés.

## Mouvement

- Courts et calmes : 120 ms pour les retours d'état, 200 ms pour les fondus, 320 ms avec ressort pour l'élévation du focus. Durées et courbe viennent des jetons.
- Le mouvement signale un changement d'état ; pas d'animation décorative.
- **Mouvement réduit** (Windows : `prefers-reduced-motion` ; Android TV : échelle de durée d'animation à 0) : plus de mise à l'échelle, de transition ni de balayage ; le liseré de focus et les changements d'état restent. Les jetons de durée tombent à 0 dans `src/app.css`, donc une animation écrite avec eux suit la règle sans code en plus ; toute animation à durée en dur doit avoir son propre `@media`.

## Focus, clavier et télécommande

- Tout élément interactif est atteignable et activable au clavier (Windows) ou au D-pad (TV), dans un ordre prévisible. Pas d'interaction réservée au survol.
- Focus visible partout : contour accent de 2 px (`:focus-visible` global). Sur les cartes, l'élément focalisé monte (×1,06, ombre, liseré clair) en plus du contour.
- Android TV : le focus ne se perd jamais. Chaque écran a un élément focalisé à l'ouverture ; fermer un panneau ou revenir en arrière remet le focus là où il était ; Retour remonte d'un niveau. Cibles larges, pas de saisie de texte inutile.
- Windows : Échap ferme le plus haut niveau (panneau, plein écran, page) ; Ctrl+F ou « / » mène à la recherche. Un raccourci est annoncé dans l'infobulle de son bouton.
- Conserver défilement et focus au retour arrière.
- Nom accessible sur chaque bouton-icône ; les changements dynamiques (compteurs, erreurs) sont annoncés sans l'être à chaque seconde.
- Distinguer clairement « Lecture », « Reprendre » et « Épisode suivant ».

## Chargement, vide, erreur

Tout écran qui attend le réseau prévoit les quatre états, et aucun ne bloque la navigation.

- **Chargement** : squelette de la même taille que le contenu final, pas de saut de mise en page.
- **Vide** : une phrase qui dit pourquoi c'est vide, et l'action utile s'il y en a une (« Réinitialiser les filtres »). Une zone sans contenu utile se masque plutôt que d'afficher un vide.
- **Erreur** : ce qui s'est passé en mots simples, ce que l'utilisateur peut faire, un bouton « Réessayer » ; le focus sur l'action principale. Une coupure du serveur garde la session et propose de réessayer ou de changer de serveur, sans renvoyer au formulaire de connexion.
- **Échec d'une action** (favori, par exemple) : l'interface revient à l'état précédent et le dit en une ligne discrète.
- Jamais de secret, de jeton ni d'adresse interne dans un message d'erreur.

## Textes

Français par défaut ; guillemets « » et espace avant `:` `?` `!`.

- Sobre, direct, au vouvoiement ou à l'infinitif, jamais familier. Pas de point d'exclamation, pas d'émoji.
- Verbes d'action courts pour les boutons : « Lecture », « Reprendre », « Réessayer », « Retour », « Annuler ».
- Une action destructive se confirme, avec « Annuler » focalisé par défaut et le bouton dangereux nommé précisément (« Oublier ce serveur »).
- Dire l'état réel, sans enjoliver (« Serveur injoignable », « Session expirée »). Une connexion non chiffrée est signalée.
- Dates, durées et tailles au format français (« 27,9 Go », « 1 h 41 »).

## Définition de « fini » pour un écran

Cohérent avec les jetons, utilisable sans souris, quatre états visibles, mouvement réduit respecté, aucun débordement aux tailles et échelles prises en charge, vérifié par capture ou test, pas par supposition.
