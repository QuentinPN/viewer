# ADR 0090 — Avance accélérée sur Android TV

Statut : Accepté (2026-11-10). Android TV seulement ; complète le déplacement par ◀ ▶ du lecteur.

## Contexte
Sur la TV, chaque appui sur ◀ ▶ (ou sur les touches retour rapide / avance rapide) déplace la lecture de 10 s. Une touche maintenue répète des pas de 10 s : Android répète la touche environ 20 fois par seconde après un délai d'environ 0,4 s, soit une vingtaine de commandes `seek` envoyées à mpv chaque seconde, sans que le pas change. Traverser un film demande alors soit de longues pressions, soit beaucoup d'appuis.

## Décision
- **Logique pure** dans `android-tv/…/player/SeekSteps.kt` : `seekStep(repeatCount)` et `seeksOnRepeat(repeatCount)`, à partir de `KeyEvent.repeatCount`.
- **Pas croissant :** 10 s pour un appui et pendant la première seconde de répétitions (répétitions 0 à 19), 30 s ensuite jusqu'à environ 3 s de maintien (répétitions 20 à 59), puis 60 s.
- **Limitation des `seek` :** un maintien n'envoie un `seek` qu'une répétition sur quatre (`SEEK_REPEAT_EVERY = 4`), soit environ 5 par seconde au lieu de 20. L'appui lui-même (répétition 0) déplace toujours. Les répétitions ignorées sont consommées, sans effet.
- **Vitesses obtenues** (à 20 répétitions par seconde) : environ 50 s de film par seconde au début, 150 s ensuite, 300 s après 3 s de maintien. Un nouvel appui repart à 10 s, puisque son `repeatCount` revient à 0.
- **Affichage inchangé :** chaque `seek` envoyé garde le comportement existant (position affichée tout de suite, vignette d'aperçu si le serveur en a, commandes affichées).
- **Pas d'indication du pas à l'écran** (« ▶▶ 30 s ») : la barre et la position montrent déjà l'avance ; l'ajout demanderait un nouvel état dans l'overlay pour peu de gain.

## Alternatives
- **Accumuler la cible et n'envoyer qu'un `seek` au relâchement** : la position et l'aperçu bougeraient sans que l'image suive pendant le maintien, et le `seek` relatif de mpv devrait devenir absolu. Plus complexe ; écarté.
- **Garder un `seek` par répétition avec un pas croissant** : 20 commandes par seconde, de 60 s chacune, sans gain de lisibilité. Écarté.
- **Seuils au temps de maintien plutôt qu'au nombre de répétitions** : équivalent à cadence fixe, et `repeatCount` est fourni par Android sans horloge à gérer. Écarté.

## Conséquences
- Maintenir ◀ ▶ au début est un peu moins rapide qu'avant (50 s par seconde contre environ 200), mais s'accélère au-delà au bout d'une seconde ; les appuis répétés gardent leur pas de 10 s.
- Les seuils dépendent de la cadence de répétition du clavier ou de la télécommande ; une télécommande qui répète plus lentement accélère plus tard.

## Vérification
- Android TV : `testDebugUnitTest` (`SeekStepsTest`, 3 nouveaux cas), `assembleDebug`, `lintDebug`.
- **Non vérifié sur appareil :** la vérification sur la Shield TV est à faire par l'orchestrateur. À vérifier : un appui déplace de 10 s, un maintien accélère à 30 s puis 60 s, un nouvel appui repart à 10 s, retour rapide symétrique, aperçu et position suivent pendant le maintien.
