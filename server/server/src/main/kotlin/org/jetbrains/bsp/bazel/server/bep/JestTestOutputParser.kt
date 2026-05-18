package org.jetbrains.bsp.bazel.server.bep

import ch.epfl.scala.bsp4j.TaskId
import ch.epfl.scala.bsp4j.TestStatus
import org.jetbrains.bsp.bazel.logger.BspClientTestNotifier
import org.jetbrains.bsp.protocol.JUnitStyleTestCaseData
import java.util.UUID

class JestTestOutputParser(private val bspClientTestNotifier: BspClientTestNotifier) {
  private val suiteStack = ArrayDeque<JestSuite>()

  fun processTestOutput(output: String) {
    output.lines().forEach { rawLine ->
      val line = rawLine.removeJestFormat().trimEnd()
      val testLine = parseTestLine(line)
      if (testLine != null) {
        emitTest(testLine)
        return@forEach
      }

      parseSuiteLine(line)?.let { suite ->
        while (suiteStack.isNotEmpty() && suite.indent <= suiteStack.last().indent) {
          suiteStack.removeLast()
        }
        suiteStack.addLast(suite)
      }
    }
  }

  private fun emitTest(testLine: JestTestLine) {
    val activeSuites = suiteStack.filter { it.indent < testLine.indent }.map { it.name }
    val lookupKey = (activeSuites + testLine.name).joinToString(" ")
    val taskId = TaskId("test-" + UUID.randomUUID().toString())
    val testCaseData =
      JUnitStyleTestCaseData(
        time = null,
        className = lookupKey,
        errorMessage = null,
        errorContent = null,
        errorType = null,
      )

    bspClientTestNotifier.startTest(testLine.name, taskId)
    bspClientTestNotifier.finishTest(
      displayName = testLine.name,
      taskId = taskId,
      status = testLine.status,
      message = null,
      dataKind = JUnitStyleTestCaseData.DATA_KIND,
      data = testCaseData,
    )
  }

  private fun parseTestLine(line: String): JestTestLine? {
    val match = testLinePattern.matchEntire(line) ?: return null
    val status =
      when (match.groups["status"]?.value) {
        "✓", "✔", "√" -> TestStatus.PASSED
        "✕", "✖", "×" -> TestStatus.FAILED
        "○", "↷" -> TestStatus.SKIPPED
        else -> return null
      }
    return JestTestLine(
      indent = match.groups["indent"]?.value?.length ?: 0,
      status = status,
      name =
        match.groups["name"]
          ?.value
          ?.trim()
          .orEmpty(),
    )
  }

  private fun parseSuiteLine(line: String): JestSuite? {
    val match = suiteLinePattern.matchEntire(line) ?: return null
    return JestSuite(
      indent = match.groups["indent"]?.value?.length ?: 0,
      name =
        match.groups["name"]
          ?.value
          ?.trim()
          .orEmpty(),
    )
  }

  companion object {
    fun textContainsJestOutput(text: String): Boolean {
      val cleanText = text.removeJestFormat()
      return cleanText.contains("Test Suites:") &&
        cleanText.contains("Tests:") &&
        cleanText.lines().any { testLinePattern.matches(it.trimEnd()) }
    }
  }
}

private val testLinePattern =
  Regex("""^(?<indent>\s*)(?<status>[✓✔√✕✖×○↷])\s+(?<name>.+?)(?:\s+\(\d+(?:\.\d+)?\s*(?:m?s|μs|ns)\))?$""")

private val suiteLinePattern =
  Regex("""^(?<indent>\s{2,})(?<name>(?!(?:Test Suites|Tests|Snapshots|Time|Ran all test suites|Force exiting Jest):)\S.*)$""")

private fun String.removeJestFormat(): String = this.replace(Regex("[?\u001b]\\[[;\\d]*m"), "")

private data class JestSuite(val indent: Int, val name: String)

private data class JestTestLine(
  val indent: Int,
  val status: TestStatus,
  val name: String,
)
