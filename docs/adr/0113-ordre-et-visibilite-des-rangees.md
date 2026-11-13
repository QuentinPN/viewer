# ADR 0113 — Ordre et visibilité des rangées de l'accueil (desktop)

Statut : Accepté (2026-11-13). Côté Android TV : ADR 0114.

## Contexte
L'accueil desktop affiche, sous la une, « Reprendre », « À suivre », « Favoris » puis une rangée « Ajoutés récemment · <bibliothèque> » par bibliothèque. Le propriétaire veut choisir les rangées affichées et leur ordre. L'app Android TV sait déjà masquer des rangées (ADR 0094) : on reprend son modèle pour rester cohérent.

## Décision
- Clés stables, identiques à la TV : `resume`, `nextUp`, `favorites`, `latest.<libraryId>` (par identifiant : une bibliothèque renommée garde sa place et son choix). La une n'est pas une rangée. Le desktop n'a pas de rangée « Listes de lecture » (`playlists` côté TV).
- Préférences `{ order: string[], hidden: string[] }` en `localStorage`, clé `viewer.home-rows.v1:<serverId>:<userId>` — le mécanisme déjà utilisé pour les options de bibliothèque (`viewer.library-options.v1:…`). Jamais sur le serveur Emby.
- Résolution (module pur `src/lib/home/rows.ts`, testé) : les clés inconnues ou en double sont ignorées ; une rangée absente de l'ordre enregistré (nouvelle bibliothèque) est placée juste après la rangée qui la précède dans l'ordre par défaut, ou en tête si aucune ; elle est affichée par défaut. Une donnée illisible donne l'ordre par défaut.
- Une rangée masquée n'est pas demandée au serveur (ni au chargement, ni aux rafraîchissements des ADR 0109/0110). Si « Reprendre » est masquée, « À suivre » n'est plus dédoublonnée contre elle.
- Réglages › Accueil : une ligne par rangée avec un interrupteur Affichée/Masquée, glisser-déposer (HTML5) **et** boutons « Monter / Descendre » accessibles au clavier (le focus reste sur la rangée déplacée, annonce `role="status"` de la nouvelle position), « Rétablir par défaut ». Animation réduite si `prefers-reduced-motion`.
- Application : l'accueil lit les préférences à son ouverture ; comme il est démonté pendant les Réglages, le changement s'applique au retour à l'accueil.
- Tout masqué : l'accueil garde la une et affiche « Toutes les rangées sont masquées — Choisis celles à afficher dans Réglages › Accueil ».

## Alternatives
- Stocker dans le cache SQLite (Rust) : plus lourd (commande Tauri, migration) pour une donnée d'interface ; `localStorage` suffit et est déjà la norme pour ces réglages.
- Stocker dans les préférences d'affichage Emby (`DisplayPreferences`) : synchronisé entre appareils, mais modifie l'état du serveur et ses clés ne correspondent pas à nos rangées ; écarté (choix par appareil, comme la TV).
- Mise à jour en direct de l'accueil : inutile, l'accueil n'est pas visible pendant les Réglages.

## Conséquences
- Le choix est par poste : un autre PC ou la TV ont le leur.
- Ordre enregistré complet dès le premier déplacement ; les nouvelles bibliothèques s'y insèrent sans action.
- Vérifié par Vitest (`rows.test.ts`) ; le rendu et le glisser-déposer n'ont pas été vus dans l'app lancée.
