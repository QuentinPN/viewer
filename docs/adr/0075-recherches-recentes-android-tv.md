# ADR 0075 — Recherches récentes (Android TV)

Statut : Accepté (2026-11-10). Complète l'ADR 0047 (qui écartait l'historique) et garde le style de l'ADR 0067.

## Contexte
À la télécommande, saisir un titre au clavier à l'écran est lent ; on relance souvent les mêmes recherches. L'ADR 0047 n'avait pas d'historique, car `RecentlySearched` serait une écriture sur le serveur. Un historique gardé sur l'appareil n'écrit rien chez Emby.

## Contrat Emby
Aucun : l'historique reste sur l'appareil ; aucune requête, aucun paramètre ni aucune réponse ne change, et aucun appel au serveur réel n'a été fait. Relancer une recherche récente envoie la même requête `SearchTerm` que l'ADR 0047.

## Décision
- **Affichage** : tant que le champ est vide (une fois nettoyé : des espaces seuls comptent comme vide), l'écran montre « Recherches récentes » (`SectionHeader`), une rangée de pilules `GlassButton` avec la loupe et le terme, puis « Effacer l'historique » seul sur sa ligne. Sans historique, l'invitation « Saisissez au moins deux lettres… » reste telle quelle ; avec une lettre saisie aussi.
- **Contenu** : 8 recherches au plus, la plus récente d'abord ; le texte est nettoyé comme celui envoyé au serveur (`cleanSearch`), moins de 2 caractères est ignoré, et un même terme à la casse ou aux accents près (`fold` de `Search.kt`, rendu `internal`) remplace l'ancien, avec la nouvelle graphie, en tête.
- **Enregistrement** : à la touche « Rechercher » du clavier (ENTRÉE), à l'ouverture d'un résultat (le terme auquel répondent les résultats) et à la relance d'une recherche récente ; jamais à chaque lettre, pour ne pas garder « ma », « mat », « matr »…
- **OK sur une pilule** : le terme remplit le champ et la recherche part sans attendre les 300 ms. La pilule disparaît avec l'historique : le focus revient au champ et le clavier système, ouvert par ce focus, est refermé comme après la touche « Rechercher ».
- **« Effacer l'historique »** : un bouton focalisable, après la rangée ; OK efface tout, sans boîte de confirmation (l'effet est visible et limité à l'appareil). Le focus revient au champ.
- **Focus** : l'entrée sur l'écran focalise toujours le champ ; Bas mène aux pilules, encore Bas au bouton, Haut remonte. Retour est inchangé (accueil, ou résultats depuis une fiche).
- **Stockage** (`session/SearchHistoryStore.kt`) : préférences simples `search`, clé `{serveur}|{userId}.recent`, une recherche par ligne (un texte nettoyé ne contient jamais de saut de ligne). Une recherche n'est pas un secret ; le jeton n'y figure jamais. La valeur lue repasse par la même logique, donc une valeur abîmée ou plus longue est remise en forme. L'application a `allowBackup="false"` : rien ne part dans une sauvegarde.
- **Logique pure** dans `emby/SearchHistory.kt` (`addSearch`, `encodeSearches`, `decodeSearches`), testée par `SearchHistoryTest`.

## Alternatives
- **`RecentlySearched` ou autre écriture côté Emby** : partagé avec les autres clients, mais écrit sur le serveur, ce que la consigne exclut. Écarté.
- **Lignes verticales** : plus lisibles pour des termes longs, mais huit lignes de 36 dp, le titre et le bouton dépassent la hauteur d'un écran 1080p (540 dp) sous le champ. Écarté au profit d'une rangée qui défile.
- **DataStore** : une dépendance de plus pour une seule chaîne ; les autres réglages du client utilisent déjà `SharedPreferences`. Écarté.
- **Clé par utilisateur seulement**, comme les réglages : deux serveurs peuvent réutiliser le même identifiant ; le serveur est ajouté à la clé.

## Conséquences
- La déconnexion n'efface pas l'historique : il revient avec le même compte sur le même serveur.
- Un terme dont la recherche n'a rien trouvé reste dans l'historique s'il a été validé au clavier.
- Non vérifié sur le Shield : fermeture du clavier système après OK sur une pilule ou sur « Effacer », annonce TalkBack « Rechercher « … » », rendu d'une rangée de termes longs.

## Vérification
- `SearchHistoryTest` (7 tests) : ordre, nettoyage, termes trop courts, doublons à la casse et aux accents près, limite de 8, aller-retour du stockage, lecture d'une valeur abîmée.
- `testDebugUnitTest` (338 tests, 0 échec), `assembleDebug` et `lintDebug` passent, sans avertissement de lint dans les fichiers changés.
