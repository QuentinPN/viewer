# Coquille Wails v3.1.0 contre Tauri 2 (unité app-shell)

Ce qu'il faut savoir de Wails pour que `go-app/cmd/viewer` se comporte comme `src-tauri/src/lib.rs`. Sources lues le 2026-11-22 : le module `github.com/wailsapp/wails/v3@v3.1.0` du cache Go (`pkg/application/webview_window_windows.go`, `event_manager.go`, `application_options.go`), `src-tauri/tauri.conf.json`, `src-tauri/src/lib.rs`, et le profil WebView2 de l'app Tauri installée (dossier leveldb du localStorage, lu sans le modifier).

## Requêtes servies par Go
- `webview_window_windows.go:2518-2524` : WebView2 ne passe à Go que les requêtes `http` dont l'hôte commence par `wails.localhost`. Le reste part au gestionnaire par défaut de WebView2.
- Conséquence : les origines Tauri `http://emby-img.localhost/…` et `http://requestimg.localhost/…` n'arrivent jamais à Go. `protocols.go` sert donc `/emby-img/…` et `/requestimg/…` sous `wails.localhost`, avec les mêmes statuts et le même `Cache-Control`.
- Réglé côté Go, à confirmer à l'écran : `main` appelle `useAppImageOrigins`, qui fait écrire à `internal/emby` et `internal/request` des URL sur `http://wails.localhost/emby-img/…` et `/requestimg/…`. Tauri garde ses origines par défaut (voir MAP.md, « Unité app-shell »).

## Dossier de données WebView2
- `application_options.go:298` et `webview_window_windows.go:2607` : `WindowsOptions.WebviewUserDataPath` devient `chromium.DataPath` sans transformation, et WebView2 ajoute lui-même `EBWebView` dessous.
- Tauri 2.12.5 utilise `app_local_data_dir`, soit `%LOCALAPPDATA%\io.github.quentinpn.viewer` (`io.github.quentinpn.viewer.debug` en debug). Le port passe `Paths.LocalData`, ce qui donne le même dossier `EBWebView`.
- **Bloquant pour la bascule :** le localStorage est rangé par origine. Tauri l'écrit sous `http://tauri.localhost` (vu dans le leveldb), Wails sous `http://wails.localhost`. Le même profil ne rend donc pas les réglages de l'UI (thème, sous-titres, dernier écran) : il faudra une migration, ou garder l'origine.

## Événements
- `event_manager.go:31-45` : `Emit(name, data...)` ne met pas une donnée unique dans un tableau (`event.Data = data[0]`). La charge utile arrive donc comme avec `app.emit` de Tauri. Une charge nil donne `null`, comme `()` sérialisé par Tauri.
- L'envoi vers le frontend passe par une file : `Emit` ne bloque pas sur l'UI, donc il peut être appelé depuis le puits d'événements du lecteur.

## Instance unique
- `lib.rs` n'enregistre pas `tauri-plugin-single-instance`, et `Cargo.toml` ne le déclare pas. Le port n'active donc pas `SingleInstance` de Wails : une deuxième instance s'ouvre, comme sous Tauri.

## Fenêtre
- Elle reprend `tauri.conf.json` : 1280×720, minimum 640×360, maximisée, sans cadre, transparente, et le titre de `TitleFor`.
- Le HWND pour mpv (ADR 0003, mode parent) vient de `NativeWindow()`.
- Fermer avec `app.Quit()` pendant que WebView2 vit journalise « Failed to unregister class Chrome_WidgetWin_0 » (1412, voir gate1-go). Le port appelle donc `win.Close()`, et Wails quitte à la fermeture de la dernière fenêtre.
