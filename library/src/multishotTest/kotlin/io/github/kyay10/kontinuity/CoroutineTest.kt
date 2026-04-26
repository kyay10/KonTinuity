package io.github.kyay10.kontinuity

import kotlin.test.Test

// Example translated from http://drops.dagstuhl.de/opus/volltexte/2018/9208/
class CoroutineTest {
  context(_: Amb, _: Generator<Int>)
  suspend fun randomYield() {
    yield(0)
    if (flip()) {
      yield(1)
    } else {
      yield(2)
    }
    yield(3)
  }

  context(_: Generator<A>)
  suspend fun <A> bucket(b: List<A>) = b.forEachIteratorless { yield(it) }

  context(_: Generator<A>)
  suspend fun <A> buckets(bs: List<List<A>>) = bs.forEachIteratorless { bucket(it) }

  val data = listOf(listOf(4, 3, 5, 7), listOf(8), listOf(), listOf(1, 3, 7))

  @Test
  fun bucketsTest() = runTestCC {
    val co = coroutine { buckets(data) }
    buildList {
      do {
        add(co.value)
      } while (co.resume())
    } shouldEq listOf(4, 3, 5, 7, 8, 1, 3, 7)
  }

  @Test
  fun snapshotTest() = runTestCC {
    val co = coroutine { buckets(data) }
    co.value shouldEq 4
    co.resume() shouldEq true
    co.value shouldEq 3
    val co2 = co.snapshot()
    co.resume() shouldEq true
    co.value shouldEq 5
    co2.value shouldEq 3
    co2.resume() shouldEq true
    co2.value shouldEq 5
    co.value shouldEq 5
  }

  @Test
  fun randomTest() = runTestCC {
    buildList {
      effectfulLogic {
        val co: Coroutine<Unit, Int, Unit> = coroutine { randomYield() }
        do {
          add(co.value)
        } while (co.resume())
      }
    } shouldEq listOf(0, 1, 3, 2, 3)
  }
}

// We use consistently across interfaces:
//
//   In: values that go into the coroutine
//   Out: values that come out of the coroutine
//   Result: the final result value of the coroutine

interface Coroutine<In, Out, Result> {
  suspend fun resume(input: In): Boolean

  val isDone: Boolean
  val value: Out
  val result: Result

  fun snapshot(): Coroutine<In, Out, Result>
}

suspend fun <In, Out, Result> coroutine(body: CoroutineBody<In, Out, Result>): Coroutine<In, Out, Result> =
  handle { CoroutineState.Done(body { use { k -> CoroutineState.Paused(k, it) } }) }.let(::CoroutineInstance)

suspend fun Coroutine<Unit, *, *>.resume(): Boolean = resume(Unit)

data class CoroutineInstance<In, Out, Result>(var state: CoroutineState<In, Out, Result>) : Coroutine<In, Out, Result> {
  override val isDone: Boolean
    get() = state is CoroutineState.Done

  override val result: Result
    get() =
      when (val s = state) {
        is CoroutineState.Done -> s.result
        else -> error("This coroutine is not yet done, can't get result")
      }

  override fun snapshot(): Coroutine<In, Out, Result> = copy()

  override val value: Out
    get() =
      when (val s = state) {
        is CoroutineState.Paused -> s.yieldValue
        else -> error("Coroutine is done, doesn't have a value, but a result")
      }

  override suspend fun resume(input: In): Boolean {
    val s = state as? CoroutineState.Paused ?: error("Can't resume this coroutine anymore")
    state = s.k(input)
    return !isDone
  }
}

sealed interface CoroutineState<in In, out Out, out Result> {
  data class Paused<in In, out Out, out Result>(
    val k: SubCont<In, CoroutineState<In, Out, Result>>,
    val yieldValue: Out,
  ) : CoroutineState<In, Out, Result>

  data class Done<Result>(val result: Result) : CoroutineState<Any?, Nothing, Result>
}

typealias CoroutineBody<In, Out, Result> = suspend context(Yield<In, Out>) () -> Result
