import Reset.Companion.lazyReset
import androidx.compose.runtime.Composable
import arrow.fx.coroutines.*
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class MonadTest {
  data class SuspendState<S, out A>(val run: suspend (S) -> Pair<A, S>) {
    fun <B> flatMap(f: suspend (A) -> SuspendState<S, B>): SuspendState<S, B> =
      SuspendState { s0 ->
        val (a, s1) = run(s0)
        f(a).run(s1)
      }

    fun onCompletion(f: suspend (Throwable?) -> Unit): SuspendState<S, A> =
      SuspendState { s0 ->
        try {
          run(s0).also { f(null) }
        } catch (e: Throwable) {
          f(e)
          throw e
        }
      }

    companion object {
      fun <S, A> of(a: A): SuspendState<S, A> = SuspendState { s -> Pair(a, s) }
    }
  }

  @Composable
  fun <S, A, B> Reset<SuspendState<S, A>>.bind(state: SuspendState<S, B>): B =
    shift { continuation ->
      state.flatMap { value -> continuation(value) }
    }

  @OptIn(DelicateCoroutinesApi::class)
  suspend fun <S, R> stateReset(body: @Composable Reset<SuspendState<S, R>>.() -> R): SuspendState<S, R> {
    val (suspendState, release) = resource { lazyReset { SuspendState.of(body(this)) } }.allocated()
    return suspendState.onCompletion { release(it?.let(ExitCase::ExitCase) ?: ExitCase.Completed) }
  }

  @Test
  fun suspendStateMonad() = runTest {
    // Usage example
    data class CounterState(val count: Int)

    fun incrementCounter(): SuspendState<CounterState, Unit> = SuspendState { state ->
      Pair(Unit, state.copy(count = state.count + 1))
    }

    fun doubleCounter(): SuspendState<CounterState, Unit> = SuspendState { state ->
      Pair(Unit, state.copy(count = state.count * 2))
    }

    val result = stateReset {
      bind(incrementCounter())
      bind(doubleCounter())
      bind(doubleCounter())
    }

    result.run(CounterState(0)) shouldBe incrementCounter().flatMap { doubleCounter().flatMap { doubleCounter() } }
      .run(CounterState(0))
  }

  data class State<S>(var state: S)

  private inline fun <S, R> state(initial: S, block: State<S>.() -> R): Pair<R, S> = State(initial).run {
    block() to state
  }

  // Usage example
  data class Counter(val count: Int)

  private fun State<Counter>.incrementCounter() {
    state = state.copy(count = state.count + 1)
  }

  private fun State<Counter>.doubleCounter() {
    state = state.copy(count = state.count * 2)
  }

  @Test
  fun betterStateMonad() = runTest {
    val (_, holder1) = state(Counter(0)) {
      incrementCounter()
      doubleCounter()
      doubleCounter()
    }

    val holder2 = State(Counter(0))
    holder1 shouldBe holder2.apply {
      incrementCounter()
      doubleCounter()
      doubleCounter()
    }.state
  }

  class SuspendReader<R, A>(val reader: suspend (R) -> A) {
    fun <B> flatMap(f: suspend (A) -> SuspendReader<R, B>): SuspendReader<R, B> =
      SuspendReader { r0 ->
        val a = reader(r0)
        f(a).reader(r0)
      }

    fun onCompletion(f: suspend (Throwable?) -> Unit): SuspendReader<R, A> =
      SuspendReader { r0 ->
        try {
          reader(r0).also { f(null) }
        } catch (e: Throwable) {
          f(e)
          throw e
        }
      }

    companion object {
      fun <R, A> of(a: A): SuspendReader<R, A> = SuspendReader { a }
    }
  }

  @Composable
  fun <R, A, B> Reset<SuspendReader<R, A>>.bind(reader: SuspendReader<R, B>): B =
    shift { continuation ->
      reader.flatMap { value -> continuation(value) }
    }

  @OptIn(DelicateCoroutinesApi::class)
  suspend fun <R, A> readerReset(body: @Composable Reset<SuspendReader<R, A>>.() -> A): SuspendReader<R, A> {
    val (suspendReader, release) = resource { lazyReset { SuspendReader.of(body(this)) } }.allocated()
    return suspendReader.onCompletion { release(it?.let(ExitCase::ExitCase) ?: ExitCase.Completed) }
  }

  @Test
  fun suspendReaderMonad() = runTest {
    resourceScope {
      val one: SuspendReader<String, Int> = SuspendReader { input -> input.toInt() }
      val sum = readerReset {
        bind(one) + bind(one)
      }
      sum.reader("1") shouldBe 2
    }
  }

  @Test
  fun betterReaderMonad() = runTest {
    // extensions or contexts
    fun String.one(): Int = toInt()
    fun String.sum(): Int = one() + one()
    "1".sum() shouldBe 2
  }
}