package app.niix.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DiagnosticLogTest {

    @Before
    fun reset() {
 // The log is a singleton shared across tests, so each starts from empty. Without this a
 // test would pass or fail depending on what ran before it.
        DiagnosticLog.clear()
    }

    @Test
    fun `records an event`() {
        DiagnosticLog.record("send", "queued on relay")
        val entries = DiagnosticLog.snapshot()
        assertEquals(1, entries.size)
        assertEquals("send", entries[0].area)
        assertEquals("queued on relay", entries[0].message)
    }

    @Test
    fun `keeps events in the order they happened`() {
        DiagnosticLog.record("a", "first")
        DiagnosticLog.record("b", "second")
        DiagnosticLog.record("c", "third")
        assertEquals(listOf("first", "second", "third"), DiagnosticLog.snapshot().map { it.message })
    }

    @Test
    fun `is bounded and drops the oldest entries`() {
 // The bound is what stops a long-running app accumulating an indefinite history of its
 // own activity in memory.
        repeat(500) { DiagnosticLog.record("test", "event $it") }
        val entries = DiagnosticLog.snapshot()
        assertEquals(200, entries.size)
        assertEquals("event 300", entries.first().message)
        assertEquals("event 499", entries.last().message)
    }

    @Test
    fun `clear removes everything`() {
        repeat(50) { DiagnosticLog.record("test", "event $it") }
        DiagnosticLog.clear()
        assertTrue(DiagnosticLog.snapshot().isEmpty())
    }

    @Test
    fun `clearing leaves nothing recoverable through render`() {
 // snapshot() and render() read the same store, but a future change could reasonably add
 // a separate buffer for rendering, this fails if that ever leaves data behind.
        DiagnosticLog.record("receive", "dropped: sender is blocked")
        DiagnosticLog.clear()
        val rendered = DiagnosticLog.render()
        assertFalse(rendered.contains("blocked"))
        assertFalse(rendered.contains("receive"))
    }

    @Test
    fun `render says so plainly when empty rather than returning nothing`() {
        assertEquals("No diagnostic events recorded.", DiagnosticLog.render())
    }

    @Test
    fun `render uses relative ages rather than absolute timestamps`() {
 // Absolute times would turn an exported log into a timeline of exactly when the device
 // was in use, which is more than the log needs to convey to be useful.
        DiagnosticLog.record("tor", "connected")
        val rendered = DiagnosticLog.render()
        assertTrue("expected a relative age", rendered.contains("s ago]"))
        assertTrue(rendered.contains("tor"))
    }

    @Test
    fun `a snapshot is not a live view of the log`() {
 // Callers iterate snapshots while the app keeps recording; a live view would risk
 // concurrent modification, and worse, could expose entries recorded after a clear.
        DiagnosticLog.record("a", "one")
        val snapshot = DiagnosticLog.snapshot()
        DiagnosticLog.record("b", "two")
        assertEquals(1, snapshot.size)
    }

    @Test
    fun `handles an empty message without losing the entry`() {
        DiagnosticLog.record("area", "")
        assertEquals(1, DiagnosticLog.snapshot().size)
    }

    @Test
    fun `stays bounded under sustained recording`() {
        repeat(5_000) { DiagnosticLog.record("flood", "entry $it") }
        assertEquals(200, DiagnosticLog.snapshot().size)
    }
}
