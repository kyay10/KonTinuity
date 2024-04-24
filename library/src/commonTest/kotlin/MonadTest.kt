import androidx.compose.runtime.Composable
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class MonadTest {
  @Test
  fun suspendStateMonad() = runTest {
    data class State<S, out A>(val run: suspend (S) -> Pair<A, S>) {
      suspend fun <B> flatMap(f: suspend (A) -> State<S, B>): State<S, B> =
        State { s0 ->
          val (a, s1) = run(s0)
          f(a).run(s1)
        }
    }

    @Composable
    fun <S, A, B> Reset<State<S, A>>.bind(s: State<S, B>): Maybe<B> = shift { continuation ->
      s.flatMap { value -> continuation(value) }
    }

    // Usage example
    data class CounterState(val count: Int)

    fun incrementCounter(): State<CounterState, Unit> = State { state ->
      Pair(Unit, state.copy(count = state.count + 1))
    }

    fun doubleCounter(): State<CounterState, Unit> = State { state ->
      Pair(Unit, state.copy(count = state.count * 2))
    }

    val result = lazyReset<State<CounterState, Unit>> {
      maybe {
        val a by bind(incrementCounter())
        val b by bind(doubleCounter())
        doubleCounter()
      }
    }

    result.await()
      .run(CounterState(0)) shouldBe incrementCounter().flatMap { doubleCounter().flatMap { doubleCounter() } }
      .run(CounterState(0))
    coroutineContext.cancelChildren()
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

  @Test
  fun suspendReaderMonad() = runTest {
    class Reader<R, A>(val reader: suspend (R) -> A) {
      suspend fun <B> flatMap(f: suspend (A) -> Reader<R, B>): Reader<R, B> =
        Reader { r0 ->
          val a = reader(r0)
          f(a).reader(r0)
        }
    }

    @Composable
    fun <R, A, B> Reset<Reader<R, A>>.bind(reader: Reader<R, B>): Maybe<B> = shift { continuation ->
      reader.flatMap { value -> continuation(value) }
    }

    val one: Reader<String, Int> = Reader { input -> input.toInt() }
    val sum = lazyReset<Reader<String, Int>>{
      maybe {
        val a by bind(one)
        val b by bind(one)
        Reader { _: String -> a + b }
      }
    }.await()
    sum.reader("1") shouldBe 2
    coroutineContext.cancelChildren()
  }

  @Test
  fun betterReaderMonad() = runTest {
    // extensions or contexts
    fun String.one(): Int = toInt()
    fun String.sum(): Int = one() + one()
    "1".sum() shouldBe 2
  }
}