# ADR 0115 — Langues audio et sous-titres : le compte Emby par défaut, un réglage local par-dessus

Statut : Accepté (2026-11-13). Décision du propriétaire, pour le desktop et l'Android TV. Complète les ADR 0035 (préférences locales Windows), 0042 (choix retenu TV), 0055 (pistes du serveur TV) et 0065 (préférences du compte TV).

## Contexte
Le desktop n'avait que des réglages locaux (ADR 0035), dont le défaut « Comme sur le serveur Emby » suivait déjà, sans le dire, les préférences du compte : le serveur s'en sert pour choisir `DefaultAudioStreamIndex` et `DefaultSubtitleStreamIndex` dans PlaybackInfo. La TV lit ces préférences et les applique elle-même quand le serveur ne nomme pas de piste (ADR 0065), mais n'offre pas de réglage local. Le propriétaire veut la même règle partout : le compte Emby d'abord, avec un choix local possible, et voir ce que dit le compte.

## Contrat Emby
Celui de l'ADR 0065 : `GET /Users/{Id}`, `UserDto.Configuration` (objet, ou tableau d'un objet selon la documentation), dont seuls quatre champs sont lus : `AudioLanguagePreference`, `PlayDefaultAudioTrack`, `SubtitleLanguagePreference`, `SubtitleMode` (`Default`, `Always`, `OnlyForced`, `None`, `Smart`, `HearingImpaired`). Jeton dans l'en-tête, jamais dans l'adresse. Lecture seule : rien n'est écrit sur le serveur. Aucun appel au serveur réel pour ce lot.

## Décision — règle commune
Pour l'audio et pour les sous-titres, séparément :
1. **Le choix local**, s'il y en a un : il remplace le compte sur cet appareil seulement.
2. Sinon **« Comme le compte Emby »** (le défaut) : la piste choisie par le serveur à partir des préférences du compte, si elle se retrouve dans le fichier.
3. Sinon les préférences du compte appliquées par le client (règles de l'ADR 0065).
4. Sinon le choix du lecteur (mpv).

Les Réglages affichent, sous chaque choix, ce que dit le compte (« Compte Emby : Français, puis anglais. ») et précisent quand le choix local le remplace. Si le compte ne peut pas être lu, ils le disent et le serveur choisit ; rien n'est bloqué.

## Desktop (ce lot)
- Commande `emby_account_tracks` (lecture seule) : `GET /Users/{Id}` de la session, les quatre champs lus par la fonction pure `tracks::account_tracks` (testée : objet ou tableau, langues filtrées et dédoublonnées, mode inconnu = pas de préférence). `ProfilePin` et le reste ne sont jamais lus.
- Le défaut des deux listes s'appelle « Comme le compte Emby ». Les valeurs enregistrées ne changent pas (`audioLanguage: null`, `subtitleMode: "server"`) : aucune migration.
- La priorité entre local et compte est dans le module pur `src/lib/settings/accountTracks.ts` (`trackSources`, testé) pour l'affichage ; le choix des flux reste dans `src-tauri/src/emby/tracks.rs` (`choose`, testé), qui applique les étapes 1 et 2.
- **Pas encore fait** : l'étape 3 sur le desktop. Quand le serveur ne nomme pas de piste, mpv décide encore. À reprendre en passant `AccountTracks` à `choose`.

## Android TV (lot de l'agent TV, qui renvoie à cet ADR)
Les étapes 2 à 4 existent (ADR 0055, 0065). Il reste à ajouter le choix local (langue audio, mode et langue des sous-titres) avec « Comme le compte Emby » par défaut, et l'affichage de ce que dit le compte. Le choix retenu du menu des pistes (ADR 0042) reste propre à un titre et passe avant tout.

## Conséquences
- Changer de langue dans Emby suffit pour tous les appareils restés sur « Comme le compte Emby ».
- Un choix local ne suit pas le compte : il faut revenir à « Comme le compte Emby » pour que le compte compte de nouveau.
