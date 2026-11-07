# ADR 0031 — Direction visuelle inspirée de l'Apple TV

Statut : Accepté (2026-11-07), à la demande du propriétaire.

## Contexte
DESIGN.md s'inspirait d'Infuse : fond bleu nuit, barre latérale, accent bleu sur les boutons principaux. Le propriétaire préfère l'esprit de l'application Apple TV. Il a validé sept maquettes (accueil, bibliothèque, film, série, lecteur, connexion, raccourcis), avec une consigne : les raccourcis restent ceux de Windows (Ctrl, Alt, Maj, Win), jamais ⌘ ni ⌥.

## Décision
- **Fond noir** `#000`, textes `#F5F5F7` et blanc cassé translucide (`rgb(235 235 245 / 0.7)`, `0.55` pour le texte discret, ce qui garde un contraste d'au moins 4,5:1 sur le noir).
- **Matériaux translucides** pour ce qui flotte (navigation, panneaux, boutons secondaires) : gris translucide et `backdrop-filter: blur(20px) saturate(180%)`. Les surfaces pleines restent pour les squelettes et les cartes sans image.
- **Boutons principaux blancs à texte noir**, boutons secondaires en verre (`rgb(120 120 128 / 0.36)`). L'accent bleu du logo (`#8FD3F5`) ne sert plus qu'au focus visible, aux liens et aux petits repères.
- **Navigation en pilule flottante** en haut et au centre (Recherche, Accueil, une entrée par bibliothèque, Réglages, compte), à la place de la barre latérale. L'élément courant est une pilule blanche à texte noir.
- **Focus par élévation** : la carte focalisée grandit (×1,06), prend une ombre et un liseré clair, avec un ressort court. Le contour du focus reste en plus pour le clavier. Avec « réduire les animations », pas d'agrandissement ni de transition, seulement le liseré.
- **Typographie** : Segoe UI Variable (Display pour les titres, Text pour le reste), titres serrés (`-0.02em`), aucune police embarquée.
- **Rayons** plus généreux : 10, 14 et 22 px, et des pilules pour les commandes de navigation.
- **Mise en œuvre par écran**, un commit par écran : jetons, puis la coque et l'accueil, la bibliothèque, les fiches, le lecteur, la connexion.

## Alternatives
- **Garder la direction Infuse** : contraire à la demande. Rejeté.
- **Reproduire l'Apple TV à l'identique** (icônes SF Symbols, police SF Pro) : licences d'Apple, et la police n'est pas faite pour Windows. Rejeté, on garde l'esprit, pas les éléments.
- **Barre latérale translucide** : plus proche de l'existant mais moins fidèle aux maquettes. Rejeté.

## Conséquences
- Les sélecteurs des scénarios CDP sont gardés (`nav[aria-label="Bibliothèques"]`, `ul.libraries button`, `button.home-link`, `#search-field`). Les actions du compte passent dans un menu, que les scénarios ouvrent avant de cliquer.
- `backdrop-filter` coûte du GPU sur de grandes surfaces. Il reste limité aux éléments flottants.
- Les écrans pas encore refaits gardent leur mise en page avec les nouveaux jetons pendant la transition.
