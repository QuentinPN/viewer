# ADR 0041 — Contrôles du lecteur Android TV

Statut : Accepté (2026-11-09).

## Contexte
Le lecteur Android TV (ADR 0038, 0040) n'affichait rien pendant la lecture : ni temps, ni progression, ni titre. Haut et bas faisaient défiler les pistes à l'aveugle (`cycle sub` / `cycle audio`), sans voir laquelle était choisie.

## Décision
- Une couche Compose (`ui/PlayerOverlay.kt`, `ComposeView`) par-dessus la `SurfaceView` de mpv. On y voit le titre (série et « S1 · É2 — titre », ou film et année), la barre de progression, le temps écoulé et restant, et « En pause ». Elle apparaît au chargement et à chaque touche, et se masque après 5 s de lecture. En pause, elle reste affichée.
- La télécommande pilote le lecteur directement. Avec OK, l'activité met en pause ou relance, avec gauche/droite elle saute de ±10 s, avec haut elle affiche les contrôles, et avec bas ou Menu elle ouvre le menu des pistes. Quand aucune vue n'a le focus, les touches arrivent par `onKeyDown`/`onKeyUp`. Quand le menu a le focus, elles passent par la vue racine (`dispatchKeyEvent` d'un `FrameLayout`), sauf si le menu est ouvert : Compose gère alors le focus. `ComponentActivity.dispatchKeyEvent` n'est pas surchargé, car lint le refuse (`RestrictedApi`).
- Retour ferme d'abord le menu, puis masque les contrôles, puis quitte le lecteur (`OnBackPressedDispatcher`).
- Le menu des pistes lit la liste des pistes de mpv (`track-list/N/type|id|lang|title|codec|demux-channel-count|external|selected`), car c'est celle que mpv peut choisir. Elle contient aussi les sous-titres externes ajoutés par `sub-add`. Les libellés sont en français (« Japonais · AAC stéréo », « Anglais · SRT (externe) »). Le choix passe par `aid` / `sid` (`no` pour « Désactivés »).
- Le titre vient de `GET /Users/{id}/Items/{id}`, lancé à côté de la négociation de lecture. Un échec ne bloque pas la lecture.

## Alternatives
- **Leanback `PlaybackSupportFragment`** : ce sont des fragments et des vues hors Compose for TV, avec un rendu figé. Écarté.
- **Pistes depuis les `MediaStreams` d'Emby** : leurs indices ne sont pas ceux de mpv, et les sous-titres externes y sont décrits autrement. Écarté pour le choix des pistes.
- **Surcharge de `Activity.dispatchKeyEvent`** : refusée par lint (API restreinte d'androidx.core).

## Conséquences
- Le choix de piste ne dure que pour cette lecture : il n'est ni enregistré ni signalé au serveur (ADR 0040).
- L'audio par défaut est celui du fichier. Les sous-titres par défaut suivent `slang` (français).
- Les sous-titres de mpv sont dessinés sous la couche : quand les contrôles sont affichés, ils peuvent se chevaucher en bas de l'image.
