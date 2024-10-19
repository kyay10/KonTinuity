package effekt

import io.kotest.matchers.shouldBe
import runTestCC
import kotlin.test.Test

// Example translated from http://drops.dagstuhl.de/opus/volltexte/2018/9208/
class CoroutineTest {
  suspend fun random(amb: Amb, yield: Yield<*, Int>) {
    yield.yield(0)
    if (amb.flip()) {
      yield.yield(1)
    } else {
      yield.yield(2)
    }
    yield.yield(3)
  }

  suspend fun <A> bucket(b: List<A>, yield: Yield<*, A>) {
    var i = 0
    while (i < b.size) {
      yield.yield(b[i++])
    }
  }

  suspend fun <A> buckets(bs: List<List<A>>, yield: Yield<*, A>) {
    var i = 0
    while (i < bs.size) {
      bucket(bs[i++], yield)
    }
  }

  val data = listOf(listOf(4, 3, 5, 7), listOf(8), listOf(), listOf(1, 3, 7))

  @Test
  fun bucketsTest() = runTestCC {
    val co: Coroutine<Unit, Int, Unit> = Coroutine.call(::buckets, data)
    buildList {
      do {
        add(co.value)
      } while (co.resume())
    } shouldBe listOf(4, 3, 5, 7, 8, 1, 3, 7)
  }

  @Test
  fun snapshotTest() = runTestCC {
    val co: Coroutine<Unit, Int, Unit> = Coroutine.call(::buckets, data)
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
        val co: Coroutine<Unit, Int, Unit> = Coroutine.call(::random, this)
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

interface Coroutine<In, Out, Result> {
  suspend fun resume(input: In): Boolean
  val isDone: Boolean
  val value: Out
  val result: Result
  fun snapshot(): Coroutine<In, Out, Result>

  companion object {
    suspend fun <In, Out, Arg, Result> call(
      body: CoroutineBody<In, Out, Arg, Result>, arg: Arg
    ): Coroutine<In, Out, Result> {
      val yielder = Yielder<In, Out, Result>(HandlerPrompt())
      val instance = CoroutineInstance<In, Out, Result>(yielder, null)
      return instance.start(body, arg)
    }
  }
}

suspend fun Coroutine<Unit, *, *>.resume(): Boolean = resume(Unit)

class CoroutineInstance<In, Out, Result>(
  private val yielder: Yielder<In, Out, Result>, private var state: CoroutineState<In, Out, Result>?
) : Coroutine<In, Out, Result> {
  override val isDone: Boolean
    get() = state is CoroutineState.Done
  override val result: Result
    get() = when (val s = state) {
      is CoroutineState.Done -> s.result
      else -> error("This coroutine is not yet done, can't get result")
    }

  override fun snapshot(): Coroutine<In, Out, Result> = CoroutineInstance(yielder, state)
  override val value: Out
    get() = when (val s = state) {
      is CoroutineState.Paused -> s.yieldValue
      else -> error("Coroutine is done, doesn't have a value, but a result")
    }

  override suspend fun resume(input: In): Boolean {
    val state = when (val s = state) {
      is CoroutineState.Paused -> s
      else -> error("Can't resume this coroutine anymore")
    }
    yielder.state = state
    state.k(input)
    this.state = yielder.state
    return !isDone
  }

  suspend fun <Arg> start(body: CoroutineBody<In, Out, Arg, Result>, arg: Arg): CoroutineInstance<In, Out, Result> {
    yielder.rehandle {
      yielder.state = CoroutineState.Done(body(arg, yielder))
    }
    state = yielder.state
    return this
  }
}

class Yielder<In, Out, Result>(prompt: HandlerPrompt<Unit>) : Yield<In, Out>, Handler<Unit> by prompt {
  lateinit var state: CoroutineState<In, Out, Result>
  override suspend fun yield(value: Out): In = use {
    state = CoroutineState.Paused(it, value)
  }
}

sealed interface CoroutineState<in In, out Out, out Result> {
  data class Paused<in In, out Out>(val k: Cont<In, Unit>, val yieldValue: Out) : CoroutineState<In, Out, Nothing>
  data class Done<Result>(val result: Result) : CoroutineState<Any?, Nothing, Result>
}

typealias CoroutineBody<In, Out, Arg, Result> = suspend (Arg, Yield<In, Out>) -> Result

interface Yield<out In, in Out> {
  suspend fun yield(value: Out): In
}

suspend fun <In> Yield<In, Unit>.yield(): In = yield(Unit)