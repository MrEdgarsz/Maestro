package maestro.cli.session

import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import maestro.Maestro
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class IsolatedWebSessionTest {
    @BeforeEach
    fun setUp() = mockkObject(Maestro.Companion)

    @AfterEach
    fun tearDown() = unmockkObject(Maestro.Companion)

    private fun runSession(block: (MaestroSessionManager.MaestroSession) -> Unit) =
        MaestroSessionManager.newSession(
            host = null, port = null, driverHostPort = null, deviceId = null,
            platform = "web", isHeadless = true, closeOnCompletion = true, block = block,
        )

    @Test
    fun `initialization failure is fatal before request block runs`() {
        every { Maestro.web(false, true, null) } throws IllegalStateException("initialization failed")
        assertThrows(SessionCleanupException::class.java) {
            runSession { error("must not execute") }
        }
    }

    @Test
    fun `flow failure still closes the browser exactly once`() {
        val maestro = mockk<Maestro>(relaxed = true)
        every { Maestro.web(false, true, null) } returns maestro
        assertThrows(IllegalArgumentException::class.java) {
            runSession { throw IllegalArgumentException("flow failure") }
        }
        verify(exactly = 1) { maestro.close() }
    }

    @Test
    fun `failed cleanup is fatal to the resident caller`() {
        val maestro = mockk<Maestro>(relaxed = true)
        every { Maestro.web(false, true, null) } returns maestro
        every { maestro.close() } throws IllegalStateException("quit failed")
        assertThrows(SessionCleanupException::class.java) { runSession { } }
        verify(exactly = 1) { maestro.close() }
    }
}
