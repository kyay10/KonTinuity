package io.github.kyay10.kontinuity.effekt

import io.github.kyay10.kontinuity.runTestCC
import io.kotest.matchers.ranges.shouldBeIn
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
      res shouldBeIn 1..4
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

class ProbHandler<R>(prompt: StatefulPrompt<List<Weighted<R>>, Data>) : Prob,
  StatefulHandler<List<Weighted<R>>, ProbHandler.Data> by prompt {
  data class Data(var p: Double = 1.0) : Stateful<Data> {
    override fun fork() = copy()
  }

  override suspend fun flip(): Boolean = use { k ->
    val previous = get().p
    k(false).also { get().p = previous } + k(true)
  }

  override suspend fun fail(): Nothing = discard { emptyList() }

  override suspend fun factor(p: Double) {
    get().p = p * get().p
  }
}

suspend fun <R> probabilistic(body: suspend ProbHandler<R>.() -> R): List<Weighted<R>> =
  handleStateful(ProbHandler.Data()) {
    listOf(Weighted(body(ProbHandler(this)), get().p))
  }

data class Weighted<T>(val value: T, val weight: Double)

class Tracing<R>(prompt: HandlerPrompt<R>) : Amb, Handler<R> by prompt {
  // use mutable state for now.
  internal val pts = mutableListOf<SamplePoint<R>>()
  override suspend fun flip(): Boolean = use { k ->
    val choice = Random.nextBoolean()
    pts.add(SamplePoint(mutableListOf(choice), k))
    k(choice)
  }
}

suspend fun tracing(body: suspend Tracing<Int>.() -> Int): Int = handle {
  with(Tracing(this)) {
    val res = body()
    // ok some very specialized sampling:
    //   We are trying to find a result which is == 1
    if (res != 1) {
      pts.shuffle()
      // find first samplePoint that is not exhausted
      val pt = pts.firstOrNull { it.choices.size < 2 } ?: error("Could not find samples to produce expected result")
      val alternative = !pt.choices[0]
      // mark as having tried that one
      pt.choices.add(alternative)
      pt.k(alternative)
    } else {
      res
    }
  }
}

data class SamplePoint<out R>(val choices: MutableList<Boolean>, val k: Cont<Boolean, R>)

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