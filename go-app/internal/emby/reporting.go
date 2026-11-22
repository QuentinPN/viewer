package emby

// Playback reporting (ADR 0008), port of emby/reporting.rs: player events become Emby
// start, progress and stop reports, sent in order by one background goroutine. The
// UI never sends reports.

import (
	"context"
	"errors"
	"log/slog"
	"math"
	"sync"
	"time"

	"viewer/internal/player"
)

// ProgressInterval is the progress cadence, also while paused so the server keeps
// the session alive.
const ProgressInterval = 10 * time.Second

// PlaybackStoppedEvent is the UI event carrying the item's server state once a
// playback stopped (payload PlaybackStopped).
const PlaybackStoppedEvent = "emby://playback-stopped"

const (
	reportTicksPerSecond = 10_000_000.0
	stopRetryDelay       = time.Second
	// A lost stop report (ADR 0024): waits between tries, doubled up to the cap.
	stopResendFirst = 5 * time.Second
	stopResendCap   = 15 * time.Second
	// stopResendGiveUp: an older report could overwrite a playback made elsewhere meanwhile.
	stopResendGiveUp = time.Hour
	// stopResendWait: how long `emby_play` waits for the resend before asking the
	// server's position.
	stopResendWait = 5 * time.Second
	// earlyEOFTicks: margin before the end under which an EOF is a normal end
	// (credits, rounding).
	earlyEOFTicks = int64(30 * reportTicksPerSecond)
)

// ActivePlayback is a playback announced by `emby_play`, reported once the file is loaded.
type ActivePlayback struct {
	ItemID        string
	MediaSourceID string
	PlaySessionID *string
	Method        PlayMethod
	RunTimeTicks  *int64
	StartTicks    int64
}

// NewActivePlayback is `ActivePlayback::new`.
func NewActivePlayback(itemID string, plan *PlaybackPlan) ActivePlayback {
	return ActivePlayback{
		ItemID:        itemID,
		MediaSourceID: plan.MediaSourceID,
		PlaySessionID: cloneStr(plan.PlaySessionID),
		Method:        plan.Method,
		RunTimeTicks:  cloneI64(plan.RunTimeTicks),
		StartTicks:    plan.StartTicks,
	}
}

func cloneStr(p *string) *string {
	if p == nil {
		return nil
	}
	v := *p
	return &v
}

func cloneI64(p *int64) *int64 {
	if p == nil {
		return nil
	}
	v := *p
	return &v
}

func sameStr(a, b *string) bool {
	if a == nil || b == nil {
		return a == nil && b == nil
	}
	return *a == *b
}

// ReportKind is the kind of a playback report.
type ReportKind int

const (
	ReportStart ReportKind = iota
	ReportProgress
	ReportStopped
)

// Path is the Emby endpoint of the report.
func (k ReportKind) Path() string {
	switch k {
	case ReportStart:
		return "/Sessions/Playing"
	case ReportProgress:
		return "/Sessions/Playing/Progress"
	default:
		return "/Sessions/Playing/Stopped"
	}
}

func (k ReportKind) String() string {
	switch k {
	case ReportStart:
		return "Start"
	case ReportProgress:
		return "Progress"
	default:
		return "Stopped"
	}
}

// Report is one playback report.
type Report struct {
	Kind ReportKind
	Body PlaybackReportBody
}

// LateProgress is the progress report sent ahead of a lost stop report (ADR 0024).
func (r Report) LateProgress() Report {
	body := r.Body
	body.IsPaused = ptrTo(false)
	body.CanSeek = ptrTo(true)
	body.EventName = ptrTo("TimeUpdate")
	body.Failed = nil
	return Report{Kind: ReportProgress, Body: body}
}

func ptrTo[T any](v T) *T { return &v }

type phaseKind int

const (
	phaseIdle phaseKind = iota
	// phasePending: announced, file not loaded yet: nothing is reported if loading fails.
	phasePending
	phasePlaying
	// phaseSwitching: a quality change (ADR 0117): the old stream plays on until the
	// new one, same item, is loaded. Neither stop nor start is reported.
	phaseSwitching
)

// phase is `Phase`: playing is set for Playing and Switching, next for Pending
// (the playback announced) and Switching (the new stream).
type phase struct {
	kind    phaseKind
	playing *playing
	next    ActivePlayback
}

type playing struct {
	playback      ActivePlayback
	positionTicks int64
	paused        bool
	lastSent      time.Time
	// seeked: a seek finished: report the next position.
	seeked bool
}

// endedEarly: an end of file well before the known duration: mpv reports a stream
// cut by the network as EOF once its cache drains (ADR 0012). Never "watched".
func (p *playing) endedEarly() bool {
	run := p.playback.RunTimeTicks
	return run != nil && *run-p.positionTicks > earlyEOFTicks
}

func (p *playing) report(kind ReportKind, event *string, now time.Time) Report {
	p.lastSent = now
	pb := &p.playback
	live := kind != ReportStopped
	body := PlaybackReportBody{
		ItemID:        pb.ItemID,
		MediaSourceID: pb.MediaSourceID,
		PlaySessionID: cloneStr(pb.PlaySessionID),
		PositionTicks: p.positionTicks,
		RunTimeTicks:  cloneI64(pb.RunTimeTicks),
		// Also on a stop: LateProgress needs it (ADR 0024).
		PlayMethod: ptrTo(pb.Method.AsEmby()),
		EventName:  cloneStr(event),
	}
	if live {
		body.IsPaused = ptrTo(p.paused)
		body.CanSeek = ptrTo(true)
	}
	return Report{Kind: kind, Body: body}
}

// Tracker is the pure state machine from player events to reports; time is passed
// in. The zero value is idle. Not safe for concurrent use (one goroutine owns it).
type Tracker struct {
	phase phase
	// endedEncoding: play session of a transcode replaced by a quality change, to
	// stop on the server once the new stream is loaded (ADR 0117).
	endedEncoding *string
}

func (t *Tracker) isIdle() bool { return t.phase.kind == phaseIdle }

func (t *Tracker) take() phase {
	p := t.phase
	t.phase = phase{}
	return p
}

// Begin announces a new playback; one still playing is reported stopped first.
func (t *Tracker) Begin(playback ActivePlayback, now time.Time) []Report {
	out := t.stop(false, now)
	t.phase = phase{kind: phasePending, next: playback}
	return out
}

// Switch is a quality change of the item playing (ADR 0117): no stop, no new start;
// a `QualityChange` progress with the new session once its file is loaded. Anything
// else playing (or nothing) is a new playback, as Begin.
func (t *Tracker) Switch(playback ActivePlayback, now time.Time) []Report {
	old := t.take()
	if (old.kind == phasePlaying || old.kind == phaseSwitching) && old.playing.playback.ItemID == playback.ItemID {
		t.phase = phase{kind: phaseSwitching, playing: old.playing, next: playback}
		return nil
	}
	t.phase = old
	return t.Begin(playback, now)
}

// TakeEndedEncoding returns the transcode a quality change replaced, once.
func (t *Tracker) TakeEndedEncoding() *string {
	out := t.endedEncoding
	t.endedEncoding = nil
	return out
}

// onSwitching handles events while a quality change loads. handled false: the switch
// fell back to the old playback, to be handled as usual.
func (t *Tracker) onSwitching(event player.PlayerEvent, now time.Time) (reports []Report, handled bool) {
	if t.phase.kind != phaseSwitching {
		return nil, true
	}
	old := t.phase.playing
	switch e := event.(type) {
	case player.EndFile:
		if e.Reason == player.EndStopped {
			// The old file ends as the new one replaces it.
			return nil, true
		}
		// The new stream failed: the old playback ends.
		t.phase = phase{kind: phasePlaying, playing: old}
		return nil, false
	case player.Shutdown:
		t.phase = phase{kind: phasePlaying, playing: old}
		return nil, false
	case player.TimePos:
		if v, ok := ticks(e.Seconds); ok {
			old.positionTicks = v
		}
		return nil, true
	case player.FileLoaded:
		p := t.take()
		replaced := &p.playing.playback
		if replaced.Method == MethodTranscode && !sameStr(replaced.PlaySessionID, p.next.PlaySessionID) {
			t.endedEncoding = cloneStr(replaced.PlaySessionID)
		}
		np := &playing{positionTicks: p.next.StartTicks, playback: p.next, lastSent: now}
		report := np.report(ReportProgress, ptrTo("QualityChange"), now)
		t.phase = phase{kind: phasePlaying, playing: np}
		return []Report{report}, true
	}
	return nil, true
}

// OnEvent turns one player event into the reports it causes.
func (t *Tracker) OnEvent(event player.PlayerEvent, now time.Time) []Report {
	if t.phase.kind == phaseSwitching {
		if reports, handled := t.onSwitching(event, now); handled {
			return reports
		}
	}
	if t.phase.kind == phasePlaying {
		p := t.phase.playing
		switch e := event.(type) {
		case player.EndFile:
			failed := false
			switch e.Reason {
			case player.EndError:
				failed = true
			case player.EndEOF:
				failed = p.endedEarly()
			}
			return t.stop(failed, now)
		case player.Shutdown:
			return t.stop(false, now)
		}
	}
	switch t.phase.kind {
	case phasePending:
		switch e := event.(type) {
		case player.FileLoaded:
			pb := t.take().next
			p := &playing{positionTicks: pb.StartTicks, playback: pb, lastSent: now}
			report := p.report(ReportStart, nil, now)
			t.phase = phase{kind: phasePlaying, playing: p}
			return []Report{report}
		case player.EndFile:
			// The file this playback replaces ends before the new one loads.
			if e.Reason != player.EndStopped {
				t.phase = phase{}
			}
		case player.Shutdown:
			t.phase = phase{}
		}
	case phasePlaying:
		p := t.phase.playing
		switch e := event.(type) {
		case player.TimePos:
			if v, ok := ticks(e.Seconds); ok {
				p.positionTicks = v
			}
			if p.seeked {
				p.seeked = false
				return []Report{p.report(ReportProgress, ptrTo("TimeUpdate"), now)}
			}
		case player.Duration:
			if p.playback.RunTimeTicks == nil {
				if v, ok := ticks(e.Seconds); ok && v > 0 {
					p.playback.RunTimeTicks = &v
				}
			}
		case player.Paused:
			if e.Paused != p.paused {
				p.paused = e.Paused
				event := "Unpause"
				if e.Paused {
					event = "Pause"
				}
				return []Report{p.report(ReportProgress, &event, now)}
			}
		case player.Seeked:
			p.seeked = true
		}
	}
	return nil
}

// Tick is the periodic progress, due every ProgressInterval since the last report.
func (t *Tracker) Tick(now time.Time) []Report {
	if t.phase.kind == phasePlaying {
		p := t.phase.playing
		if now.Sub(p.lastSent) >= ProgressInterval {
			return []Report{p.report(ReportProgress, ptrTo("TimeUpdate"), now)}
		}
	}
	return nil
}

func (t *Tracker) stop(failed bool, now time.Time) []Report {
	p := t.take()
	if p.kind != phasePlaying && p.kind != phaseSwitching {
		return nil
	}
	report := p.playing.report(ReportStopped, nil, now)
	report.Body.Failed = ptrTo(failed)
	return []Report{report}
}

// ticks is Rust's `(seconds * 1e7).round() as i64` for a finite, non-negative value
// (`as` saturates).
func ticks(seconds float64) (int64, bool) {
	if math.IsNaN(seconds) || math.IsInf(seconds, 0) || seconds < 0 {
		return 0, false
	}
	v := math.Round(seconds * reportTicksPerSecond)
	if v >= math.MaxInt64 {
		return math.MaxInt64, true
	}
	return int64(v), true
}

// resendSchedule says when a lost stop report is sent again, and when it is given
// up (ADR 0024).
type resendSchedule struct {
	giveUpAt time.Time
	nextAt   time.Time
	delay    time.Duration
}

func newResendSchedule(now time.Time) resendSchedule {
	return resendSchedule{giveUpAt: now.Add(stopResendGiveUp), nextAt: now.Add(stopResendFirst), delay: stopResendFirst}
}

func (s *resendSchedule) due(now time.Time) bool     { return !now.Before(s.nextAt) }
func (s *resendSchedule) expired(now time.Time) bool { return !now.Before(s.giveUpAt) }

// postpone: the try at now failed.
func (s *resendSchedule) postpone(now time.Time) {
	s.delay = min(s.delay*2, stopResendCap)
	s.nextAt = now.Add(s.delay)
}

// lostStop is a stop report the server did not get, and the session it belongs to.
type lostStop struct {
	report   Report
	owner    *Client
	schedule resendSchedule
}

// isTransient: a failure that may pass, unlike a refused token or a bad request.
func isTransient(err error) bool {
	var e EmbyError
	if !errors.As(err, &e) {
		return false
	}
	switch e.Kind {
	case KindUnreachable, KindTimeout, KindServer:
		return true
	}
	return false
}

type messageKind int

const (
	msgBegin messageKind = iota
	msgSwitch
	msgPlayer
	msgFlush
	msgResendStop
)

type message struct {
	kind     messageKind
	playback ActivePlayback
	event    player.PlayerEvent
	ack      chan struct{}
}

// Reporter is the handle to the reporting goroutine. Sending never blocks: the queue
// is unbounded, as Rust's unbounded channel. Rust's AppHandle is replaced by the
// service and an emit function (the app shell passes its event emitter). Go has no
// Drop: Close ends the goroutine once the queued messages are handled.
type Reporter struct {
	emby   *EmbyService
	emit   func(event string, payload any)
	log    *slog.Logger
	ctx    context.Context
	cancel context.CancelFunc

	mu     sync.Mutex
	queue  []message
	closed bool
	wake   chan struct{}
	done   chan struct{}
}

// NewReporter is `Reporter::spawn`. emit receives PlaybackStoppedEvent with a
// PlaybackStopped; nil emits nothing.
func NewReporter(emby *EmbyService, emit func(event string, payload any)) *Reporter {
	if emit == nil {
		emit = func(string, any) {}
	}
	ctx, cancel := context.WithCancel(context.Background())
	r := &Reporter{
		emby: emby, emit: emit, log: slog.Default(), ctx: ctx, cancel: cancel,
		wake: make(chan struct{}, 1), done: make(chan struct{}),
	}
	go r.run()
	return r
}

func (r *Reporter) send(m message) bool {
	r.mu.Lock()
	if r.closed {
		r.mu.Unlock()
		return false
	}
	r.queue = append(r.queue, m)
	r.mu.Unlock()
	select {
	case r.wake <- struct{}{}:
	default:
	}
	return true
}

// Begin announces a playback.
func (r *Reporter) Begin(playback ActivePlayback) {
	r.send(message{kind: msgBegin, playback: playback})
}

// Switch is a quality change of the playback in progress (ADR 0117).
func (r *Reporter) Switch(playback ActivePlayback) {
	r.send(message{kind: msgSwitch, playback: playback})
}

// PlayerEvent forwards the events the tracker uses (track lists and buffering are
// left out).
func (r *Reporter) PlayerEvent(event player.PlayerEvent) {
	switch event.(type) {
	case player.Tracks, player.Buffering:
		return
	}
	r.send(message{kind: msgPlayer, event: event})
}

// Flush blocks until every report queued so far was sent, at most timeout. Not for
// the UI thread. Returns false on timeout or once closed.
func (r *Reporter) Flush(timeout time.Duration) bool {
	ack := make(chan struct{}, 1)
	if !r.send(message{kind: msgFlush, ack: ack}) {
		return false
	}
	timer := time.NewTimer(timeout)
	defer timer.Stop()
	select {
	case <-ack:
		return true
	case <-timer.C:
		return false
	}
}

// ResendStop sends a lost stop report now, so that the server's position is the
// right one before a new playback asks for it (ADR 0024). Waits a bounded time.
func (r *Reporter) ResendStop(ctx context.Context) {
	ack := make(chan struct{}, 1)
	if !r.send(message{kind: msgResendStop, ack: ack}) {
		return
	}
	timer := time.NewTimer(stopResendWait)
	defer timer.Stop()
	select {
	case <-ack:
	case <-timer.C:
	case <-ctx.Done():
	}
}

// Close stops taking messages; the goroutine handles those queued, then ends. A call
// in flight is cancelled after timeout. Returns false if it did not end in time.
func (r *Reporter) Close(timeout time.Duration) bool {
	r.mu.Lock()
	r.closed = true
	r.mu.Unlock()
	select {
	case r.wake <- struct{}{}:
	default:
	}
	timer := time.NewTimer(timeout)
	defer timer.Stop()
	select {
	case <-r.done:
		return true
	case <-timer.C:
		r.cancel()
		return false
	}
}

// next waits for a message, at most one second. closed: no message is left and none
// will come.
func (r *Reporter) next() (m message, ok, closed bool) {
	timer := time.NewTimer(time.Second)
	defer timer.Stop()
	for {
		r.mu.Lock()
		if len(r.queue) > 0 {
			m = r.queue[0]
			r.queue[0] = message{}
			r.queue = r.queue[1:]
			r.mu.Unlock()
			return m, true, false
		}
		if r.closed {
			r.mu.Unlock()
			return message{}, false, true
		}
		r.mu.Unlock()
		select {
		case <-r.wake:
		case <-timer.C:
			return message{}, false, false
		}
	}
}

func (r *Reporter) run() {
	defer close(r.done)
	defer r.cancel()
	var tracker Tracker
	var lost *lostStop
	for {
		// Wake up every second at most, so periodic progress is sent while paused.
		m, ok, closed := r.next()
		if closed {
			return
		}
		now := time.Now()
		var reports []Report
		if ok {
			switch m.kind {
			case msgBegin:
				// A late stop must never overwrite the position of a newer playback.
				lost = nil
				reports = tracker.Begin(m.playback, now)
			case msgSwitch:
				reports = tracker.Switch(m.playback, now)
			case msgPlayer:
				reports = tracker.OnEvent(m.event, now)
			case msgFlush, msgResendStop:
				// Earlier messages were fully handled: their reports are sent.
				r.resend(&lost)
				m.ack <- struct{}{}
			}
		}
		reports = append(reports, tracker.Tick(now)...)
		if session := tracker.TakeEndedEncoding(); session != nil {
			// Only once mpv reads the new stream; a failure is only logged.
			if err := r.emby.StopEncoding(r.ctx, *session); err != nil {
				r.log.Warn("replaced transcode not stopped", "kind", kindText(err))
			}
		}
		for _, report := range reports {
			// The stop of a playback replaced by a new one stays lost, same rule as Begin.
			if stop := r.deliver(report); stop != nil && tracker.isIdle() {
				lost = stop
			}
		}
		if lost != nil && lost.schedule.due(time.Now()) {
			r.resend(&lost)
		}
	}
}

// kindText is `e.kind()` for the logs: never the message, which may quote a server.
func kindText(err error) string {
	if k, ok := KindOf(err); ok {
		return string(k)
	}
	return "unknown"
}

// deliver sends a report; returns a stop report the server did not get.
func (r *Reporter) deliver(report Report) *lostStop {
	owner, _ := r.emby.Client()
	err := r.emby.ReportPlayback(r.ctx, report)
	if report.Kind == ReportStopped && isTransient(err) {
		select {
		case <-time.After(stopRetryDelay):
		case <-r.ctx.Done():
		}
		err = r.emby.ReportPlayback(r.ctx, report)
	}
	if err != nil {
		r.log.Warn("playback report not sent", "report", report.Kind.String(), "kind", kindText(err))
	}
	if report.Kind != ReportStopped {
		return nil
	}
	r.announceState(report.Body.ItemID)
	if err != nil && isTransient(err) && owner != nil {
		return &lostStop{report: report, owner: owner, schedule: newResendSchedule(time.Now())}
	}
	return nil
}

// resend tries a lost stop report again; it stays lost only if the failure may pass.
func (r *Reporter) resend(lost **lostStop) {
	stop := *lost
	*lost = nil
	if stop == nil {
		return
	}
	if stop.schedule.expired(time.Now()) {
		r.log.Warn("stop report given up")
		return
	}
	err := r.emby.ResendStop(r.ctx, stop.owner, stop.report)
	switch {
	case err == nil:
		r.log.Info("stop report sent after the cut")
		r.announceState(stop.report.Body.ItemID)
	case isTransient(err):
		stop.schedule.postpone(time.Now())
		*lost = stop
	default:
		r.log.Warn("stop report given up", "kind", kindText(err))
	}
}

// announceState tells the UI what the server now holds for the item.
func (r *Reporter) announceState(itemID string) {
	state, err := r.emby.PlaybackState(r.ctx, itemID)
	if err != nil {
		r.log.Warn("state after playback not read", "kind", kindText(err))
		return
	}
	r.emit(PlaybackStoppedEvent, state)
}
