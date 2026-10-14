package io.github.mangi.eta.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 移植自上游 213e8ed6：围栏代码块内的空行曾让 normalize() 的扫描游标不前进而进入死循环。
 * 用例带超时，回归时表现为测试挂起而不是失败。
 */
class TexMathDelimitersTest {
    @Test(timeout = 1_000)
    fun fencedBlankLinesPreserveCodeAndNormalizeSurroundingMath() {
        listOf("```", "~~~").forEach { fence ->
            listOf("\n", "\r\n").forEach { newline ->
                val code = listOf("\\(a_i\\)", "", "", " ", "\t", "\\[", "b_j", "\\]")
                    .joinToString(newline)
                val fenced = "${fence}latex$newline$code$newline$fence"
                val source = "\\(before\\)$newline$fenced$newline\\[after\\]"
                val expected = "\$\$before\$\$$newline$fenced$newline\$\$after\$\$"

                assertEquals(fenced, TexMathDelimiters.normalize(fenced))
                assertEquals(expected, TexMathDelimiters.normalize(source))
            }
        }
    }

    @Test(timeout = 1_000)
    fun unclosedFencesPreserveBlankLinesThroughEof() {
        listOf("```", "~~~").forEach { fence ->
            listOf("", "\n", "\n\n").forEach { ending ->
                val source = "${fence}latex\n\n\n\\(literal\\)$ending"

                assertEquals(source, TexMathDelimiters.normalize(source))
            }
        }
    }

    @Test(timeout = 1_000)
    fun shortOrMismatchedFencesDoNotExposeCodeToMathNormalization() {
        listOf("````" to "~~~", "~~~~" to "```").forEach { (fence, otherFence) ->
            val code = "${fence}latex\n\n${fence.dropLast(1)}\n\\(a_i\\)\n\n" +
                "$otherFence\n\\[b_j\\]\n\n$fence${fence.first()}"
            val source = "$code\n\\(after\\)"

            assertEquals("$code\n\$\$after\$\$", TexMathDelimiters.normalize(source))
        }
    }
}
