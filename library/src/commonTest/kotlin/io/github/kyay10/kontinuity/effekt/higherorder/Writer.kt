package io.github.kyay10.kontinuity.effekt.higherorder

import io.github.kyay10.kontinuity.State
import io.github.kyay10.kontinuity.runState
import io.github.kyay10.kontinuity.runTestCC
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class WriterTest {
  @Test
  fun basicTest() = runTestCC {
    runWriter(0, Int::plus) {
      tell(1)
      listen {
        tell(2)
      }.first shouldBe 2
      tell(3)
    }.first shouldBe 6
  }
}

interface Writer<T> {
  suspend fun tell(t: T)
  suspend fun <R> listen(block: suspend Writer<T>.() -> R): Pair<T, R>
}

suspend fun <T, R> runWriter(initial: T, combine: (T, T) -> T, block: suspend Writer<T>.() -> R): Pair<T, R> {
  class WriterListener(val state: State<T>, val delegate: Writer<T>? = null) : Writer<T> {
    override suspend fun tell(t: T) {
      state.value = combine(state.value, t)
      delegate?.tell(t)
    }

    override suspend fun <R> listen(block: suspend Writer<T>.() -> R): Pair<T, R> = runState(initial) {
      val res = block(WriterListener(this, this@WriterListener))
      value to res
    }
  }
  return runState(initial) {
    val res = block(WriterListener(this))
    value to res
  }
}