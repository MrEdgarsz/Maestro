package maestro.cli.api

import com.google.common.truth.Truth.assertThat
import maestro.cli.util.EnvUtils
import org.junit.jupiter.api.Test

class CliVersionTest {
    @Test
    fun `release resource loads as a CLI version`() {
        assertThat(EnvUtils.CLI_VERSION).isNotNull()
        assertThat(EnvUtils.CLI_VERSION.toString()).isEqualTo("2.11.0-revento.1")
    }

    @Test
    fun `parse preserves release qualifier while comparisons use numeric version`() {
        val release = requireNotNull(CliVersion.parse("2.11.0-revento.1"))

        assertThat(release).isNotNull()
        assertThat(release.toString()).isEqualTo("2.11.0-revento.1")
        assertThat(release).isGreaterThan(CliVersion.parse("2.10.99"))
        assertThat(release.compareTo(requireNotNull(CliVersion.parse("2.11.0")))).isEqualTo(0)
    }

    @Test
    fun `parse accepts stock versions and rejects malformed versions`() {
        assertThat(CliVersion.parse("2.11.0").toString()).isEqualTo("2.11.0")
        assertThat(CliVersion.parse("2.11")).isNull()
        assertThat(CliVersion.parse("2.11.0-revento.")).isNull()
        assertThat(CliVersion.parse("02.11.0")).isNull()
        assertThat(CliVersion.parse("2.x.0")).isNull()
    }
}
