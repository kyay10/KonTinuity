package io.github.kyay10.kontinuity.hansei

import io.github.kyay10.kontinuity.ensure
import io.github.kyay10.kontinuity.repeatIteratorless
import io.github.kyay10.kontinuity.runTestCC
import io.github.kyay10.kontinuity.shouldEq
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import kotlin.test.Test

class SamplingTests {
  @Test
  fun `test flip with sharing sampling`() = runTestCC {
    val sharedFlip: suspend context(Probabilistic) () -> Boolean = {
      val v = flip()
      v && v
    }

    sampleRejection(random(1).selector(), 100) { sharedFlip() } shouldContainExactlyInAnyOrder listOf(
      Probable(0.5, Value.Leaf(true)),
      Probable(0.5, Value.Leaf(false))
    )

    sampleImportance(random(1).selector(), 100) { sharedFlip() } shouldContainExactlyInAnyOrder listOf(
      Probable(0.5, Value.Leaf(true)),
      Probable(0.5, Value.Leaf(false))
    )
  }

  @Test
  fun `test flip without sharing sampling`() = runTestCC {
    val independentFlips: suspend context(Probabilistic) () -> Boolean = {
      flip() && flip()
    }

    sampleRejection(random(1).selector(), 100) { independentFlips() } shouldContainExactlyInAnyOrder listOf(
      Probable(0.27, Value.Leaf(true)),
      Probable(0.73, Value.Leaf(false))
    )

    sampleImportance(random(1).selector(), 100) { independentFlips() } shouldContainExactlyInAnyOrder listOf(
      Probable(0.25, Value.Leaf(true)),
      Probable(0.75, Value.Leaf(false))
    )
  }

  @Test
  fun `test alarm model sampling`() = runTestCC {
    val alarm: suspend context(Probabilistic) () -> Boolean = {
      val earthquake = flip(0.01)
      val burglary = flip(0.1)
      if (earthquake) {
        if (burglary) flip(0.99) else flip(0.2)
      } else {
        if (burglary) flip(0.98) else flip(0.01)
      }
    }

    sampleRejection(random(1).selector(), 100) { alarm() } shouldContainExactlyInAnyOrder listOf(
      Probable(0.08, Value.Leaf(true)),
      Probable(0.92, Value.Leaf(false))
    )

    sampleImportance(random(1).selector(), 100) { alarm() } shouldContainExactlyInAnyOrder listOf(
      Probable(0.108720000000000011, Value.Leaf(true)),
      Probable(0.891280000000000072, Value.Leaf(false))
    )
  }

  /*Exact result:
    n = 0: 0.85
    n = 1: 0.85 *. 0.15         = 0.1275
    n = 2: 0.85 *. (0.15 ** 2.) = 0.019125
    n = 3: 0.85 *. (0.15 ** 3.) = 0.00286874999999999934
    n = 4: 0.85 *. (0.15 ** 4.) = 0.0004303125
    n = 5: 0.85 *. (0.15 ** 5.) = 6.45468749999999884e-05
   */
  @OptIn(ExperimentalStdlibApi::class)
  @Test
  fun `test geometric distribution sampling`() = runTestCC {
    sampleRejection(random(17).selector(), 100) { geometric(0.85) } shouldContainExactlyInAnyOrder listOf(
      Probable(0.01, Value.Leaf(3)),
      Probable(0.03, Value.Leaf(2)),
      Probable(0.15, Value.Leaf(1)),
      Probable(0.81, Value.Leaf(0))
    )

    sampleImportance(random(17).selector(), 100) { geometric(0.85) } shouldContainExactlyInAnyOrder listOf(
      Probable(0.00090000000000000019, Value.Leaf(4)),
      Probable(0.0040500000000000024, Value.Leaf(3)),
      Probable(0.017549999999999982, Value.Leaf(2)),
      Probable(0.1275, Value.Leaf(1)),
      Probable(0.85, Value.Leaf(0))
    )

    sampleImportance(random(17).selector(), 1000) { geometric(0.85) } shouldContainExactlyInAnyOrder listOf(
      Probable(0.00054000000000000022, Value.Leaf(4)),
      Probable(0.0029699999999999965, Value.Leaf(3)),
      Probable(0.018990000000000402, Value.Leaf(2)),
      Probable(0.1275, Value.Leaf(1)),
      Probable(0.85, Value.Leaf(0))
    )
  }

  @Test
  fun `test geometric bounded exploration`() = runTestCC {
    reify {
      ensure(geometric(0.85, 7) <= 3)
    }.boundedExplore(1) shouldEq 0.999494006159381776..0.999494006159381776
  }

  @Test
  fun `test foo ibl program 3`() = runTestCC {
    sampleImportance(random(1).selector(), 100) {
      val f = if (flip(0.1)) listOf(Probable(0.7, 'a'), Probable(0.3, 'b')).dist()
      else listOf(Probable(0.2, 'a'), Probable(0.8, 'b')).dist()
      ensure(f == 'a')
    } shouldContainExactlyInAnyOrder listOf(Probable(0.25, Value.Leaf(Unit)))
  }

  @Test
  fun `test ibl t2`() = runTestCC {
    context(_: Probabilistic, _: Memory)
    suspend fun iblT2(): Boolean {
      val x = listOf(
        Probable(0.01, 'a' to 'b'),
        Probable(0.02, 'a' to 'c'),
        Probable(0.97, 'd' to 'e')
      ).dist().also { ensure(it.first == 'a') }
      return when (x.second) {
        'b' -> flip(0.9)
        'c' -> flip(0.6)
        else -> flip(0.2)
      }
    }
    sampleRejection(random(1).selector(), 100) {
      iblT2()
    } shouldContainExactlyInAnyOrder listOf(Probable(0.01, Value.Leaf(true)), Probable(0.01, Value.Leaf(false)))

    sampleImportance(random(1).selector(), 100) {
      iblT2()
    } shouldContainExactlyInAnyOrder listOf(
      Probable(0.021, Value.Leaf(true)),
      Probable(0.009, Value.Leaf(false)) // should be 0.00900000000000000105
    )
  }

  @Test
  fun `test music 4-1`() = runTestCC {
    sampleImportance(random(1).selector(), 100) {
      ensure(
        listOf(
          Probable(0.8, flip(0.1)),
          Probable(0.2, flip(0.3))
        ).dist()
      )
    } shouldContainExactlyInAnyOrder listOf(
      Probable(0.14, Value.Leaf(Unit)),
    )
  }

  @Test
  fun `test music 4-2`() = runTestCC {
    sampleImportance(random(1).selector(), 1) {
      val m: Pair<Char, suspend context(Probabilistic) () -> Boolean> = if (flip(0.01)) {
        'a' to { flip(0.3) }
      } else {
        'b' to { true }
      }
      val (p, q) = m
      ensure(p == 'a')
      q()
    } shouldContainExactlyInAnyOrder listOf(
      Probable(0.003, Value.Leaf(true)),
      Probable(0.00699999999999999928, Value.Leaf(false))
    )
  }

  context(_: Probabilistic)
  suspend fun drunkCoin(): Boolean {
    val x = flip()
    ensure(!flip(0.9))
    return x
  }

  context(_: Probabilistic)
  suspend fun drunkCoinAnd(n: Int): Boolean {
    repeatIteratorless(n) {
      if (!drunkCoin()) {
        return false
      }
    }
    return true
  }

  @Test
  fun drunkCoinAndTest() = runTestCC {
    exactReify { drunkCoinAnd(10) } shouldContainExactlyInAnyOrder listOf(
      Probable(9.76562499999997764e-14, Value.Leaf(true)),
      Probable(0.0526315789473632695, Value.Leaf(false))
    )

    sampleRejection(random(17).selector(), 100) { drunkCoinAnd(10) } shouldContainExactlyInAnyOrder listOf(
      Probable(0.029999999999999999, Value.Leaf(false))
    )

    sampleRejection(random(17).selector(), 10000) { drunkCoinAnd(10) } shouldContainExactlyInAnyOrder listOf(
      Probable(0.053999999999999999, Value.Leaf(false))
    )

    sampleImportance(random(17).selector(), 100) { drunkCoinAnd(10) } shouldContainExactlyInAnyOrder listOf(
      Probable(0.052746827004999997, Value.Leaf(false)),
    )

    sampleImportance(random(1).selector(), 5000) { drunkCoinAnd(10) } shouldContainExactlyInAnyOrder listOf(
      Probable(9.9999999999999776E-14, Value.Leaf(true)),
      Probable(0.052649419387680141, Value.Leaf(false))
    )

    // Very close!
    sampleImportance(random(17).selector(), 5000) { drunkCoinAnd(10) } shouldContainExactlyInAnyOrder listOf(
      Probable(1.0999999999999977E-13, Value.Leaf(true)),
      Probable(0.052637811581150142, Value.Leaf(false))
    )
  }

  context(_: Probabilistic)
  suspend fun dCoinAndTrue(n: Int) {
    ensure(drunkCoinAnd(n))
  }

  @Test
  fun dCoinAndTrueTest() = runTestCC {
    sampleRejection(random(1).selector(), 10_000) { dCoinAndTrue(10) } shouldEq emptyList()
    reify { dCoinAndTrue(10) }.boundedExplore(1) shouldEq 0.0..1.0
    reify { dCoinAndTrue(10) }.boundedExplore(3) shouldEq 0.0..0.5 // should be 0.0499999999999999889 TODO
    reify { dCoinAndTrue(10) }.boundedExplore(5) shouldEq 9.76562499999997764e-14..9.76562499999997764e-14
  }
}