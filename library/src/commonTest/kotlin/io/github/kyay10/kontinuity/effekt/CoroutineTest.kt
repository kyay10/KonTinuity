package io.github.kyay10.kontinuity.effekt

import io.github.kyay10.kontinuity.MultishotScope
import io.github.kyay10.kontinuity.NewRegion
import io.github.kyay10.kontinuity.NewScope
import io.github.kyay10.kontinuity.SubCont
import io.github.kyay10.kontinuity.runTestCC
import io.kotest.matchers.shouldBe
import kotlin.test.Test

// Example translated from http://drops.dagstuhl.de/opus/volltexte/2018/9208/
class CoroutineTest {
  context(_: Amb<Region>, _: YieldAny<Int, Region>, _: MultishotScope<Region>)
  suspend fun <Region> randomYield() {
    yield(0)
    if (flip()) {
      yield(1)
    } else {
      yield(2)
    }
    yield(3)
  }

  context(_: YieldAny<A, Region>, _: MultishotScope<Region>)
  suspend fun <A, Region> bucket(b: List<A>) {
    var i = 0
    while (i < b.size) {
      yield(b[i++])
    }
  }

  context(_: YieldAny<A, Region>, _: MultishotScope<Region>)
  suspend fun <A, Region> buckets(bs: List<List<A>>) {
    var i = 0
    while (i < bs.size) {
      bucket(bs[i++])
    }
  }

  val data = listOf(listOf(4, 3, 5, 7), listOf(8), listOf(), listOf(1, 3, 7))

  @Test
  fun bucketsTest() = runTestCC {
    val co = coroutine<Unit, _, _, _> { buckets(data) }
    buildList {
      do {
        add(co.value)
      } while (co.resume())
    } shouldBe listOf(4, 3, 5, 7, 8, 1, 3, 7)
  }

  @Test
  fun snapshotTest() = runTestCC {
    val co = coroutine<Unit, _, _, _> { buckets(data) }
    co.value shouldBe 4
    co.resume()
    co.value shouldBe 3
    val co2 = co.snapshot()
    co.resume()
    co.value shouldBe 5
    co2.value shouldBe 3
    co2.resume()
    co2.value shouldBe 5
    co.value shouldBe 5
  }

  @Test
  fun randomTest() = runTestCC {
    buildList {
      ambList {
        val co = coroutine { randomYield() }
        do {
          add(co.value)
        } while (co.resume())
      }
    } shouldBe listOf(0, 1, 3, 2, 3)
  }
}

// We use consistently across interfaces:
//
//   In: values that go into the coroutine
//   Out: values that come out of the coroutine
//   Result: the final result value of the coroutine

interface Coroutine<In, Out, Result, in Region> {
  context(_: MultishotScope<Region>)
  suspend fun resume(input: In): Boolean
  val isDone: Boolean
  val value: Out
  val result: Result
  fun snapshot(): Coroutine<In, Out, Result, Region>
}

context(_: MultishotScope<Region>)
suspend fun <In, Out, Result, Region> coroutine(
  body: CoroutineBody<In, Out, Result, Region>
): Coroutine<In, Out, Result, Region> = handle<CoroutineState<In, Out, Result, Region>, _> {
  context(Yielder(this)) {
    CoroutineState.Done(body())
  }
}.let(::CoroutineInstance)

context(_: MultishotScope<Region>)
suspend fun <Region> Coroutine<Unit, *, *, Region>.resume(): Boolean = resume(Unit)

data class CoroutineInstance<In, Out, Result, Region>(
  var state: CoroutineState<In, Out, Result, Region>
) : Coroutine<In, Out, Result, Region> {
  override val isDone: Boolean
    get() = state is CoroutineState.Done
  override val result: Result
    get() = when (val s = state) {
      is CoroutineState.Done -> s.result
      else -> error("This coroutine is not yet done, can't get result")
    }

  override fun snapshot(): Coroutine<In, Out, Result, Region> = copy()
  override val value: Out
    get() = when (val s = state) {
      is CoroutineState.Paused -> s.yieldValue
      else -> error("Coroutine is done, doesn't have a value, but a result")
    }

  context(_: MultishotScope<Region>)
  override suspend fun resume(input: In): Boolean {
    val s = state as? CoroutineState.Paused ?: error("Can't resume this coroutine anymore")
    state = s.k(input)
    return !isDone
  }
}

class Yielder<In, Out, Result, in IR, OR>(prompt: HandlerPrompt<CoroutineState<In, Out, Result, OR>, IR, OR>) : Yield<In, Out, IR>,
  Handler<CoroutineState<In, Out, Result, OR>, IR, OR> by prompt {
  context(_: MultishotScope<IR>)
  override suspend fun yield(value: Out): In = use {
    CoroutineState.Paused(it, value)
  }
}

sealed interface CoroutineState<in In, out Out, out Result, in Region> {
  data class Paused<in In, out Out, out Result, in Region>(val k: SubCont<In, CoroutineState<In, Out, Result, Region>, Region>, val yieldValue: Out) :
    CoroutineState<In, Out, Result, Region>

  data class Done<Result>(val result: Result) : CoroutineState<Any?, Nothing, Result, Any?>
}

typealias CoroutineBody<In, Out, Result, Region> = suspend context(Yield<In, Out, NewRegion>, NewScope<Region>) () -> Result

interface Yield<out In, in Out, in Region> {
  context(_: MultishotScope<Region>)
  suspend fun yield(value: Out): In
}
typealias YieldAny<Out, Region> = Yield<*, Out, Region>

context(yield: YieldAny<Out, Region>, _: MultishotScope<Region>)
suspend fun <Out, Region> yield(value: Out) {
  yield.yield(value)
}