package io.github.kyay10.kontinuity.effekt.hansei

import io.github.kyay10.kontinuity.effekt.raise
import io.github.kyay10.kontinuity.runTestCC
import io.github.kyay10.kontinuity.yieldToTrampoline
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import kotlin.test.Test

class ExactInfTest {
  @Test
  fun `test flip with sharing`() = runTestCC {
    val result = exactReify {
      val v = flip(0.5)
      v && v
    }
    result shouldContainExactlyInAnyOrder listOf(
      Probable(0.5, Value.Leaf(true)),
      Probable(0.5, Value.Leaf(false))
    )
  }

  @Test
  fun `test flip without sharing`() = runTestCC {
    val result = exactReify {
      flip(0.5) && flip(0.5)
    }
    result shouldContainExactlyInAnyOrder listOf(
      Probable(0.25, Value.Leaf(true)),
      Probable(0.75, Value.Leaf(false))
    )
  }

  @Test
  fun `test alarm model`() = runTestCC {
    val result = exactReify {
      val earthquake = flip(0.01)
      val burglary = flip(0.1)
      if (earthquake) {
        if (burglary) flip(0.99) else flip(0.2)
      } else {
        if (burglary) flip(0.98) else flip(0.01)
      }
    }
    result shouldContainExactlyInAnyOrder listOf(
      Probable(0.108720000000000011, Value.Leaf(true)),
      Probable(0.891280000000000072, Value.Leaf(false))
    )
  }

  @Test
  fun `test grass model`() = runTestCC {
    val result = exactReify {
      val rain = flip(0.3)
      val sprinkler = flip(0.5)
      val grassIsWet = (flip(0.9) && rain) || (flip(0.8) && sprinkler) || flip(0.1)
      if (!grassIsWet) raise()
      rain
    }
    result shouldContainExactlyInAnyOrder listOf(
      Probable(0.2838, Value.Leaf(true)),
      Probable(0.321999999999999897, Value.Leaf(false))
    )
  }

  @Test
  fun `test uniform range`() = runTestCC {
    val result = exactReify {
      (1..8).uniformly()
    }
    result shouldContainExactlyInAnyOrder (1..8).map {
      Probable(0.125, Value.Leaf(it))
    }
  }

  @Test
  fun `test geometric distribution`() = runTestCC {
    val result = reify {
      geometric(0.85)
    }.explore(maxDepth = 4)
    result.filter { it.value is Value.Leaf } shouldContainExactlyInAnyOrder listOf(
      Probable(0.85, Value.Leaf(0)),
      Probable(0.1275, Value.Leaf(1)),
      Probable(0.0191250000000000031, Value.Leaf(2)),
      Probable(0.00286875000000000107, Value.Leaf(3))
    )
    result.mapNotNull { it.prob.takeIf { _ -> it.value is Value.Branch } } shouldContainExactlyInAnyOrder listOf(
      1.13906250000000092e-05,
      6.45468750000000426e-05,
      0.000430312500000000248
    )
  }

  @Test
  fun `test geometric bounded`() = runTestCC {
    val result = exactReify {
      geometric(0.85, endInclusive = 7)
    }
    result shouldContainExactlyInAnyOrder listOf(
      Probable(0.850000217845759, Value.Leaf(0)),
      Probable(0.127500032676863856, Value.Leaf(1)),
      Probable(0.0191250049015295812, Value.Leaf(2)),
      Probable(0.00286875073522943743, Value.Leaf(3)),
      Probable(0.000430312610284415691, Value.Leaf(4)),
      Probable(6.45468915426623699e-05, Value.Leaf(5)),
      Probable(9.68203373139935684e-06, Value.Leaf(6)),
      Probable(1.4523050597099037e-06, Value.Leaf(7))
    )
  }

  @Test
  fun `test flips and`() = runTestCC {
    val result = exactReify {
      tailrec suspend fun loop(n: Int): Boolean {
        return if (n == 1) flip(0.5) else flip(0.5) && loop(n - 1)
      }
      loop(10)
    }
    result shouldContainExactlyInAnyOrder listOf(
      Probable(0.0009765625, Value.Leaf(true)),
      Probable(0.9990234375, Value.Leaf(false))
    )
  }

  @Test
  fun `test flips xor`() = runTestCC {
    val result = exactReify {
      suspend fun loop(n: Int): Boolean {
        return if (n == 1) flip(0.5) else flip(0.5) != loop(n - 1)
      }
      loop(10)
    }
    result shouldContainExactlyInAnyOrder listOf(
      Probable(0.5, Value.Leaf(true)),
      Probable(0.5, Value.Leaf(false))
    )
  }

  @Test
  fun `test flips xor incorrect`() = runTestCC {
    val result = exactReify {
      // doesn't use the context of the variable elimination :o
      suspend fun loop(n: Int): Boolean {
        return if (n == 1) flip(0.5) else {
          val r = variableElimination { loop(n - 1) }
          flip(0.5) != r
        }
      }
      loop(10)
    }
    result shouldContainExactlyInAnyOrder listOf(
      Probable(0.5, Value.Leaf(true)),
      Probable(0.5, Value.Leaf(false))
    )
  }

  @Test
  fun `test flips xor prime`() = runTestCC {
    val result = exactReify {
      context(_: Probabilistic)
      suspend fun loop(n: Int): Boolean {
        return if (n == 1) flip(0.5) else {
          if ((n % 500) == 2) yieldToTrampoline()
          val r = variableElimination { loop(n - 1) }
          flip(0.5) != r
        }
      }
      yieldToTrampoline()
      loop(1_000_0)
    }
    result shouldContainExactlyInAnyOrder listOf(
      Probable(0.5, Value.Leaf(true)),
      Probable(0.5, Value.Leaf(false))
    )
  }
}