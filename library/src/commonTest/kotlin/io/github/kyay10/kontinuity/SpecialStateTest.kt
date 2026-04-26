package io.github.kyay10.kontinuity

import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes

class SpecialStateTest {
  @Test fun simpleRead() = runTestCC { stateFun(42) { read() } shouldEq 42 }

  @Test
  fun countDown() = runTestCC {
    suspend fun StateT<Int>.countDown(): List<Int> {
      val printed = mutableListOf(Int.MIN_VALUE)
      while (read() > 0) {
        printed.add(read())
        put(read() - 1)
      }
      printed.add(read())
      return printed
    }
    stateFun(10) { countDown() } shouldEq listOf(Int.MIN_VALUE, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1, 0)
  }

  @Test
  fun silentCountDown() =
    runTestCC(timeout = 10.minutes) {
      suspend fun StateT<Int>.countDown() {
        while (read() > 0) {
          put(read() - 1)
        }
      }
      stateFun(10_000) { countDown() }
    }
}

class SpecialState<S>(val state: State<S>) : StateT<S> {
  override suspend fun read(): S = state.value

  override suspend fun put(value: S) {
    state.value = value
  }
}

suspend fun <R, S> stateFun(init: S, block: suspend StateT<S>.() -> R): R =
  handle<suspend (S) -> R> {
    val res =
      block(
        object : StateT<S> {
          override suspend fun read(): S = useOnce { k -> { s -> k(s)(s) } }

          override suspend fun put(value: S) = useOnce { k -> { _ -> k(Unit)(value) } }
        }
      )
    suspend { _: S -> res }
  }(init)

interface StateT<S> : Read<S> {
  suspend fun put(value: S)
}
