package io.github.kyay10.kontinuity.effekt

import io.github.kyay10.kontinuity.MultishotScope
import io.github.kyay10.kontinuity.SubCont
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

  context(_: Prob)
  suspend fun MultishotScope.falsePositive(): Boolean {
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

  override suspend fun MultishotScope.flip(): Boolean = use { k ->
    val previous = get().p
    k(false).also { get().p = previous } + k(true)
  }

  override suspend fun MultishotScope.fail(): Nothing = discard { emptyList() }

  override suspend fun MultishotScope.factor(p: Double) {
    get().p = p * get().p
  }
}

suspend fun <R> MultishotScope.probabilistic(body: suspend context(ProbHandler<R>) MultishotScope.() -> R): List<Weighted<R>> =
  handleStateful(ProbHandler.Data()) {
    listOf(Weighted(body(ProbHandler(given<StatefulPrompt<List<Weighted<R>>, ProbHandler.Data>>()), this), get().p))
  }

data class Weighted<T>(val value: T, val weight: Double)

// use mutable state for now.
class Tracing<R>(prompt: HandlerPrompt<R>, internal val pts: MutableList<SamplePoint<R>>) : Amb, Handler<R> by prompt {
  override suspend fun MultishotScope.flip(): Boolean = use { k ->
    val choice = Random.nextBoolean()
    pts.add(SamplePoint(mutableListOf(choice), k))
    k(choice)
  }
}

suspend fun MultishotScope.tracing(body: suspend context(Tracing<Int>) MultishotScope.() -> Int): Int {
  val pts = mutableListOf<SamplePoint<Int>>()
  var res = handle { body(Tracing(given<HandlerPrompt<Int>>(), pts), this) }
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
  suspend fun MultishotScope.flip(): Boolean
  suspend fun MultishotScope.fail(): Nothing
  suspend fun MultishotScope.factor(p: Double)
}

context(p: Prob)
suspend fun MultishotScope.flip() = with(p) { flip() }
context(p: Prob)
suspend fun MultishotScope.fail(): Nothing = with(p) { fail() }
context(prob: Prob)
suspend fun MultishotScope.factor(p: Double) = with(prob) { factor(p) }

// could also be the primitive effect op and `flip = bernoulli(0.5)`
context(_: Prob)
suspend fun MultishotScope.bernoulli(p: Double): Boolean = if (flip()) {
  factor(p)
  true
} else {
  factor(1 - p)
  false
}

context(_: Prob)
suspend fun MultishotScope.guard(condition: Boolean) {
  if (!condition) fail()
}