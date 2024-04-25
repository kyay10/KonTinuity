import androidx.compose.runtime.Composable
import arrow.core.raise.Raise
import arrow.fx.coroutines.resourceScope
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class MonadTest {
  data class SuspendState<S, out A>(val run: suspend (S) -> Pair<A, S>) {
    suspend fun <B> flatMap(f: suspend (A) -> SuspendState<S, B>): SuspendState<S, B> =
      SuspendState { s0 ->
        val (a, s1) = run(s0)
        f(a).run(s1)
      }
  }

  context(Reset<SuspendState<S, A>>, Raise<Unit>)
  @Composable
  fun <S, A, B> SuspendState<S, B>.bind(): B = shift { continuation ->
    flatMap { value -> continuation(value) }
  }

  @Test
  fun suspendStateMonad() = runTest {
    resourceScope {
      // Usage example
      data class CounterState(val count: Int)

      fun incrementCounter(): SuspendState<CounterState, Unit> = SuspendState { state ->
        Pair(Unit, state.copy(count = state.count + 1))
      }

      fun doubleCounter(): SuspendState<CounterState, Unit> = SuspendState { state ->
        Pair(Unit, state.copy(count = state.count * 2))
      }

      val result = lazyReset<SuspendState<CounterState, Unit>> {
        incrementCounter().bind()
        doubleCounter().bind()
        doubleCounter()
      }.bind()

      result.run(CounterState(0)) shouldBe incrementCounter().flatMap { doubleCounter().flatMap { doubleCounter() } }
        .run(CounterState(0))
    }
  }

  data class State<S>(var state: S)

  inline fun <S, R> state(initial: S, block: State<S>.() -> R): Pair<R, S> = State(initial).run {
    block() to state
  }

  // Usage example
  data class Counter(val count: Int)

  context(State<Counter>)
  private fun incrementCounter() {
    state = state.copy(count = state.count + 1)
  }

  context(State<Counter>)
  private fun doubleCounter() {
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
    suspend fun <B> flatMap(f: suspend (A) -> SuspendReader<R, B>): SuspendReader<R, B> =
      SuspendReader { r0 ->
        val a = reader(r0)
        f(a).reader(r0)
      }
  }

  context(Raise<Unit>)
  @Composable
  fun <R, A, B> Reset<SuspendReader<R, A>>.bind(reader: SuspendReader<R, B>): B =
    shift { continuation ->
      reader.flatMap { value -> continuation(value) }
    }

  @Test
  fun suspendReaderMonad() = runTest {
    resourceScope {
      val one: SuspendReader<String, Int> = SuspendReader { input -> input.toInt() }
      val sum = lazyReset<SuspendReader<String, Int>> {
        val a = bind(one)
        val b = bind(one)
        SuspendReader { _: String -> a + b }
      }.bind()
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