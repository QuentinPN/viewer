# libmpv client API, vu de Go sans cgo (unité player, vague 3)

Source primaire : `vendor/mpv/include/mpv/client.h` (en-tête du dépôt, `MPV_CLIENT_API_VERSION` 2.6 ; la version de la DLL n'est vérifiée qu'au chargement, majeur 2), lu pour cette unité. Le binding `internal/mpv` (spike) en reprend les signatures ; la vague 3 y ajoute ce que `player/mpv.rs` et `ffi.rs` utilisent en plus.

## Fonctions ajoutées au binding
- `int mpv_command_async(mpv_handle *ctx, uint64_t reply_userdata, const char **args)` : la commande tourne sur les threads de mpv, le résultat arrive en `MPV_EVENT_COMMAND_REPLY` (5) avec `reply_userdata` et `error`. Un code négatif au retour veut dire que la commande n'a pas pu être analysée ni mise en file, et alors aucun événement ne suit. mpv copie les arguments avant de rendre la main : l'argv Go n'a besoin de vivre que pendant l'appel (`runtime.KeepAlive`). Sous Windows x64, `uint64_t` passe dans un registre entier, donc un `uintptr`.
- Fin de fichier : `MPV_END_FILE_REASON_EOF` 0, `STOP` 2, `QUIT` 3, `ERROR` 4, `REDIRECT` 5. `mpv_event_end_file.error` n'est non nul que pour `ERROR`. Rust range `REDIRECT` (et toute valeur inconnue) dans `Other`.

## Propriétés observées en FLAG et DOUBLE
- `mpv_event_property { const char *name; mpv_format format; void *data; }`. `format` est le format demandé, sauf si la propriété est indisponible : `MPV_FORMAT_NONE`, et `data` est NULL et invalide. Rust ignore ces deux cas (`data.is_null()`, puis le `match` sur le format).
- `MPV_FORMAT_FLAG` (3) : `data` pointe sur un `int` C (32 bits), 0 ou 1.
- `MPV_FORMAT_DOUBLE` (5) : `data` pointe sur un `double`. Lire 8 octets en mémoire ne pose pas le problème des retours flottants par `syscall` (registre XMM0), puisque rien n'est renvoyé par registre : c'est une lecture de la mémoire de libmpv, valable jusqu'au prochain `mpv_wait_event`.
- `MPV_FORMAT_STRING` (1) : `data` pointe sur un `char *`.

## Messages de journal
`mpv_event_log_message { const char *prefix; const char *level; const char *text; int log_level; }` : `text` est une ligne terminée par `\n`. Rust écrit `[prefix] text` sans les blancs finaux, après `redact`.

## Fil d'exécution
`mpv_wait_event` ne doit être appelé que depuis un seul fil, et l'événement rendu reste valable jusqu'à l'appel suivant : le binding copie tout avant de rendre la main. `mpv_wakeup` interrompt une attente. `mpv_terminate_destroy` bloque jusqu'à l'arrêt du cœur ; avec une fenêtre vidéo enfant de la fenêtre principale, il ne doit pas tourner sur le fil de l'UI (ADR 0003). Le reste de l'API est sûr entre fils.

## Version
`mpv_client_api_version()` rend `MAJOR << 16 | MINOR` sur un `unsigned long` (32 bits sous Windows). Rust refuse un majeur différent de 2 avec `UnsupportedApi("M.m")`, distinct de `LibraryUnavailable` : le binding rend donc une erreur typée `*UnsupportedAPIError` pour ce cas.
