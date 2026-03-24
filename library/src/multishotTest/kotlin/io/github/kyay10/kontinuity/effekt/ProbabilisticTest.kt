package io.github.kyay10.kontinuity.effekt

import io.github.kyay10.kontinuity.SubCont
import io.github.kyay10.kontinuity.runState
import io.github.kyay10.kontinuity.runTestCC
import io.kotest.matchers.shouldBe
import kotlin.random.Random
import kotlin.test.Test

class ProbabilisticTest {
  @Test
  fun tracing() = runTestCC {
    repeat(10) {
      val res = tracing {
        when {
          flip() -> if (flip()) 1 else 2
          flip() -> 3
          else -> 4
        }
      }
      res shouldBe 1
    }
  }

  suspend fun Prob.falsePositive(): Boolean {
    val sick = bernoulli(0.01)
    guard(
      if (sick) {
        bernoulli(0.99)
      } else {
        bernoulli(0.1)
      }
    )
    return sick
  }

  @Test
  fun falsePositive() = runTestCC {
    probabilistic {
      falsePositive()
    } shouldBe listOf(
      Weighted(false, 0.099), Weighted(true, 0.0099)
    )
  }
}

suspend fun <R> probabilistic(body: suspend Prob.() -> R): List<Weighted<R>> = runState(1.0) {
  handle {
    listOf(Weighted(body(object : Prob {
      override suspend fun flip(): Boolean = use { k ->
        val previous = value
        k(false).also { value = previous } + k(true)
      }

      override suspend fun fail(): Nothing = discard { emptyList() }

      override suspend fun factor(p: Double) {
        value *= p
      }
    }), value))
  }
}

data class Weighted<T>(val value: T, val weight: Double)

// use mutable state for now.
class Tracing<R>(prompt: HandlerPrompt<R>, internal val pts: MutableList<SamplePoint<R>>) : Amb, Handler<R> by prompt {
  override suspend fun flip(): Boolean = use { k ->
    val choice = Random.nextBoolean()
    pts.add(SamplePoint(mutableListOf(choice), k))
    k(choice)
  }
}

suspend fun tracing(body: suspend Tracing<Int>.() -> Int): Int {
  val pts = mutableListOf<SamplePoint<Int>>()
  var res = handle { body(Tracing(this, pts)) }
  // ok some very specialized sampling:
  //   We are trying to find a result which is == 1
  while (res != 1) {
    pts.shuffle()
    // find the first samplePoint that is not exhausted
    val pt = pts.firstOrNull { it.choices.size < 2 } ?: error("Could not find samples to produce expected result")
    val alternative = !pt.choices[0]
    // mark as having tried that one
    pt.choices.add(alternative)
    res = pt.k(alternative)
  }
  return res
}

data class SamplePoint<out R>(val choices: MutableList<Boolean>, val k: SubCont<Boolean, R>)

interface Prob {
  suspend fun flip(): Boolean
  suspend fun fail(): Nothing
  suspend fun factor(p: Double)
}

// could also be the primitive effect op and `flip = bernoulli(0.5)`
suspend fun Prob.bernoulli(p: Double): Boolean = if (flip()) {
  factor(p)
  true
} else {
  factor(1 - p)
  false
}

suspend fun Prob.guard(condition: Boolean) {
  if (!condition) fail()
}