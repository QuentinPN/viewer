# ADR 0065 — Pistes par défaut selon les préférences du compte Emby (Android TV)

Statut : Accepté (2026-11-10). Complète les ADR 0042 et 0055.

## Contexte
Sans choix retenu (ADR 0042), la TV prend la piste choisie par le serveur (`DefaultAudioStreamIndex`, `DefaultSubtitleStreamIndex` de PlaybackInfo, ADR 0055), sinon celle de mpv. Deux cas restaient mal servis :
- le serveur ne nomme aucun sous-titre (absence ou `-1`) : mpv prend alors un sous-titre français par `slang=fr,fre,fra`, même si le compte demande « Aucun » ou « Forcés uniquement » ;
- le serveur nomme une piste que la TV ne retrouve pas dans le fichier (`ff-index` différent, fichier externe du serveur) : mpv décide aussi.

Les préférences du compte (langue audio, langue et mode des sous-titres) sont pourtant lisibles par le client. L'ADR 0042 avait reporté leur usage.

## Contrat Emby vérifié
Documentation officielle, `UserService` → [`GET /Users/{Id}`](https://dev.emby.media/reference/RestAPI/UserService/getUsersById.html), consultée le 2026-11-10 (point d'entrée : https://dev.emby.media/reference/RestAPI.html) :
- requiert une authentification utilisateur ; `Id` dans le chemin ; réponse 200 : `UserDto` ;
- `UserDto.Configuration` est typé `UserConfiguration[]` sur cette page ; le modèle `UserConfiguration` y porte, entre autres, `AudioLanguagePreference` (texte), `PlayDefaultAudioTrack` (booléen), `SubtitleLanguagePreference` (texte), `SubtitleMode` (`SubtitlePlaybackMode`), ainsi que `ProfilePin`, `RememberAudioSelections`, `RememberSubtitleSelections`… ;
- valeurs de `SubtitlePlaybackMode` : `Default`, `Always`, `OnlyForced`, `None`, `Smart`, `HearingImpaired`.

La page ne décrit pas le sens de chaque mode, ni le format des langues. La documentation dit tableau, là où un `UserDto` porte d'ordinaire un seul objet de configuration ; l'ADR 0035 cite ces champs sans relever leur forme. Le client accepte les deux. Aucun appel au serveur réel n'a été fait pour ce lot.

## Décision
- **Lecture** (`EmbyClient.userConfiguration`) : `GET /Users/{Id}`, id contrôlé par `segment`, jeton dans l'en-tête `X-Emby-Authorization` seulement. Seuls les quatre champs ci-dessus sont décodés (`UserConfigurationDto`) : `ProfilePin` et le reste ne sont jamais lus. `Configuration` objet, ou premier objet d'un tableau ; absent ou vide : pas de préférence. `SubtitleMode` reste du texte : une valeur inconnue veut dire « pas de préférence de sous-titres », pas une erreur.
- **Une lecture par session** (`AppServices.accountTracks`) : gardée en mémoire par serveur et utilisateur tant que l'application vit. Le lecteur la lance à son ouverture, sans l'attendre. Un échec (réseau, session expirée, réponse invalide) n'est pas gardé et ne bloque rien : le choix reste celui d'avant ce lot. Le journal `ViewerTV` écrit une ligne `account audio=[…] default=… sub=[…] mode=…`, langues et mode seulement.
- **Ordre de priorité**, pour l'audio et pour les sous-titres séparément (`decide`) :
  1. le choix retenu (ADR 0042) — toujours prioritaire ;
  2. la piste du serveur, si elle se retrouve dans le fichier (ADR 0055) ;
  3. **les préférences du compte appliquées par la TV** (`accountPick`, `player/AccountTracks.kt`), origine `account` dans la ligne `tracks` ;
  4. le choix de mpv.
  Le serveur passe avant le calcul local : il applique les mêmes préférences avec ses propres règles, qui font foi. Le calcul local ne remplace que le choix de mpv.
- **Audio** : `PlayDefaultAudioTrack` vrai et une piste marquée par défaut dans le fichier → celle-ci, quelle que soit sa langue. Sinon, la première piste de la première langue voulue présente (la piste par défaut d'abord dans une langue). Aucune langue ou aucune correspondance : pas d'avis. En transcodage, le flux ne porte que l'audio du serveur : rien à choisir.
- **Sous-titres**, langues voulues dans l'ordre de `SubtitleLanguagePreference` (une liste séparée par des virgules est acceptée) :
  - `None` : désactivés ;
  - `OnlyForced` : une piste forcée dans une langue voulue ou celle de l'audio, sinon désactivés ; une piste forcée d'une autre langue (autre doublage) est écartée ;
  - `Default` : comme `OnlyForced`, puis la piste marquée par défaut dans le fichier, sinon désactivés ;
  - `Always` : une piste complète (non forcée) d'une langue voulue, sans malentendants d'abord ; à défaut une forcée de cette langue ; aucune piste de ces langues : désactivés ;
  - `HearingImpaired` : comme `Always`, la piste pour malentendants d'abord ;
  - `Smart` : audio dans une langue voulue → comme `OnlyForced` ; audio dans une autre langue → comme `Always`.
  `Always`, `HearingImpaired` et `Smart` sans langue voulue, et `Smart` sans langue d'audio connue, n'ont pas d'avis. La langue de l'audio est celle de la piste audio retenue juste avant (audio décidé d'abord).
- **Pistes forcées et malentendants** : les drapeaux de mpv (`track-list/N/forced`, `default`, `hearing-impaired`, lus par `readTracks`), ou un titre qui le dit (« forced », « forcé », « SDH ») : le relevé du Shield (ADR 0055) montre une piste nommée « FR Forced ».
- **Lecture seule** : rien n'est écrit sur le serveur ; les préférences se changent dans Emby.

## Alternatives
- **Préférences du compte avant la piste du serveur** : deux mises en œuvre des mêmes règles en concurrence, et celle du serveur connaît tous les flux. Écarté : le calcul local ne sert que de repli.
- **Garder `slang` comme seul repli** : contredit les modes `None` et `OnlyForced` du compte. Écarté.
- **Réglages locaux de langue et de mode sur la TV**, comme sous Windows (ADR 0035) : écarté par l'ADR 0055, le choix retenu du menu tient ce rôle.
- **Relire `/Users/{Id}` à chaque lecture** : une requête de plus par fichier pour une valeur qui change rarement. Écarté ; elle est relue au prochain lancement de l'application.
- **Décoder tout `UserDto.Configuration`** : inutile, et `ProfilePin` n'a rien à faire en mémoire. Écarté.

## Conséquences
- Sans choix retenu, une lecture où le serveur ne nomme pas de sous-titre suit désormais le mode du compte (`None` ou `OnlyForced` sans piste forcée → aucun sous-titre) au lieu du français de `slang`.
- Une préférence modifiée dans Emby n'est vue qu'au prochain lancement de l'application.
- Si la lecture de la configuration arrive après le chargement du fichier, ce fichier garde le comportement d'avant ; les suivants en profitent.

## Vérification
- Tests unitaires : `AccountTracksTest` (lecture de la configuration, audio par langue et par piste par défaut, chaque `SubtitleMode`, préférence absente, langue sans piste, pistes forcées par drapeau ou par titre, malentendants, choix retenu prioritaire, ordre serveur → compte → mpv, langue de l'audio joué) ; `EmbyClientTest` (`userConfiguration` : chemin, jeton dans l'en-tête seulement, `ProfilePin` non décodé, objet, tableau, absence, erreurs typées) ; `TracksTest` (lecture de `default`, `forced`, `hearing-impaired`). `testDebugUnitTest` (236 tests, 0 échec), `assembleDebug` et `lintDebug` passent, sans nouvel avertissement de lint.
- Non vérifié sur le serveur réel : la forme de `Configuration` en 4.10 (objet ou tableau), le format des langues stockées, le sens exact de chaque mode côté Emby (les règles ci-dessus suivent les libellés des modes, pas un relevé). Non vérifié sur le Shield : que la libmpv expose `forced`, `default` et `hearing-impaired` dans `track-list`, et les pistes choisies à l'écran.
