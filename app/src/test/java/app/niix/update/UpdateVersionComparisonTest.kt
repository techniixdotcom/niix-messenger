package app.niix.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the version check that gates updates. Every release is properly signed, so the
 * signature says nothing about freshness; this comparison is what stops a rollback to an older,
 * vulnerable version. A wrong answer here fails silently.
 */
class UpdateVersionComparisonTest {

    private fun newer(offered: String, current: String): Boolean =
        UpdateChecker.isStrictlyNewer(offered, current)

    @Test
    fun `a later patch version is newer`() {
        assertTrue(newer("0.8.9", "0.8.8"))
    }

    @Test
    fun `double-digit patch versions compare numerically, not as text`() {
        // As text "0.8.10" sorts before "0.8.9", but it's the later release.
        assertTrue(newer("0.8.10", "0.8.9"))
        assertFalse(newer("0.8.9", "0.8.10"))
    }

    @Test
    fun `an identical version is not newer`() {
        assertFalse(newer("0.8.8", "0.8.8"))
    }

    @Test
    fun `an earlier version is refused`() {
        assertFalse(newer("0.8.7", "0.8.8"))
        assertFalse(newer("0.7.0", "0.8.0"))
    }

    @Test
    fun `a later minor beats a much later patch`() {
        assertTrue(newer("0.9.0", "0.8.99"))
        assertFalse(newer("0.8.99", "0.9.0"))
    }

    @Test
    fun `a v prefix is tolerated on either side`() {
        // Release tags have it; version.properties doesn't.
        assertTrue(newer("v0.9.0", "0.8.9"))
        assertTrue(newer("0.9.0", "v0.8.9"))
    }

    @Test
    fun `differing segment counts compare as if padded with zeros`() {
        assertTrue(newer("1.0", "0.9.9"))
        assertFalse(newer("1.0", "1.0.0"))
        assertTrue(newer("1.0.1", "1.0"))
    }

    @Test
    fun `unparseable versions are treated as not newer`() {
        // Fail closed: refusing an update can be fixed, accepting a downgrade can't.
        assertFalse(newer("garbage", "0.8.8"))
        assertFalse(newer("0.8.9", "garbage"))
        assertFalse(newer("", "0.8.8"))
        assertFalse(newer("0.8.x", "0.8.8"))
        assertFalse(newer("0.-1.0", "0.8.8"))
    }

    @Test
    fun `whitespace around a tag does not defeat the comparison`() {
        assertTrue(newer("  0.9.0  ", "0.8.9"))
    }
}
