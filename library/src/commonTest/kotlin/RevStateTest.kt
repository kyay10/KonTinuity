import io.kotest.matchers.shouldBe
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class RevStateTest {
  @Test
  fun reverse() = runTest {
    // Usage example
    data class CounterState(val count: Int)

    suspend fun RevState<CounterState, Unit>.incrementCounter() {
      modify { state ->
        suspend {
          val state = state()
          state.copy(count = state.count + 1)
        }
      }
    }

    suspend fun RevState<CounterState, Unit>.doubleCounter() {
      modify { state ->
        suspend {
          val state = state()
          state.copy(count = state.count * 2)
        }
      }
    }

    val result = runCC {
      runRevState(CounterState(0)) {
        doubleCounter()
        doubleCounter()
        incrementCounter()
      }.first()
    }
    result shouldBe CounterState(4)
  }
}

typealias RevState<S, R> = Prompt<Pair<suspend () -> S, R>>

suspend fun <S, R> RevState<S, R>.modify(f: (suspend () -> S) -> (suspend () -> S)) = shift {
  val (s, r) = it(Unit)
  f(s) to r
}

suspend fun <S, R> RevState<S, R>.get(): suspend () -> S = shift {
  val channel = Channel<suspend () -> S>()
  it(suspend {
    channel.receive()()
  }).also { (s, _) ->
    channel.send(s)
  }
}

suspend fun <S, R> runRevState(value: S, body: suspend RevState<S, R>.() -> R): Pair<suspend () -> S, R> {
  val state = RevState<S, R>()
  return state.pushRevState(value) { state.body() }
}

suspend fun <S, R> RevState<S, R>.pushRevState(value: S, body: suspend () -> R): Pair<suspend () -> S, R> = pushPrompt { suspend { value } to body() }
