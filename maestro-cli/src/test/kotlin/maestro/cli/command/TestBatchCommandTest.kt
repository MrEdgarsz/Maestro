package maestro.cli.command

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import picocli.CommandLine
import java.io.PrintWriter
import java.io.StringWriter
import java.util.concurrent.CancellationException

class TestBatchCommandTest {
    @Test
    fun `batch rejects modes that can outlive one isolated request`() {
        for (args in listOf(
            listOf("--continuous", "/tmp/flow.yaml"),
            listOf("--shard-all", "2", "/tmp/flow.yaml"),
            listOf("--platform=ios", "/tmp/flow.yaml"),
            listOf("relative.yaml"),
        )) {
            val command = TestCommand().apply { batchMode = true }
            val cli = CommandLine(command).setErr(PrintWriter(StringWriter()))
            assertThat(cli.execute(*args.toTypedArray())).isNotEqualTo(0)
        }
    }

    @Test
    fun `requests preserve args and independent failure codes`() {
        val input = """
            {"id":"first","args":["-e","TOKEN=synthetic value","/tmp/first.yaml"]}
            {"id":"second","args":["--screen-size","800x600","/tmp/second.yaml"]}
        """.trimIndent().reader().buffered()
        val output = StringWriter()
        val seen = mutableListOf<List<String>>()
        assertThat(runTestBatch(input, PrintWriter(output)) { args ->
            seen.add(args)
            if (seen.size == 1) 1 else 0
        }).isEqualTo(0)
        assertThat(seen).containsExactly(
            listOf("-e", "TOKEN=synthetic value", "/tmp/first.yaml"),
            listOf("--screen-size", "800x600", "/tmp/second.yaml"),
        ).inOrder()
        assertThat(output.toString().lineSequence().filter { it.isNotEmpty() }.map {
            jacksonObjectMapper().readTree(it)["exitCode"].asInt()
        }.toList()).containsExactly(1, 0).inOrder()
    }

    @Test
    fun `malformed requests return usage failure without executing`() {
        val output = StringWriter()
        val input = """
            not json
            {"id":"bad","args":[1]}
            {"id":"good","args":["/tmp/flow.yaml"]}
        """.trimIndent().reader().buffered()
        var calls = 0
        runTestBatch(input, PrintWriter(output)) { calls++; 0 }
        assertThat(calls).isEqualTo(1)
        assertThat(output.toString().lines().filter { it.isNotEmpty() }.map {
            jacksonObjectMapper().readTree(it)["exitCode"].asInt()
        }).containsExactly(2, 2, 0).inOrder()
    }

    @Test
    fun `cancellation stops before another request or completion reply`() {
        val output = StringWriter()
        val input = """
            {"id":"first","args":[]}
            {"id":"second","args":[]}
        """.trimIndent().reader().buffered()
        var calls = 0
        assertThrows(CancellationException::class.java) {
            runTestBatch(input, PrintWriter(output)) { calls++; throw CancellationException() }
        }
        assertThat(calls).isEqualTo(1)
        assertThat(output.toString()).isEmpty()
    }
}
