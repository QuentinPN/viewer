import { mount } from "svelte";
import App from "./App.svelte";
import "./app.css";
import { startUiSettings } from "./lib/native/uiSettings";

const target = document.getElementById("app");
if (!target) throw new Error("#app introuvable");
const app = target;

// Before the mount, so components read the preferences the Wails build imports
// from the Tauri app (waits 500 ms at most; Tauri does not wait).
void startUiSettings().finally(() => mount(App, { target: app }));
