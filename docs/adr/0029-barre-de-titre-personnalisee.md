# ADR 0029 — Barre de titre personnalisée

Statut : Accepté (2026-11-07), à la demande du propriétaire.

## Contexte
La barre de titre de Windows, noire avec le nom et l'icône, rompt avec l'interface sombre et avec le lecteur plein cadre. Le propriétaire veut la cacher et la remplacer par une barre propre à l'application.

## Décision
- **`decorations: false`** sur la fenêtre principale. Tauri garde sous Windows les bords de redimensionnement et l'ombre d'une fenêtre sans décorations.
- **`TitleBar.svelte`**, fixe en haut, 32 px (`--titlebar-height`) :
  - toute la zone vide déplace la fenêtre (`data-tauri-drag-region`), et un double-clic l'agrandit ;
  - à droite, les trois boutons de Windows, dans son ordre et avec ses glyphes (Segoe Fluent Icons, ou Segoe MDL2 Assets sous Windows 10) : « Réduire », « Agrandir » ou « Niveau inférieur », « Fermer », rouge au survol. Hors de l'ordre de tabulation, comme les boutons natifs : le clavier passe par les raccourcis de Windows (Alt+F4, Win+↑/↓, Alt+Espace).
- **Hors lecture**, la barre a le fond de l'application. `#app` est décalé de sa hauteur : la fenêtre est transparente pour la vidéo, et une bande vide laisserait voir le bureau.
- **En lecture**, le lecteur place la barre dans son bandeau du haut, sans fond, sur le dégradé : elle disparaît et revient avec les commandes, et n'existe pas en plein écran.
- **« Fermer » appelle `close()`**, qui suit le même chemin que le ✕ natif : rapport d'arrêt et libération du lecteur.
- **Permissions** limitées à la fenêtre principale : `start-dragging`, `internal-toggle-maximize`, `minimize`, `toggle-maximize`, `close`, `is-maximized`.

## Alternatives
- **`titleBarStyle: Overlay`** : propre à macOS. Rejeté.
- **Commandes applicatives en Rust** pour ces actions, comme le plein écran : elles reproduiraient à l'identique des permissions de Tauri déjà limitées à une action chacune. Rejeté.
- **Garder la barre native en lecture** : elle resterait visible au-dessus de la vidéo. Rejeté.

## Conséquences
- Le menu des dispositions d'ancrage de Windows 11 (survol de « Agrandir ») n'apparaît pas sur un bouton dessiné en HTML. L'ancrage par glisser vers un bord et Win+flèches doivent rester disponibles.
- Les captures CDP montrent la barre, mais le déplacement, le double-clic, le redimensionnement et l'ancrage à la souris ne se vérifient qu'à la main.
