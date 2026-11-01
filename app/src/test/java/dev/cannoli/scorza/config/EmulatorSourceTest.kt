package dev.cannoli.scorza.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EmulatorSourceTest {
    @Test fun `fromRunnerLabel maps known labels`() {
        assertEquals(EmulatorSource.Standalone, EmulatorSource.fromRunnerLabel("Standalone"))
        assertEquals(EmulatorSource.Standalone, EmulatorSource.fromRunnerLabel("App"))
        assertNull(EmulatorSource.fromRunnerLabel(null))
        assertNull(EmulatorSource.fromRunnerLabel(""))
    }

    @Test fun `fromRunnerLabel treats any other label as RetroArch`() {
        assertEquals(EmulatorSource.RetroArch, EmulatorSource.fromRunnerLabel("RetroArch"))
        assertEquals(EmulatorSource.RetroArch, EmulatorSource.fromRunnerLabel("RetroArch Plus"))
        // The built-in runner is gone, so its old label lands on RetroArch with the same core.
        assertEquals(EmulatorSource.RetroArch, EmulatorSource.fromRunnerLabel("Internal"))
    }

    @Test fun `fromStoredName maps the removed Internal source to RetroArch`() {
        assertEquals(EmulatorSource.RetroArch, EmulatorSource.fromStoredName("Internal"))
        assertEquals(EmulatorSource.RetroArch, EmulatorSource.fromStoredName("RetroArch"))
        assertEquals(EmulatorSource.Standalone, EmulatorSource.fromStoredName("Standalone"))
        assertNull(EmulatorSource.fromStoredName("Bogus"))
        assertNull(EmulatorSource.fromStoredName(null))
    }
}
