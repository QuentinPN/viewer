package io.github.quentinpn.viewer.tv.sync

import io.github.quentinpn.viewer.tv.emby.SessionInfoDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val TV = "tv-self"
private const val PC = "pc-1"

/** The follower on a fake monotonic clock. */
class FollowerTest {
    private var now = 1_000L
    private var links = 0
    private val follower = Follower(newLink = { "link-${++links}" }, nameOf = { if (it == PC) "Bureau" else "Autre" }).also { it.self = TV }
    private var peerSeq = 0L

    private fun from(peer: String, link: String, body: Body) = Envelope(peer, link, peerSeq++, body)

    private fun sent(effects: List<Effect>): List<Body> = effects.filterIsInstance<Effect.Send>().map { it.envelope.body }

    private fun load(itemId: String = "item-1", positionMs: Long = 60_000) = Body.Load(itemId, null, positionMs, 1, 3)

    private fun state(positionMs: Long = 60_000, paused: Boolean = false) = Body.State("item-1", positionMs, paused, false)

    /** join → load → ready: linked, on the link the TV drew. */
    private fun linked(): String {
        follower.join(PC, now)
        val link = "link-$links"
        val effects = follower.receive(from(PC, link, load()), now)
        assertEquals(listOf(Effect.Load(load())), effects)
        now += 500
        assertEquals(listOf(Body.Ready("item-1", 60_000)), sent(follower.ready("item-1", 60_000, now)))
        assertTrue(follower.linked)
        return link
    }

    @Test
    fun joinLoadReadyLinks() {
        val effects = follower.join(PC, now)
        assertEquals(listOf<Body>(Body.Join), sent(effects))
        assertEquals(PC, (effects.single() as Effect.Send).to)
        assertTrue(follower.phase is Phase.Joining)
        linked()
        assertEquals(Phase.Linked(PC), follower.phase)
    }

    @Test
    fun nothingWithoutOwnSession() {
        val f = Follower({ "l" }, { it })
        assertTrue(f.join(PC, now).isEmpty())
        assertTrue(f.receive(Envelope(PC, "l", 0, load()), now).isEmpty())
    }

    @Test
    fun joinTimesOutAfterTenSeconds() {
        follower.join(PC, now)
        now += JOIN_TIMEOUT_MS - 1
        assertTrue(follower.tick(now).isEmpty())
        now += 1
        val effects = follower.tick(now)
        assertEquals(listOf<Body>(Body.Bye(ByeReason.Timeout)), sent(effects))
        assertTrue(Effect.Notice(noAnswerMessage("Bureau")) in effects)
        assertEquals(Phase.Idle, follower.phase)
    }

    @Test
    fun byeIdleAndBusyWhileJoining() {
        follower.join(PC, now)
        assertEquals(listOf(Effect.Notice(IDLE_MESSAGE), Effect.Unlinked), follower.receive(from(PC, "link-1", Body.Bye(ByeReason.Idle)), now))
        assertEquals(Phase.Idle, follower.phase)
        follower.join(PC, now)
        assertEquals(listOf(Effect.Notice(busyMessage("Bureau")), Effect.Unlinked), follower.receive(from(PC, "link-2", Body.Bye(ByeReason.Busy)), now))
        assertEquals(Phase.Idle, follower.phase)
    }

    @Test
    fun loadWhileIdleIsAccepted() {
        val effects = follower.receive(from(PC, "pc-link", load()), now)
        assertEquals(listOf(Effect.Load(load())), effects)
        assertTrue(follower.phase is Phase.Loading)
        val ready = follower.ready("item-1", 60_000, now).single() as Effect.Send
        assertEquals("pc-link", ready.envelope.link)
        assertTrue(follower.linked)
    }

    @Test
    fun anotherDesktopGetsBusy() {
        linked()
        val effects = follower.receive(from("pc-2", "other", load()), now)
        val bye = effects.single() as Effect.Send
        assertEquals("pc-2", bye.to)
        assertEquals("other", bye.envelope.link)
        assertEquals(Body.Bye(ByeReason.Busy), bye.envelope.body)
        assertTrue(follower.linked)
    }

    @Test
    fun pingsEverySecondAndMeasuresRtt() {
        linked()
        follower.driftMs = -12
        val first = sent(follower.tick(now))
        assertEquals(listOf<Body>(Body.Ping(now, -12)), first)
        val pingAt = now
        now += 999
        assertTrue(follower.tick(now).isEmpty())
        now += 1
        assertEquals(1, sent(follower.tick(now)).size)
        follower.receive(from(PC, "link-1", Body.Pong(pingAt)), now)
        assertEquals(1_000, follower.rttMs)
        // A pong from the future is not sampled
        follower.receive(from(PC, "link-1", Body.Pong(now + 10)), now)
        assertEquals(1_000, follower.rttMs)
    }

    @Test
    fun noDriftInPingsBeforeTheLink() {
        follower.receive(from(PC, "pc-link", load()), now)
        follower.driftMs = 30
        assertEquals(listOf<Body>(Body.Ping(now, null)), sent(follower.tick(now)))
    }

    @Test
    fun linkIsLostAfterFiveSecondsOfSilence() {
        val link = linked()
        now += LOSS_MS - 1
        follower.receive(from(PC, link, state()), now)
        now += LOSS_MS - 1
        assertTrue(follower.tick(now).none { it is Effect.Notice })
        now += 1
        val effects = follower.tick(now)
        assertEquals(listOf(Effect.Notice(lostMessage("Bureau")), Effect.Unlinked), effects)
        assertEquals(Phase.Idle, follower.phase)
    }

    @Test
    fun loadTimesOutWithoutReady() {
        follower.receive(from(PC, "pc-link", load()), now)
        // The desktop keeps the link alive meanwhile
        repeat(5) {
            now += LOAD_TIMEOUT_MS / 6
            follower.receive(from(PC, "pc-link", Body.Pong(now)), now)
        }
        now += LOAD_TIMEOUT_MS / 6
        val effects = follower.tick(now)
        assertEquals(listOf<Body>(Body.Bye(ByeReason.Timeout)), sent(effects))
        assertTrue(Effect.Notice(lostMessage("Bureau")) in effects)
    }

    @Test
    fun everyUserActionIsSent() {
        linked()
        assertEquals(listOf<Body>(Body.Cmd(CmdAction.Pause, null)), sent(follower.command(CmdAction.Pause)))
        assertEquals(listOf<Body>(Body.Cmd(CmdAction.Play, null)), sent(follower.command(CmdAction.Play)))
        // ±10 s, held seek, chapter and skip-intro: all absolute positions
        for (at in listOf(70_000L, 120_000L, 0L, 92_500L)) {
            assertEquals(listOf<Body>(Body.Cmd(CmdAction.Seek, at)), sent(follower.command(CmdAction.Seek, at)))
        }
        assertEquals(listOf<Body>(Body.Cmd(CmdAction.Seek, 0)), sent(follower.command(CmdAction.Seek, -5)))
        assertTrue(follower.command(CmdAction.Seek, null).isEmpty())
        assertEquals(listOf<Body>(Body.Cmd(CmdAction.Next, null)), sent(follower.command(CmdAction.Next)))
        assertEquals(listOf<Body>(Body.Cmd(CmdAction.Previous, null)), sent(follower.command(CmdAction.Previous)))
    }

    @Test
    fun commandsOnlyInALink() {
        assertTrue(follower.command(CmdAction.Pause).isEmpty())
        assertTrue(follower.buffering(true).isEmpty())
    }

    @Test
    fun bufferingOnAndOff() {
        linked()
        assertEquals(listOf<Body>(Body.Buffering(true)), sent(follower.buffering(true)))
        assertEquals(listOf<Body>(Body.Buffering(false)), sent(follower.buffering(false)))
    }

    @Test
    fun nextEpisodeReloadsOnTheSameLink() {
        val link = linked()
        val next = load(itemId = "item-2", positionMs = 0)
        assertEquals(listOf(Effect.Load(next)), follower.receive(from(PC, link, next), now))
        assertTrue(follower.phase is Phase.Loading)
        // A `ready` for the old title is not the answer
        assertTrue(follower.ready("item-1", 0, now).isEmpty())
        val ready = follower.ready("item-2", 0, now).single() as Effect.Send
        assertEquals(link, ready.envelope.link)
        assertTrue(follower.linked)
    }

    @Test
    fun byeInALink() {
        var link = linked()
        assertEquals(listOf(Effect.Close, Effect.Unlinked), follower.receive(from(PC, link, Body.Bye(ByeReason.Stopped)), now))
        link = linked()
        assertEquals(listOf(Effect.Unlinked), follower.receive(from(PC, link, Body.Bye(ByeReason.Left)), now))
        assertEquals(Phase.Idle, follower.phase)
        link = linked()
        assertEquals(listOf(Effect.Notice(lostMessage("Bureau")), Effect.Unlinked), follower.receive(from(PC, link, Body.Bye(ByeReason.Timeout)), now))
    }

    @Test
    fun unlinkAndStopSendTheirBye() {
        linked()
        assertEquals(listOf<Body>(Body.Bye(ByeReason.Left)), sent(follower.unlink()))
        assertEquals(Phase.Idle, follower.phase)
        assertTrue(follower.unlink().isEmpty())
        linked()
        assertEquals(listOf<Body>(Body.Bye(ByeReason.Stopped)), sent(follower.stopped()))
    }

    @Test
    fun seqGrowsAndStaleMessagesAreDropped() {
        val link = linked()
        follower.receive(Envelope(PC, link, 100, state(70_000)), now)
        follower.receive(Envelope(PC, link, 99, state(10_000)), now)
        assertEquals(70_000L, follower.leader?.state?.positionMs)
        val seqs = listOf(follower.command(CmdAction.Pause), follower.command(CmdAction.Play)).map { (it.single() as Effect.Send).envelope.seq }
        assertTrue(seqs[1] > seqs[0])
    }

    @Test
    fun targetProjectsTheLeaderWithTheOffset() {
        val link = linked()
        assertNull(follower.target(now, 0))
        follower.receive(from(PC, link, state(60_000)), now)
        now += 1_000
        assertEquals(61_000L - 300, follower.target(now, 300))
    }

    @Test
    fun aLateStateDoesNotPullTheTargetBack() {
        val link = linked()
        follower.receive(from(PC, link, state(60_000)), now)
        // One second later, but 300 ms late on the relay
        now += 1_300
        follower.receive(from(PC, link, state(61_000)), now)
        assertEquals(61_300L, follower.target(now, 0))
        // The leader went back 10 s: followed at once
        now += 1_000
        follower.receive(from(PC, link, state(52_000)), now)
        assertEquals(52_000L, follower.target(now, 0))
        // A pause forgets the anchors: the paused position is the target
        follower.receive(from(PC, link, state(52_100, paused = true)), now)
        assertEquals(52_100L, follower.target(now, 0))
    }

    @Test
    fun theSeekLeadLearnsFromTheLanding() {
        assertEquals(1_200L, nextSeekLead(0, -1_200))
        assertEquals(1_000L, nextSeekLead(1_200, 200))
        assertEquals(0L, nextSeekLead(100, 400))
        assertEquals(3_000L, nextSeekLead(2_500, -900))
    }

    @Test
    fun keepAnchorKeepsTheLastFewUnlessTheLeaderWentBack() {
        assertEquals(listOf(5L), keepAnchor(emptyList(), 5))
        assertEquals(listOf(2L, 3L, 4L, 5L, 6L), keepAnchor(listOf(1, 2, 3, 4, 5), 6))
        assertEquals(listOf(100L, 0L), keepAnchor(listOf(100), 0))
        assertEquals(listOf(-901L), keepAnchor(listOf(100), -901))
    }

    @Test
    fun everyEndOfALinkSaysUnlinked() {
        // Join timeout, load timeout, « Délier », stop: Unlinked comes last each time
        follower.join(PC, now)
        now += JOIN_TIMEOUT_MS
        assertEquals(Effect.Unlinked, follower.tick(now).last())
        follower.receive(from(PC, "pc-link", load()), now)
        now += LOAD_TIMEOUT_MS
        assertEquals(Effect.Unlinked, follower.tick(now).last())
        linked()
        assertEquals(Effect.Unlinked, follower.unlink().last())
        linked()
        assertEquals(Effect.Unlinked, follower.stopped().last())
        // Not while a link goes on, nor when there is none
        val link = linked()
        assertTrue(Effect.Unlinked !in follower.receive(from(PC, link, state()), now))
        assertTrue(Effect.Unlinked !in follower.tick(now))
        follower.unlink()
        assertTrue(follower.unlink().isEmpty())
    }

    @Test
    fun offsetBounds() {
        assertEquals(0, offeredOffset(-100))
        assertEquals(0, offeredOffset(0))
        assertEquals(700, offeredOffset(750))
        assertEquals(1_500, offeredOffset(1_500))
        assertEquals(1_500, offeredOffset(9_999))
        assertEquals(100, nextOffset(0))
        assertEquals(1_500, nextOffset(1_400))
        assertEquals(0, nextOffset(1_500))
    }

    @Test
    fun pill() {
        assertEquals("Lié à Bureau", linkPill("Bureau", null))
        assertEquals("Lié à Bureau · ±40 ms", linkPill("Bureau", -40))
    }

    @Test
    fun relayDevicesAndFrames() {
        val sessions = listOf(
            SessionInfoDto("s-tv", deviceId = "dev-tv", deviceName = "Shield", client = "Viewer TV", supportsRemoteControl = true),
            SessionInfoDto("s-pc", deviceId = "dev-pc", deviceName = "Bureau", client = DESKTOP_CLIENT, supportsRemoteControl = true),
            SessionInfoDto("s-pc2", deviceId = "dev-pc2", deviceName = "", client = DESKTOP_CLIENT, supportsRemoteControl = true),
            SessionInfoDto("s-off", deviceId = "dev-off", deviceName = "Portable", client = DESKTOP_CLIENT, supportsRemoteControl = false),
            SessionInfoDto("s-web", deviceId = "dev-web", deviceName = "Chrome", client = "Emby Web", supportsRemoteControl = true),
        )
        assertEquals("s-tv", ownSessionId(sessions, "dev-tv"))
        assertEquals(listOf(Device("s-pc", "Bureau"), Device("s-pc2", "L'ordi")), desktops(sessions, "dev-tv"))
        assertEquals(
            "abc",
            sendStringOf("""{"MessageType":"GeneralCommand","Data":{"Name":"SendString","Arguments":{"String":"abc"}}}"""),
        )
        assertNull(sendStringOf("""{"MessageType":"GeneralCommand","Data":{"Name":"DisplayMessage","Arguments":{"String":"abc"}}}"""))
        assertNull(sendStringOf("""{"MessageType":"Playstate","Data":{"Command":"Pause"}}"""))
        assertNull(sendStringOf("not json"))
    }
}
