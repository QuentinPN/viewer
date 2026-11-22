# DXGI, D3D11 et registre sans cgo (system_info.rs)

Sources : en-têtes `dxgi.h`, `dxgi1_2.h` à `dxgi1_6.h`, `d3d11.h` (non installés sur cette machine : aucun Windows SDK) ; l'ordre des méthodes a donc été relevé dans les vtables générées du crate `windows` 0.61.3 (même métadonnées Win32 que les en-têtes), présent dans `~/.cargo/registry`. Les GUID et constantes viennent du même crate. Le Rust de `src-tauri` n'est pas modifié.

## Approche : COM par syscall
- `CreateDXGIFactory1` (`dxgi.dll`) et `D3D11CreateDevice` (`d3d11.dll`) sont des exports plats : `windows.NewLazySystemDLL(...).NewProc(...)`, qui ne charge que depuis System32.
- Un objet COM est un pointeur vers un pointeur de vtable. `comObject{vtbl *[32]uintptr}` donne le slot ; `call` fait `syscall.SyscallN(vtbl[slot], this, args...)`. La fenêtre de 32 slots est plus longue que toute vtable utilisée, mais aucun slot au-delà du compte de l'interface n'est lu.
- Les arguments pointeurs sont `uintptr(unsafe.Pointer(&x))` de variables Go. `call` est marqué `//go:uintptrescapes` (comme `syscall.SyscallN`) : les variables vivent sur le tas jusqu'au retour, donc un déplacement de pile ne peut pas les invalider. `go vet` (unsafeptr) est propre : aucune conversion `uintptr -> unsafe.Pointer`.
- Les pointeurs d'interface reçus restent dans `*comObject`, mémoire du serveur COM que le GC ignore. Chaque objet a un propriétaire et un `Release` (slot 2) différé.
- Pas de `CoInitialize` : DXGI et D3D11 (sans fenêtre ni contexte immédiat) sont libres de fil, comme dans le Rust, qui n'en fait pas non plus.

## Slots utilisés (comparés aux vtables du crate `windows`)
| Interface | Slot | Méthode |
|---|---|---|
| IUnknown | 0, 2 | QueryInterface, Release |
| IDXGIFactory1 | 12 | EnumAdapters1 (IDXGIFactory : 7 à 11, IDXGIObject : 3 à 6) |
| IDXGIAdapter | 7, 9 | EnumOutputs, CheckInterfaceSupport |
| IDXGIAdapter1 | 10 | GetDesc1 |
| IDXGIOutput6 | 27 | GetDesc1 (IDXGIOutput 7 à 18, Output1 19 à 22, Output2 23, Output3 24, Output4 25, Output5 26) |
| ID3D11VideoDevice | 11, 12 | GetVideoDecoderProfileCount (renvoie un UINT, pas un HRESULT), GetVideoDecoderProfile |

Une mutation du slot GetDesc1 de l'output (27 -> 28) fait échouer `Test_go_real_dxgi_probe_on_this_pc`.

## Structures
`DXGI_ADAPTER_DESC1` (312 octets, `Flags` à 304) et `DXGI_OUTPUT_DESC1` (152 octets, `Monitor` à 88, `ColorSpace` à 100) sont refaites en Go à la disposition x64 ; `Test_go_descriptor_layouts` en vérifie tailles et décalages.

## Faisabilité
Tout ce que fait system_info.rs est porté : OS (registre), GPU (DXGI), décodeurs (D3D11 Video), écrans et HDR. Aucun morceau n'est sans cgo infaisable : **pas d'écart de fonction**.

Écarts mineurs :
- Registre : `golang.org/x/sys/windows/registry` au lieu de `RegGetValueW`. Comme en Rust, une valeur de plus de 255 caractères, ou d'un autre type que `REG_SZ` / `REG_DWORD`, compte comme absente (`GetStringValue` accepte aussi `REG_EXPAND_SZ` : refusé ici, comme `RRF_RT_REG_SZ`).
- Le probe réel n'est lancé qu'en lecture : aucune fenêtre, aucune surface de rendu, un périphérique D3D11 libéré tout de suite. Les noms de GPU ne sont écrits que dans le journal du test.
