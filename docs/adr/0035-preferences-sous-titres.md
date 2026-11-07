# ADR 0035 — Préférences audio et sous-titres

Statut : Accepté (2026-11-07).

## Contexte
`PRODUCT.md` (P1) demande des préférences de sous-titres avancées : une langue audio par défaut, une langue de sous-titres par défaut, un mode (désactivés, forcés uniquement, toujours) et l'apparence des sous-titres texte (taille, couleur, fond ou contour, position). Jusqu'ici, les pistes étaient celles choisies par défaut par le serveur (ADR 0017), et le menu du lecteur permettait d'en changer pendant la lecture.

Vérifié le 2026-11-07, en lecture seule, sur le serveur de test (Emby 4.10.1.0), avec le film 30207 (audio français et anglais, sous-titres français forcés et complets, sous-titres anglais) :
- La configuration de l'utilisateur (`/Users/{Id}`, `Configuration`) porte `AudioLanguagePreference`, `SubtitleLanguagePreference` et `SubtitleMode`. Le serveur s'en sert pour calculer `DefaultAudioStreamIndex` et `DefaultSubtitleStreamIndex` dans la réponse PlaybackInfo.
- **Lecture directe :** les champs `AudioStreamIndex` et `SubtitleStreamIndex` de la requête PlaybackInfo sont ignorés. Le fichier entier est envoyé et le client choisit ses pistes.
- **Transcodage :** `AudioStreamIndex` n'est pris en compte qu'avec `MediaSourceId`. Il choisit alors la piste audio du flux transcodé. Les sous-titres d'un transcodage arrivent en fichiers externes (`DeliveryMethod` `External`).

## Décision
- **Préférences locales à ce poste**, dans le `localStorage` de la WebView (`viewer.player.subtitles`, sans secret), comme le volume (ADR 0017) et les intros (ADR 0030). L'interface les relit à chaque lecture et les passe à `emby_play` dans l'argument `preferences`. Elles sont validées en Rust (langues de la liste connue, valeurs d'énumération).
- **Choix des pistes en Rust** (`emby/tracks.rs`), à partir des `MediaStreams` de la source choisie :
  - Langue audio : la première piste audio dans cette langue, la piste par défaut du serveur en priorité. Sans piste dans cette langue, celle du serveur est gardée.
  - Mode « Comme sur le serveur Emby » (par défaut) : la piste de sous-titres par défaut du serveur quand elle peut être affichée, sinon mpv décide, comme avant.
  - « Désactivés » : aucun sous-titre.
  - « Forcés uniquement » : une piste forcée dans la langue choisie, à défaut dans la langue de l'audio. Sans piste forcée, aucun sous-titre.
  - « Toujours » : une piste complète (non forcée) dans la langue choisie, à défaut une piste forcée dans cette langue, sinon aucun sous-titre. Sans langue choisie, la piste du serveur si elle n'est pas forcée, à défaut la première piste complète.
  - Parmi plusieurs pistes possibles : le texte avant l'image, puis la piste marquée par défaut dans le fichier, puis l'ordre du fichier.
  - Les codes ISO 639-2 B et T et les codes à deux lettres sont tenus pour équivalents (`fre`, `fra`, `fr`).
- **Lecture directe :** le moteur reçoit l'index Emby choisi (`TrackPick::Stream`) et, au chargement du fichier, sélectionne la piste mpv dont `ff-index` vaut cet index.
- **Transcodage :** si l'audio choisi n'est pas celui du serveur, une seconde requête PlaybackInfo est envoyée avec `MediaSourceId` et `AudioStreamIndex`. Son plan n'est gardé que s'il reste un transcodage de la même source. Le sous-titre est choisi parmi les fichiers externes (drapeau `selected`).
- **Remise à zéro à chaque chargement :** `aid=auto` et `sid=auto` (ou `sid=no`), avant de choisir. Un choix fait à la main dans le menu ne passe plus au fichier suivant.
- **Apparence** par les options mpv, appliquées à chaque chargement :
  - Taille : `sub-scale` 0,8 / 1 / 1,25 / 1,5.
  - Couleur : blanc, ou jaune `#FFE14D`.
  - Lisibilité : contour (`outline-and-shadow`) ou fond sombre (`background-box`).
  - Position : `sub-pos` 100 (en bas), 85 (plus haut), ou `sub-align-y=top`.
  - Les sous-titres ASS/SSA gardent leur propre style (`sub-ass-override=no`), sauf si « Appliquer aussi aux sous-titres stylés (ASS) » est coché (`force`).
- **Jeton :** les pistes gardées dans le plan pour ce choix ont leur `DeliveryUrl` réécrite sans jeton, comme les autres URL du plan.
- Le réglage s'applique à la prochaine lecture. Le menu des pistes du lecteur reste disponible.

## Alternatives
- **Écrire les préférences dans la configuration Emby** (`POST /Users/{Id}/Configuration`) : elles suivraient le compte sur tous les clients. Écarté pour cette tranche : c'est une écriture sur le serveur, qui demande l'accord du propriétaire, et elle changerait aussi le comportement des autres clients. Le serveur en tient déjà compte dans ses choix par défaut, que le mode « Comme sur le serveur Emby » respecte.
- **Envoyer `AudioStreamIndex`/`SubtitleStreamIndex` dans toutes les requêtes PlaybackInfo :** sans effet en lecture directe, vérifié sur le serveur.
- **Laisser mpv choisir avec `alang`/`slang` :** mpv ne connaît ni la piste par défaut du serveur, ni le choix d'Emby pour un transcodage.

## Conséquences
- En lecture directe, le mode par défaut applique désormais les pistes par défaut du serveur, au lieu du choix de mpv. C'est cohérent avec l'ADR 0017.
- Un transcodage avec un autre audio coûte une requête PlaybackInfo de plus.
- Hypothèse : l'`Index` Emby d'une piste intégrée correspond à son `ff-index` dans mpv. C'est vrai sur 30207. Un fichier où la numérotation diffère garderait le choix par défaut de mpv pour cette piste.
- Les sous-titres en image (PGS, VobSub) ne changent pas d'apparence. Aucune incrustation n'est demandée au serveur.
- Les préférences ne suivent pas le compte sur un autre poste.

## Validation
- Tests Rust : choix des pistes (`emby::tracks`), transcodage avec un autre audio et lecture directe contre le faux serveur (`emby::tests`), sélection par `ff-index` et options de style lues dans mpv (`player::mpv`).
- Vitest : lecture, validation et enregistrement des préférences (`src/lib/player/subtitles.test.ts`).
- E2E `gate3-subtitles` sur le serveur de test : avec les préférences, audio anglais et sous-titres français complets, et options de style relues dans mpv ; sans, les pistes du serveur et l'apparence par défaut. Lectures remises à 0.
- Non vérifié : l'apparence des sous-titres à l'image (la vidéo mpv n'est pas capturée).
