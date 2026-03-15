package io.github.kyay10.kontinuity.effekt.hansei

import io.github.kyay10.kontinuity.RequiresMultishot
import io.github.kyay10.kontinuity.effekt.raise
import io.github.kyay10.kontinuity.runTestCC
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import kotlin.test.Test

private fun SearchTree<Boolean>.atLeast(prob: Prob, v: Boolean): Boolean {
  return (find { it.value == Value.Leaf(v) }?.prob ?: 0.0) >= prob
}

@RequiresMultishot
class PaperExamples {
  @Test
  fun `test extended grass model exact inference`() = runTestCC {
    val t1exact = exactReify {
      val cloudy = flip(0.5)
      val rain = flip(if (cloudy) 0.8 else 0.2)
      val sprinkler = flip(if (cloudy) 0.1 else 0.5)
      val wetRoof = flip(0.7) && rain
      val wetGrass = (flip(0.9) && rain) || (flip(0.9) && sprinkler)
      if (wetGrass) rain else raise()
    }
    t1exact shouldContainExactlyInAnyOrder listOf(
      Probable(0.4581, Value.Leaf(true)),
      Probable(0.188999999999999974, Value.Leaf(false))
    )
    t1exact.normalize() shouldContainExactlyInAnyOrder listOf(
      Probable(0.707927677329624472, Value.Leaf(true)),
      Probable(0.292072322670375473, Value.Leaf(false))
    )
  }

  @Test
  fun `test grass model with memoization`() = runTestCC {
    val t2exact = exactReify {
      val cloudy = letLazy { flip(0.5) }
      val rain = letLazy { flip(if (cloudy()) 0.8 else 0.2) }
      val sprinkler = letLazy { flip(if (cloudy()) 0.1 else 0.5) }
      val wetRoof = letLazy { flip(0.7) && rain() }
      val wetGrass = letLazy { (flip(0.9) && rain()) || (flip(0.9) && sprinkler()) }
      if (wetGrass()) rain() else raise("Failure")
    }
    t2exact shouldContainExactlyInAnyOrder listOf(
      Probable(0.458100000000000063, Value.Leaf(true)),
      Probable(0.189000000000000029, Value.Leaf(false))
    )

  }

  @Test
  fun `test nested inference`() = runTestCC {
    val result = exactReify {
      val biased = flip(0.5)
      exactReify { flip(0.5) || biased }.atLeast(0.3, true)
    }
    result shouldContainExactlyInAnyOrder listOf(Probable(1.0, Value.Leaf(true)))
  }

  @Test
  fun `test inference about inference`() = runTestCC {
    val result = exactReify {
      val biased = flip(0.5)
      sampleRejection(distSelector(), 2) { flip(0.5) || biased }.atLeast(0.3, true)
    }

    result shouldContainExactlyInAnyOrder listOf(
      Probable(0.875, Value.Leaf(true)),
      Probable(0.125, Value.Leaf(false))
    )
  }

  @Test
  fun `test inference with memoization`() = runTestCC {
    val result = exactReify {
      val biased = letLazy { flip(0.5) }
      sampleRejection(distSelector(), 2) { flip(0.5) || biased() }.atLeast(0.3, true)
    }
    result shouldContainExactlyInAnyOrder listOf(
      Probable(0.875, Value.Leaf(true)),
      Probable(0.125, Value.Leaf(false))
    )
  }
}

private fun <A> SearchTree<A>.normalize(): SearchTree<A> {
  val total = sumOf { it.prob }
  return map { it.copy(prob = it.prob / total) }.sortedByDescending { it.prob }
}