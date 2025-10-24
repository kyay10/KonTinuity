package io.github.kyay10.kontinuity.effekt

import io.github.kyay10.kontinuity.MultishotScope
import io.github.kyay10.kontinuity.NewRegion
import io.github.kyay10.kontinuity.NewScope
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

  context(_: MultishotScope<Region>)
  suspend fun <Region> Prob<Region>.falsePositive(): Boolean {
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

class ProbHandler<R, in IR, OR>(prompt: StatefulPrompt<List<Weighted<R>>, Data, IR, OR>) : Prob<IR>,
  StatefulHandler<List<Weighted<R>>, ProbHandler.Data, IR, OR> by prompt {
  data class Data(var p: Double = 1.0) : Stateful<Data> {
    override fun fork() = copy()
  }

  context(_: MultishotScope<IR>)
  override suspend fun flip(): Boolean = use { k ->
    val previous = get().p
    k(false).also { get().p = previous } + k(true)
  }

  context(_: MultishotScope<IR>)
  override suspend fun fail(): Nothing = discard { emptyList() }

  context(_: MultishotScope<IR>)
  override suspend fun factor(p: Double) {
    get().p *= p
  }
}

context(_: MultishotScope<Region>)
suspend fun <R, Region> probabilistic(body: suspend context(NewScope<Region>) Prob<NewRegion>.() -> R): List<Weighted<R>> =
  handleStateful(ProbHandler.Data()) {
    listOf(Weighted(body(ProbHandler(this)), get().p))
  }

data class Weighted<T>(val value: T, val weight: Double)

// use mutable state for now.
class Tracing<R, in IR, OR>(prompt: HandlerPrompt<R, IR, OR>, internal val pts: MutableList<SamplePoint<R, OR>>) : Amb<IR>, Handler<R, IR, OR> by prompt {
  context(_: MultishotScope<IR>)
  override suspend fun flip(): Boolean = use { k ->
    val choice = Random.nextBoolean()
    pts.add(SamplePoint(mutableListOf(choice), k))
    k(choice)
  }
}

context(_: MultishotScope<Region>)
suspend fun <Region> tracing(body: suspend context(NewScope<Region>) Amb<NewRegion>.() -> Int): Int {
  val pts = mutableListOf<SamplePoint<Int, Region>>()
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

data class SamplePoint<out R, in Region>(val choices: MutableList<Boolean>, val k: SubCont<Boolean, R, Region>)

interface Prob<in Region> {
  context(_: MultishotScope<Region>)
  suspend fun flip(): Boolean

  context(_: MultishotScope<Region>)
  suspend fun fail(): Nothing

  context(_: MultishotScope<Region>)
  suspend fun factor(p: Double)
}

// could also be the primitive effect op and `flip = bernoulli(0.5)`
context(_: MultishotScope<Region>)
suspend fun <Region> Prob<Region>.bernoulli(p: Double): Boolean = if (flip()) {
  factor(p)
  true
} else {
  factor(1 - p)
  false
}

context(_: MultishotScope<Region>)
suspend fun <Region> Prob<Region>.guard(condition: Boolean) {
  if (!condition) fail()
}