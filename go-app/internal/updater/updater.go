// Package updater is the port of src-tauri/src/updater.rs: in release
// builds, the manifest is checked 15 s after launch then every 6 h; a newer
// installer is downloaded into memory, its ed25519 signature is verified against
// the pinned public key, and the UI is told it is ready (app://update-ready).
// Installing writes the NSIS installer to a temporary folder, runs it in quiet
// mode (`/S /UPDATE /R /ARGS`, the arguments tauri-plugin-updater 2.14.1 passes)
// and quits the app; the installer relaunches it.
//
// tauri-plugin-updater is replaced by a small own implementation, not by the
// Wails v3 updater, which swaps a single binary instead of running the installer
// (research/updater.md, MAP.md decisions).
package updater

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"strings"
	"sync"
	"time"
)

// UpdateReadyEvent is UPDATE_READY_EVENT.
const UpdateReadyEvent = "app://update-ready"

// UpdateInfo is what the UI is told about a downloaded update. No rename_all in
// Rust: the keys are `version` and `notes`.
type UpdateInfo struct {
	Version string `json:"version"`
	// Notes are the Markdown release notes, empty when the manifest has none.
	Notes string `json:"notes"`
}

// NewUpdateInfo is UpdateInfo::new: both are trimmed, notes default to "".
func NewUpdateInfo(version string, notes *string) UpdateInfo {
	info := UpdateInfo{Version: strings.TrimSpace(version)}
	if notes != nil {
		info.Notes = strings.TrimSpace(*notes)
	}
	return info
}

// Slot is the downloaded update, kept until it is installed. Generic over the
// payload so the transitions are testable without a real download. Not safe for
// concurrent use: Updater guards it with a mutex.
type Slot[T any] struct {
	ready   bool
	info    UpdateInfo
	payload T
}

// Downloaded is the download of version, if it is the one already kept: a
// version is fetched only once.
func (s *Slot[T]) Downloaded(version string) (UpdateInfo, bool) {
	if !s.ready || s.info.Version != strings.TrimSpace(version) {
		return UpdateInfo{}, false
	}
	return s.info, true
}

// Store keeps the newest download; the payload of an older one is dropped.
func (s *Slot[T]) Store(info UpdateInfo, payload T) {
	s.ready, s.info, s.payload = true, info, payload
}

// Info is the kept update, if any.
func (s *Slot[T]) Info() (UpdateInfo, bool) { return s.info, s.ready }

// Payload is the kept payload, if any.
func (s *Slot[T]) Payload() (T, bool) { return s.payload, s.ready }

// CheckOutcome is the result of one check, as the Settings section shows it.
// Serialized like the Rust enum (`#[serde(tag = "status", rename_all =
// "camelCase")]`): the tag first, then the fields of the variant.
type CheckOutcome struct {
	Status string // StatusUpToDate, StatusReady, StatusAvailable or StatusFailed
	// Info is set for StatusReady.
	Info UpdateInfo
	// Version is set for StatusAvailable.
	Version string
	// Category is set for StatusFailed (see Category).
	Category string
}

// The four variants of CheckOutcome.
const (
	// StatusUpToDate: no newer version on the manifest.
	StatusUpToDate = "upToDate"
	// StatusReady: a newer version is downloaded and verified (also announced by
	// app://update-ready).
	StatusReady = "ready"
	// StatusAvailable: debug builds only, a newer version exists but is not
	// downloaded, so a dev build never installs a release over itself.
	StatusAvailable = "available"
	// StatusFailed: the check or the download failed.
	StatusFailed = "failed"
)

// UpToDate is CheckOutcome::UpToDate.
func UpToDate() CheckOutcome { return CheckOutcome{Status: StatusUpToDate} }

// Ready is CheckOutcome::Ready.
func Ready(info UpdateInfo) CheckOutcome { return CheckOutcome{Status: StatusReady, Info: info} }

// Available is CheckOutcome::Available.
func Available(version string) CheckOutcome {
	return CheckOutcome{Status: StatusAvailable, Version: version}
}

// Failed is CheckOutcome::Failed.
func Failed(category string) CheckOutcome {
	return CheckOutcome{Status: StatusFailed, Category: category}
}

// MarshalJSON writes the internally tagged shape of the Rust enum.
func (o CheckOutcome) MarshalJSON() ([]byte, error) {
	switch o.Status {
	case StatusUpToDate:
		return json.Marshal(struct {
			Status string `json:"status"`
		}{o.Status})
	case StatusReady:
		return json.Marshal(struct {
			Status string     `json:"status"`
			Info   UpdateInfo `json:"info"`
		}{o.Status, o.Info})
	case StatusAvailable:
		return json.Marshal(struct {
			Status  string `json:"status"`
			Version string `json:"version"`
		}{o.Status, o.Version})
	case StatusFailed:
		return json.Marshal(struct {
			Status   string `json:"status"`
			Category string `json:"category"`
		}{o.Status, o.Category})
	}
	return nil, fmt.Errorf("updater: unknown outcome status %q", o.Status)
}

// Kind is the variant of an updater failure, named after the
// tauri_plugin_updater::Error variants it replaces.
type Kind int

// The failure kinds. Only their Category reaches the UI and the log.
const (
	// KindOther covers what has no category of its own.
	KindOther Kind = iota
	// KindNetwork: the request failed, or the download did not answer 2xx
	// (Error::Reqwest, Error::Network).
	KindNetwork
	// KindReleaseNotFound: no endpoint gave a release, e.g. a non-2xx manifest.
	KindReleaseNotFound
	// KindTargetsNotFound: the manifest has no entry for windows-x86_64.
	KindTargetsNotFound
	// KindSerialization: the manifest is not valid JSON or misses a field.
	KindSerialization
	// KindSemver: a version is not valid semver.
	KindSemver
	// KindInvalidUpdaterFormat: the downloaded file is not a Windows executable.
	KindInvalidUpdaterFormat
	// KindSignature: the signature does not decode or does not verify, or no
	// public key is pinned (Error::Minisign).
	KindSignature
	// KindMissingSignature: the manifest entry has no ed25519 signature
	// (the place of Error::MissingSignedVersion in the categories).
	KindMissingSignature
	// KindInsecureTransport: an http endpoint while insecure transport is off.
	KindInsecureTransport
	// KindIo: the installer could not be written or launched.
	KindIo
	// KindEmptyEndpoints: no endpoint is configured.
	KindEmptyEndpoints
)

// Error is an updater failure. Its text may carry the endpoint, so only its
// Category is logged or sent to the UI.
type Error struct {
	Kind  Kind
	cause error
}

func newError(kind Kind, cause error) *Error { return &Error{Kind: kind, cause: cause} }

func (e *Error) Error() string {
	if e.cause == nil {
		return "updater: " + Category(e)
	}
	return "updater: " + Category(e) + ": " + e.cause.Error()
}

func (e *Error) Unwrap() error { return e.cause }

// Category is the log category of an updater failure: never the URL, body or
// message, which can carry the endpoint. One of network, signature, manifest,
// transport, io or other.
func Category(err error) string {
	var e *Error
	if !errors.As(err, &e) {
		return "other"
	}
	switch e.Kind {
	case KindNetwork:
		return "network"
	case KindSignature, KindMissingSignature:
		return "signature"
	case KindReleaseNotFound, KindTargetsNotFound, KindSerialization, KindSemver,
		KindInvalidUpdaterFormat:
		return "manifest"
	case KindInsecureTransport:
		return "transport"
	case KindIo:
		return "io"
	}
	return "other"
}

func failed(err error, what string) CheckOutcome {
	category := Category(err)
	slog.Info(fmt.Sprintf("update %s failed: %s", what, category))
	return Failed(category)
}

// Emitter sends an event to the UI (bridge.Emitter has the same method).
type Emitter interface {
	Emit(name string, payload any)
}

// Config is what tauri.conf.json `plugins.updater` and the plugin builder gave.
type Config struct {
	// CurrentVersion is the running version (package.json, injected at build
	// time by the app-shell), compared as semver with the manifest's.
	CurrentVersion string
	// Endpoints are checked in order; the first that gives a release wins.
	Endpoints []string
	// AllowInsecureTransport is dangerousInsecureTransportProtocol: the LAN
	// Gitea is plain http, the signature guarantees integrity.
	AllowInsecureTransport bool
	// PublicKey is the base64 ed25519 public key (32 bytes); empty fails closed.
	PublicKey string
	// InstallerArgs are the NSIS arguments of a quiet install that relaunches.
	InstallerArgs []string
	// AppArgs are the app's own launch arguments (os.Args[1:]), passed after
	// /ARGS so the relaunched app gets them back (plugin current_exe_args).
	AppArgs []string
	// HTTPClient is used for the manifest and the download; nil uses a default
	// client without timeout, like reqwest in the plugin.
	HTTPClient *http.Client
	// Launch runs the installer; nil uses ShellExecute "open" on Windows.
	Launch func(path, args string) error
	// Quit ends the app once the installer runs; nil uses os.Exit(0), like
	// std::process::exit(0) in the plugin.
	Quit func()
	// TempDir is where the installer is written; empty uses os.TempDir().
	TempDir string
}

// DefaultConfig is the shipped configuration: the endpoint, the insecure
// transport flag and the quiet NSIS mode of tauri.conf.json, and the pinned key.
func DefaultConfig(currentVersion string) Config {
	return Config{
		CurrentVersion:         currentVersion,
		Endpoints:              []string{DefaultEndpoint},
		AllowInsecureTransport: true,
		PublicKey:              PublicKey,
		InstallerArgs:          QuietInstallerArgs(),
		AppArgs:                append([]string(nil), os.Args[1:]...),
	}
}

// DefaultEndpoint is plugins.updater.endpoints[0] of tauri.conf.json.
const DefaultEndpoint = "http://git.example.com/QuentinPN/Viewer-releases/raw/branch/main/desktop/latest.json"

// QuietInstallerArgs are the NSIS arguments of installMode "quiet" with
// restart_after_install and no app argument (plugin 2.14.1 updater_parameters):
// silent, update mode, relaunch, then the (empty) arguments of the relaunched app.
func QuietInstallerArgs() []string { return []string{"/S", "/UPDATE", "/R", "/ARGS"} }

// payload is the verified installer of a release, kept in the slot.
type payload struct {
	release release
	bytes   []byte
}

// Updater is the managed UpdateState: the update and its verified installer
// bytes, and the lock that serializes checks (the background loop and the
// Settings button never download twice).
type Updater struct {
	cfg  Config
	emit Emitter

	mu   sync.Mutex
	slot Slot[payload]

	checking chan struct{} // capacity 1: held during a check
}

// New builds the updater. emit may be nil (no event is sent).
func New(cfg Config, emit Emitter) *Updater {
	return &Updater{cfg: cfg, emit: emit, checking: make(chan struct{}, 1)}
}

func (u *Updater) httpClient() *http.Client {
	if u.cfg.HTTPClient != nil {
		return u.cfg.HTTPClient
	}
	return http.DefaultClient
}

// CheckOnce is check_once, shared by the background loop and the Settings
// button. Checks are serialized: a second caller waits, then finds the version
// already downloaded. download is false in debug builds (see StatusAvailable).
func (u *Updater) CheckOnce(ctx context.Context, download bool) CheckOutcome {
	select {
	case u.checking <- struct{}{}:
	case <-ctx.Done():
		return failed(newError(KindNetwork, ctx.Err()), "check")
	}
	defer func() { <-u.checking }()

	current, err := u.validate()
	if err != nil {
		return failed(err, "setup")
	}
	rel, err := u.check(ctx, current)
	if err != nil {
		return failed(err, "check")
	}
	if rel == nil {
		return UpToDate()
	}
	u.mu.Lock()
	info, ok := u.slot.Downloaded(rel.version)
	u.mu.Unlock()
	if ok {
		return Ready(info)
	}
	if !download {
		return Available(strings.TrimSpace(rel.version))
	}
	bytes, err := u.download(ctx, rel, current)
	if err != nil {
		return failed(err, "download")
	}
	info = NewUpdateInfo(rel.version, rel.notes)
	slog.Info(fmt.Sprintf("update %s downloaded", info.Version))
	u.mu.Lock()
	u.slot.Store(info, payload{release: *rel, bytes: bytes})
	u.mu.Unlock()
	if u.emit != nil {
		u.emit.Emit(UpdateReadyEvent, info)
	}
	return Ready(info)
}

// Pending is app_update_pending: the downloaded update, if any.
func (u *Updater) Pending() (UpdateInfo, bool) {
	u.mu.Lock()
	defer u.mu.Unlock()
	return u.slot.Info()
}

// Background delays: 15 s after launch, then every 6 h.
const (
	FirstCheckDelay = 15 * time.Second
	CheckInterval   = 6 * time.Hour
)

// Run is spawn, for release builds only: it checks FirstCheckDelay after it is
// called, then every CheckInterval, until ctx is done.
func (u *Updater) Run(ctx context.Context) {
	u.run(ctx, FirstCheckDelay, CheckInterval)
}

func (u *Updater) run(ctx context.Context, first, every time.Duration) {
	timer := time.NewTimer(first)
	defer timer.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-timer.C:
		}
		u.CheckOnce(ctx, true)
		timer.Reset(every)
	}
}
