# ADR 0061 — Informations de lecture dans le lecteur (Android TV)

Statut : Accepté (2026-11-09). Reprend sur TV le panneau « Informations de lecture » de Windows (ADR 0017, plafond de débit ADR 0037) ; s'ajoute aux menus des pistes (ADR 0042, 0055) et des chapitres (ADR 0057).

## Contexte
Sous Windows, le lecteur montre comment le serveur livre l'élément (lecture directe, flux direct ou transcodage et ses raisons), le plafond de débit appliqué, et ce que fait la machine : décodage matériel ou logiciel, codec et taille de la vidéo, audio, sortie, images perdues. Sur TV, rien de cela n'était visible : pour savoir si un HEVC 10 bits passait par MediaCodec ou si le serveur transcodait, il fallait lire logcat.

Le lecteur TV a déjà tout ce qu'il faut : le `PlaybackPlan` (méthode, URL sans jeton qui garde le paramètre `TranscodeReasons` du serveur) et `PlayerEngine.property`, qui lit une propriété libmpv en texte.

Touches du lecteur : OK lecture / pause, ◀ ▶ 10 s, ▲ chapitres quand il y en a (ADR 0057), ▼ et Menu le menu audio et sous-titres. La télécommande du Shield n'a pas de touche INFO.

## Décision
- **Ouverture : une ligne « Informations de lecture » en bas du menu ▼**, après les pistes audio ; OK sur cette ligne remplace le menu des pistes par le panneau. **La touche INFO** (`KEYCODE_INFO`) l'ouvre directement sur les télécommandes qui l'ont. Sur le Shield, ▼ puis descendre jusqu'à la dernière ligne suffit ; aucune touche déjà prise ne change de rôle.
- **Panneau à droite**, comme les autres menus, titré « Informations de lecture ». Une ligne par valeur : le libellé au-dessus, la valeur dessous. Les lignes prennent le focus, pour que ▲ ▼ fassent défiler un panneau plus haut que l'écran et que le bouton « Passer » ne prenne jamais le focus ; OK n'y fait rien. Retour le ferme. Les commandes restent affichées tant qu'il est ouvert.
- **Menus** : `PlayerUi.info` (non nul quand le panneau est ouvert) entre dans `menuOpen` et `closeMenus()`, et `reset()` le ferme au fichier suivant. Retour, la carte « Épisode suivant », le saut automatique et le focus du bouton « Passer » le traitent donc comme les autres menus.
- **Rafraîchissement** : environ une fois par seconde tant qu'il est ouvert (`snapshotFlow` sur `ui.info`, boucle annulée à la fermeture), sur le fil de l'interface comme la lecture des pistes. Lignes repérées par leur libellé : celle qui a le focus le garde.
- **Contenu, seulement ce qui est connu** : une valeur que mpv ne donne pas est omise, jamais devinée (`player/PlaybackInfo.kt`, logique pure testée).
  | Ligne | Source |
  |---|---|
  | Lecture | `PlaybackPlan.method` : « Lecture directe », « Flux direct », « Transcodage · raisons » ; raisons tirées de `TranscodeReasons` de l'URL, mêmes libellés que `transcode_reasons` de Windows, « raison non précisée » sans paramètre |
  | Débit maximal | Réglages (ADR 0051) : plafond de l'adresse du serveur, ou « Maximale », puis « réseau local » / « accès distant » |
  | Conteneur | `file-format` |
  | Vidéo | `video-format` |
  | Résolution | `video-params/w`, `video-params/h`, avec 4K / 1080p / 720p / SD |
  | Images par seconde | `container-fps` (« 23,976 i/s ») |
  | Couleur | `video-params/gamma` (PQ, HLG → HDR, sinon SDR), `video-params/primaries`, profondeur tirée de `video-params/hw-pixelformat` ou `video-params/pixelformat` |
  | Décodage | `hwdec-current` : « Logiciel » pour `no`, sinon « Matériel (mediacodec) » |
  | Piste audio | piste audio choisie (`track-list`) |
  | Format audio | `audio-codec-name`, `audio-params/channel-count`, `audio-params/samplerate` |
  | Sortie audio | `current-ao`, `audio-out-params/channel-count` (montre un mixage stéréo) |
  | Sous-titres | piste choisie, ou « Désactivés » |
  | Débit mesuré | `video-bitrate` + `audio-bitrate`, estimés par mpv (« kb/s », « Mb/s ») |
  | Images perdues | `decoder-frame-drop-count` (décodeur), `frame-drop-count` (affichage) |
  | Mémoire tampon | `demuxer-cache-duration` (« 12 s d'avance ») |
- **Nombres en français** : virgule décimale, zéros inutiles retirés, unités `kb/s`, `Mb/s`, `i/s`, `kHz`, `s`.
- **Secrets** : ni le jeton ni l'URL du flux ne sont affichés ou journalisés ; seul le paramètre `TranscodeReasons` est lu dans l'URL, puis l'URL est oubliée par le panneau.
- **Aide des commandes** : « ▼ audio, sous-titres et infos », raccourcie depuis en « ▼ options » : la liste s'allongeait à chaque réglage ajouté au menu.

## Alternatives
- **INFO seule** : la télécommande du Shield n'en a pas ; le panneau serait inaccessible sur l'appareil de test. Rejeté.
- **Appui long sur OK ou sur ▼** : rien ne l'annonce, et un appui long sur OK risque de mettre en pause. Rejeté.
- **▲ quand il n'y a pas de chapitres** : la même touche ferait deux choses selon l'élément. Rejeté.
- **Touche Menu pour le panneau** : Menu ouvre déjà les pistes, comme ▼ (ADR 0042) ; lui donner un autre rôle changerait une touche connue. Rejeté.
- **Incrustation permanente (comme les statistiques de mpv)** : couvre l'image et demande un réglage de plus. Reporté.
- **Débit et conteneur tirés de `MediaSourceInfo`** : le plan TV ne les garde pas, et en transcodage ils décrivent le fichier source, pas le flux reçu. Les valeurs de mpv décrivent ce que la TV lit réellement.
- **Dolby Vision** : mpv n'expose pas de propriété fiable pour le dire ; non affiché plutôt que deviné.

## Conséquences
- Le menu ▼ a une ligne de plus, en dernier : la sélection des pistes ne change pas.
- La profondeur de couleur peut manquer en décodage matériel direct (`mediacodec`) : le format d'image est alors une surface matérielle, sans profondeur lisible.
- Les débits sont ceux que mpv mesure sur les paquets lus, pas le débit annoncé par le serveur ; ils varient d'une seconde à l'autre.
- Réversible : retirer la ligne, la touche INFO et le panneau ne touche à rien d'autre.

## Vérification
- Tests unitaires `PlaybackInfoTest` : libellés de méthode et raisons de transcodage (paramètre seul, insensible à la casse, raisons inconnues, URL invalide), résolution et sa classe, images par seconde, débits en kb/s et Mb/s, dispositions des canaux, couleur et profondeur, décodage, conteneur, fréquence d'échantillonnage, images perdues, mémoire tampon, et la liste des lignes pour un HEVC HDR en lecture directe ou un transcodage sans propriétés mpv. `testDebugUnitTest`, `assembleDebug` et `lintDebug` passent, sans avertissement lint dans les fichiers modifiés.
- Non vérifié sur le Shield : l'ouverture par la ligne du menu ▼, le focus et le défilement dans le panneau, le rafraîchissement chaque seconde, la fermeture par Retour, la touche INFO (absente du Shield), et les valeurs réelles renvoyées par mpv-android pour `hwdec-current`, `video-params/*` en `mediacodec`, `audio-out-params/channel-count`, `video-bitrate` et `demuxer-cache-duration`, en lecture directe comme en transcodage HLS.
