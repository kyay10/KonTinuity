package io.github.kyay10.kontinuity.effekt

import io.github.kyay10.kontinuity.MultishotScope
import io.github.kyay10.kontinuity.SubCont
import io.github.kyay10.kontinuity.runTestCC
import io.kotest.matchers.shouldBe
import kotlin.test.Test

// Example translated from http://drops.dagstuhl.de/opus/volltexte/2018/9208/
class CoroutineTest {
  context(_: Amb, _: YieldAny<Int>)
  suspend fun MultishotScope.randomYield() {
    yield(0)
    if (flip()) {
      yield(1)
    } else {
      yield(2)
    }
    yield(3)
  }

  context(_: YieldAny<A>)
  suspend fun <A> MultishotScope.bucket(b: List<A>) {
    var i = 0
    while (i < b.size) {
      yield(b[i++])
    }
  }

  context(_: YieldAny<A>)
  suspend fun <A> MultishotScope.buckets(bs: List<List<A>>) {
    var i = 0
    while (i < bs.size) {
      bucket(bs[i++])
    }
  }

  val data = listOf(listOf(4, 3, 5, 7), listOf(8), listOf(), listOf(1, 3, 7))

  @Test
  fun bucketsTest() = runTestCC {
    val co = coroutine<Unit, Int, _> { buckets(data) }
    buildList {
      do {
        add(co.value)
      } while (resume(co))
    } shouldBe listOf(4, 3, 5, 7, 8, 1, 3, 7)
  }

  @Test
  fun snapshotTest() = runTestCC {
    val co = coroutine<Unit, Int, Unit> { buckets(data) }
    co.value shouldBe 4
    resume(co)
    co.value shouldBe 3
    val co2 = co.snapshot()
    resume(co)
    co.value shouldBe 5
    co2.value shouldBe 3
    resume(co2)
    co2.value shouldBe 5
    co.value shouldBe 5
  }

  @Test
  fun randomTest() = runTestCC {
    buildList {
      ambList {
        val co: Coroutine<Unit, Int, Unit> = coroutine { randomYield() }
        do {
          add(co.value)
        } while (resume(co))
      }
    } shouldBe listOf(0, 1, 3, 2, 3)
  }
}

// We use consistently across interfaces:
//
//   In: values that go into the coroutine
//   Out: values that come out of the coroutine
//   Result: the final result value of the coroutine

interface Coroutine<In, Out, Result> {
  suspend fun MultishotScope.resume(input: In): Boolean
  val isDone: Boolean
  val value: Out
  val result: Result
  fun snapshot(): Coroutine<In, Out, Result>
}

suspend fun <In, Out, Result> MultishotScope.coroutine(
  body: CoroutineBody<In, Out, Result>
): Coroutine<In, Out, Result> = handle {
  CoroutineState.Done(body(Yielder(given<HandlerPrompt<CoroutineState<In, Out, Result>>>()), this))
}.let(::CoroutineInstance)

suspend fun MultishotScope.resume(c: Coroutine<Unit, *, *>): Boolean = with(c) { resume(Unit) }

data class CoroutineInstance<In, Out, Result>(
  var state: CoroutineState<In, Out, Result>
) : Coroutine<In, Out, Result> {
  override val isDone: Boolean
    get() = state is CoroutineState.Done
  override val result: Result
    get() = when (val s = state) {
      is CoroutineState.Done -> s.result
      else -> error("This coroutine is not yet done, can't get result")
    }

  override fun snapshot(): Coroutine<In, Out, Result> = copy()
  override val value: Out
    get() = when (val s = state) {
      is CoroutineState.Paused -> s.yieldValue
      else -> error("Coroutine is done, doesn't have a value, but a result")
    }

  override suspend fun MultishotScope.resume(input: In): Boolean {
    val s = state as? CoroutineState.Paused ?: error("Can't resume this coroutine anymore")
    state = s.k(input)
    return !isDone
  }
}

class Yielder<In, Out, Result>(prompt: HandlerPrompt<CoroutineState<In, Out, Result>>) : Yield<In, Out>,
  Handler<CoroutineState<In, Out, Result>> by prompt {
  override suspend fun MultishotScope.yield(value: Out): In = use {
    CoroutineState.Paused(it, value)
  }
}

sealed interface CoroutineState<in In, out Out, out Result> {
  data class Paused<in In, out Out, out Result>(val k: SubCont<In, CoroutineState<In, Out, Result>>, val yieldValue: Out) :
    CoroutineState<In, Out, Result>

  data class Done<Result>(val result: Result) : CoroutineState<Any?, Nothing, Result>
}

typealias CoroutineBody<In, Out, Result> = suspend context(Yield<In, Out>) MultishotScope.() -> Result

interface Yield<out In, in Out> {
  suspend fun MultishotScope.yield(value: Out): In
}
typealias YieldAny<Out> = Yield<*, Out>

context(yield: YieldAny<Out>)
suspend fun <Out> MultishotScope.yield(value: Out) = with(yield) { yield(value) }