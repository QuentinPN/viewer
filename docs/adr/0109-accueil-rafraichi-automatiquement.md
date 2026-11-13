# ADR 0109 — Accueil relu automatiquement (desktop et Android TV)

Statut : Accepté (2026-11-13). Complète les ADR 0010, 0012 et 0097.

## Contexte
Retour du propriétaire (2026-11-13) : « lorsqu'une nouvelle série / film est ajouté, il faudrait que l'accueil se mette à jour automatiquement, j'ai dû quitter l'app et la relancer pour le voir dans "ajoutés récemment" ». L'accueil n'était relu qu'après une lecture, un changement de favori ou de marque « vu », ou le retour de la connexion. Sur la Shield, revenir du lanceur reprend l'activité telle quelle : l'accueil restait celui du premier chargement.

## Décision
- L'accueil garde l'heure de sa dernière lecture complète (`loadedAt`).
- **Affiché et au premier plan** : toutes les 60 s, si la lecture date de 5 min ou plus, toutes les rangées sont relues **en place** (les cartes restent jusqu'à la réponse, le focus reste sur la même carte par id).
- **Retour dans l'app** (desktop : `focus` de la fenêtre ou `visibilitychange` visible ; Android TV : passage à `STARTED` après un arrêt) : relu si la lecture date de 30 s ou plus.
- Rien n'est demandé quand la fenêtre est cachée ou l'activité arrêtée.
- Desktop : `src/lib/home/autoRefresh.ts`, branché dans `Home.svelte` sur le même chemin que l'après-lecture. Android TV : `ui/HomeRefresh.kt`, branché dans `HomeScreen` via `repeatOnLifecycle(STARTED)` et `HomeMemory.userDataChanged()`.

## Alternatives
- **WebSocket Emby (`LibraryChanged`)** : immédiat, mais une connexion persistante de plus à maintenir (reconnexion, authentification, veille) sur les deux apps, pour un besoin où quelques minutes de retard suffisent. Écarté pour cette tranche, puis retenu à la demande du propriétaire (ADR 0110) ; la relecture périodique reste en secours.
- **Bouton « Actualiser »** : demande une action, ce que le retour veut éviter. Écarté.
- **Relire seulement « Ajoutés récemment »** : « Reprendre » et « À suivre » changent aussi depuis d'autres appareils ; la relecture complète coûte une dizaine de petites requêtes toutes les 5 min. Écarté.

## Conséquences
- Un titre ajouté apparaît au plus 5 min après (plus le délai de scan d'Emby), ou dès le retour dans l'app.
- Une dizaine de requêtes toutes les 5 min tant que l'accueil reste affiché au premier plan.
- Une relecture qui échoue garde les rangées affichées ; elle est retentée à la vérification suivante.

## Vérification
Voir ROADMAP.md (entrée ADR 0109).
