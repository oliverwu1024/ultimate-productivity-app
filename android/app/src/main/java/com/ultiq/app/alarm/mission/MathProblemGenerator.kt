package com.ultiq.app.alarm.mission

import kotlin.random.Random

/**
 * In-process generator for the §8.7 math dismiss mission. Guarantees:
 * - Every answer is a non-negative integer.
 * - Hard-mode division is always clean (operand pre-multiplied by divisor).
 * - Display strings only use the digits 0–9 and the operators + − × ÷ ( ).
 *
 * Stateless — pass a seeded [Random] in tests for reproducibility.
 */
object MathProblemGenerator {

    fun generate(difficulty: MathDifficulty, random: Random = Random.Default): MathProblem =
        when (difficulty) {
            MathDifficulty.EASY -> easy(random)
            MathDifficulty.MEDIUM -> medium(random)
            MathDifficulty.HARD -> hard(random)
        }

    private fun easy(r: Random): MathProblem {
        val a = r.nextInt(2, 10)
        val b = r.nextInt(2, 10)
        return if (r.nextBoolean()) {
            MathProblem("$a + $b", a + b)
        } else {
            val (x, y) = if (a >= b) a to b else b to a
            MathProblem("$x − $y", x - y)
        }
    }

    private fun medium(r: Random): MathProblem {
        val a = r.nextInt(11, 50)
        val b = r.nextInt(11, 30)
        val c = r.nextInt(2, 6)
        return when (r.nextInt(4)) {
            0 -> MathProblem("$a + $b − $c", a + b - c)
            1 -> MathProblem("($a + $b) − $c", a + b - c)
            2 -> {
                // §H3: cap b so a*c − b is never negative. Keypad has no
                // minus sign — a negative answer would be unsolvable.
                val safeB = r.nextInt(11, (a * c).coerceAtMost(30).coerceAtLeast(12))
                MathProblem("$a × $c − $safeB", a * c - safeB)
            }
            else -> MathProblem("$a + $b × $c", a + b * c)
        }
    }

    private fun hard(r: Random): MathProblem {
        val a = r.nextInt(100, 500)
        val b = r.nextInt(10, 30)
        val c = r.nextInt(2, 8)
        val d = r.nextInt(2, 8)
        return when (r.nextInt(3)) {
            0 -> MathProblem("$a − $b + $c × $d", a - b + c * d)
            1 -> {
                // Pre-multiply so the division is clean.
                val divisible = (a / c) * c
                MathProblem("$divisible ÷ $c + $b", divisible / c + b)
            }
            else -> MathProblem("($a − $b) × $c", (a - b) * c)
        }
    }
}
