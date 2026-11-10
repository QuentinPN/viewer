# ADR 0067 — Recherche et grille de bibliothèque au style de l'accueil (Android TV)

Statut : Accepté (2026-11-10). Complète l'ADR 0064 ; ne change ni le comportement ni l'ordre du focus des ADR de la recherche et de la grille.

## Contexte
Depuis l'ADR 0064, l'accueil TV utilise des contrôles translucides : verre au repos, blancs à texte noir, agrandis et cerclés de l'accent au focus, en pilule ou à coins de 8 dp, sur la marge de 48 dp. La recherche et la grille de bibliothèque gardaient les boutons contour et pleins par défaut de tv-material3 (tri, ordre, filtres, « Réinitialiser ») et un champ de recherche rectangulaire gris bordé d'accent. Passer de l'accueil à « Films », « Séries » ou à la recherche changeait donc de langage visuel.

## Contrat Emby
Aucun : le lot ne touche que l'apparence ; aucune requête, aucun paramètre ni aucune réponse ne change, et aucun appel au serveur réel n'a été fait.

## Décision
- **Composant partagé** (`ui/Controls.kt`) :
  - `GlassButton` : `Surface` tv-material3 cliquable, pilule par défaut, 36 dp de haut ; verre `Glass` (`rgb(120 120 128 / 0.36)`, la teinte des boutons du hero) au repos, blanc à texte noir quand `selected`, blanc ×1,06 et anneau d'accent de 2 dp à 3 dp du bord (`RING_GAP`) au focus.
  - `reducedMotion()` : le réglage système « supprimer les animations » (`ANIMATOR_DURATION_SCALE` à 0, comme le hero) ; le contrôle focalisé garde alors sa taille, l'anneau reste (DESIGN.md).
  - `SectionHeader` : titre d'une rangée au style des rangées de l'accueil (`titleLarge`), avec son nombre à côté en gris secondaire.
- **Grille de bibliothèque** : « Tri », l'ordre, « Visionnage », « Favoris », « Genre » et « Réinitialiser » deviennent des pilules `GlassButton` en 14 sp ; un filtre qui restreint la grille est blanc à texte noir (DESIGN.md : « Favoris » enfoncé blanc). Un filtre est désormais **un seul** nœud dans les deux états : le focus y reste sans la reprise manuelle qu'imposait l'échange `Button` / `OutlinedButton`. Il annonce son état (`selected`) aux services d'accessibilité. « Réinitialiser » reste toujours affiché, à la même place, pour ne pas changer l'ordre du focus.
- **Recherche** : le champ devient une pilule de verre avec l'icône loupe de la barre (`SearchIcon`, rendu `internal`) ; à la saisie, blanc à texte et curseur noirs, cerclé de l'accent. L'anneau et son écart sont réservés autour de la pilule, qui commence sur la marge de 48 dp : rien ne bouge au focus. Les titres « Films », « Séries », « Épisodes » passent par `SectionHeader` (« Films 3 » au lieu de « Films · 3 »).
- **Accueil inchangé** : `BarEntry` (états actif / barre focalisée, ×1,08, 28 dp) et `HeroAction` (40 dp, triangle, largeur minimale) ne passent pas par `GlassButton`. `HeroAction` en serait proche, mais `GlassButton` supprime l'agrandissement sans animations, ce que le hero ne fait pas : la refonte ne serait pas identique. Seule la visibilité de `SearchIcon` change dans `HomeBar.kt`.
- Inchangés : textes français, pagination, tri et filtres (`ItemsOptions`), liste des genres, ordre et reprise du focus, messages de chargement, d'erreur et « Réessayer ».

## Alternatives
- **Garder les boutons tv-material3 en les recolorant** (`ButtonDefaults.colors`) : moins de code, mais `Button` et `OutlinedButton` restent deux composants à échanger, avec la reprise de focus, et leur forme et leur anneau diffèrent de l'accueil. Écarté.
- **Faire passer la barre et le hero par `GlassButton`** : un seul composant partout, mais il faudrait des paramètres pour l'état actif de la barre, l'échelle ×1,08 et l'absence de réduction de mouvement du hero ; le rendu de l'accueil validé changerait sous « supprimer les animations ». Écarté pour ce lot.
- **Masquer « Réinitialiser » sans filtre actif** (comme sous Windows) : change l'ordre du focus de la barre d'outils. Écarté ; à décider à part.

## Conséquences
- La barre d'outils et le champ ressemblent à la barre de l'accueil ; un filtre actif se lit en blanc même sans focus.
- Le focus focalisé d'une pilule blanche active se distingue par l'agrandissement et l'anneau, ou par l'anneau seul sans animations.
- Non vérifié sur le Shield : rendu à 1080p (taille des pilules, anneau non rogné par le défilement horizontal de la barre d'outils), lisibilité du champ blanc avec le clavier système ouvert, contraste du texte indicatif, annonce TalkBack de l'état des filtres.

## Vérification
- Aucun calcul pur nouveau : pas de nouveau test unitaire. Les tests existants (`LibraryOptionsTest`, `LibraryPagingTest`, `SearchGroupsTest`, `HomeBarTest`…) passent sans modification.
- `testDebugUnitTest` (218 tests, 0 échec), `assembleDebug` et `lintDebug` passent, sans avertissement de lint dans les fichiers changés.
