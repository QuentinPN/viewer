# ADR 0108 — OK après une recherche, et mémoire tampon sur Android TV

Statut : Accepté (2026-11-13). Complète les ADR 0048, 0089 et 0090.

## Contexte
Deux retours du propriétaire sur la Shield (2026-11-12) :
1. « le bouton "passer l'intro" sur TV est déclenché dès que j'appuie sur OK, donc juste si je scroll +10 +10 +10 pour reprendre ou -10 -10 -10 OK ». ◀ ▶ affichent les commandes, donc le bouton (ADR 0089), et OK passait le segment dès que le bouton était visible.
2. En lecture directe, la mémoire tampon tombait souvent à 0 s en début d'épisode : micro-coupures, puis lecture stable vers le milieu. Le mini-PC Emby et la Shield sont tous deux en Wi-Fi. mpv tournait avec `demuxer-max-bytes` à 64 Mio (moins que les 150 Mio par défaut de mpv, soit environ 8 s d'un fichier à 60 Mb/s) et reprenait la lecture dès 1 s en tampon (`cache-pause-wait` par défaut) : sur un réseau à peine plus rapide que le débit du fichier, une coupure par seconde de marge.

## Décision
- **Bouton armé / désarmé** (`SkipArm` dans `player/Skip.kt`). Armé quand il apparaît en lecture normale, commandes masquées ; quand OK le fait revenir (ADR 0089) ; quand les commandes disparaissent. **Désarmé** par toute recherche ◀ ▶, et quand il apparaît pendant une recherche (◀ qui revient dans l'intro). Armé, OK passe le segment et le bouton a le focus ; désarmé, OK met en lecture ou en pause et le bouton reste visible sans le focus.
- **Tampon** : `demuxer-max-bytes` 150 Mio (défaut de mpv ; la Shield a 3 Go), `cache=yes`, `cache-pause-initial=yes` et `cache-pause-wait=4` : quelques secondes chargées avant la première image et après une coupure. Une attente courte remplace une suite de micro-coupures.

## Alternatives
- **OK ne passe jamais, seul le bouton focalisé passe** : les touches passent par l'activité, pas par le focus Compose ; même effet que l'état armé, plus de code. Écarté.
- **Tampon beaucoup plus grand** (500 Mio+) : n'aide pas quand le réseau est plus lent que le fichier, et prend la mémoire de la Shield. Écarté.
- **Indicateur « Mise en mémoire tampon… »** : utile, hors de cette tranche.

## Conséquences
- Après une recherche, il faut attendre que les commandes disparaissent (5 s) pour que OK passe l'intro, ou la laisser passer par le bouton revenu.
- Le démarrage d'une lecture réseau attend quelques secondes de plus sur un réseau lent ; sur un réseau rapide, le tampon se remplit en moins d'une seconde.
- **Le réseau reste la cause probable** : deux liaisons Wi-Fi (serveur et Shield) passent deux fois par le point d'accès et partagent son débit. Brancher le mini-PC en Ethernet est la vraie correction ; ces réglages n'en font que réduire l'effet.

## Vérification
- `testDebugUnitTest` (`SkipTest`, 1 nouveau cas, 15/15), `assembleDebug`, `lintDebug` : réussis.
- **Sur la Shield (2026-11-13, build debug 1.0.2, Overgeared S1É7, lecture directe 1080p H.264, intro 40–128 s)** :
  - 16 × ◀ depuis 4:22 jusqu'à 1:58 : « Passer l'intro » affiché sans le focus ; OK met en pause, aucun `skip` dans les journaux.
  - Intro atteinte en lecture normale : bouton focalisé ; OK dans ses 10 s → `skip to 128.0`.
  - OK après les 10 s : le bouton revient focalisé avec les commandes, sans pause (ADR 0089 inchangé).
  - Tampon : 140 à 146 s d'avance pour un débit mesuré de 4 à 10 Mb/s, aucune image perdue.
- **Réseau mesuré** : Shield en Wi-Fi 2,4 GHz (canal 11, lien 78 Mb/s, RSSI −48 dBm). 20 pings vers le serveur : 3 à 143 ms (moyenne 42 ms), sans perte. Les coupures de la veille n'ont pas été reproduites ; leur cause reste le débit Wi-Fi.
