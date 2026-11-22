package emby

// The #[ignore] tests of tests.rs that need the real Emby server and credentials in
// VIEWER_EMBY_* variables. Never run here (no real server, CLAUDE.md): kept under the
// same names so the count matches; to port when the owner allows a real-server run.

import "testing"

const realServerSkip = "needs the real Emby server and VIEWER_EMBY_* variables (#[ignore] in tests.rs, not ported)"

func Test_real_server_sign_in_restore_sign_out(t *testing.T) { t.Skip(realServerSkip) }

func Test_real_server_libraries_and_pages(t *testing.T) { t.Skip(realServerSkip) }

func Test_real_server_details_seasons_episodes(t *testing.T) { t.Skip(realServerSkip) }

func Test_real_server_playback_negotiation(t *testing.T) { t.Skip(realServerSkip) }

func Test_real_server_playback_reporting_and_resume(t *testing.T) { t.Skip(realServerSkip) }

func Test_real_server_transcoding(t *testing.T) { t.Skip(realServerSkip) }

func Test_real_server_encoder_without_stop_report(t *testing.T) { t.Skip(realServerSkip) }

func Test_real_server_home_rows(t *testing.T) { t.Skip(realServerSkip) }

func Test_real_server_next_up(t *testing.T) { t.Skip(realServerSkip) }

func Test_real_server_mark_unplayed(t *testing.T) { t.Skip(realServerSkip) }

func Test_real_server_library_filters(t *testing.T) { t.Skip(realServerSkip) }

func Test_real_server_search(t *testing.T) { t.Skip(realServerSkip) }

func Test_real_server_hero(t *testing.T) { t.Skip(realServerSkip) }

func Test_real_server_episode_detail(t *testing.T) { t.Skip(realServerSkip) }
