package io.github.kyay10.kontinuity.effekt.higherorder

import io.github.kyay10.kontinuity.effekt.StatefulHandler
import io.github.kyay10.kontinuity.effekt.StatefulPrompt
import io.github.kyay10.kontinuity.effekt.collect
import io.github.kyay10.kontinuity.effekt.handleStateful
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

  @Test
  fun withNonDetTest() = runTestCC {
    runWriter(0, Int::plus) {
      collect {
        listen {
          tell(1)
          if (flip()) {
            tell(2)
            true
          } else {
            tell(3)
            false
          }
        }
      }
    } shouldBe (6 to listOf(3 to true, 4 to false))
  }
}

interface Writer<T> {
  suspend fun tell(t: T)
  suspend fun <R> listen(block: suspend Writer<T>.() -> R): Pair<T, R>
}

private data class WriterData<T>(var acc: T)

suspend fun <T, R> runWriter(initial: T, combine: (T, T) -> T, block: suspend Writer<T>.() -> R): Pair<T, R> =
  handleStateful(
    WriterData(initial), WriterData<T>::copy
  ) {
    val res = block(object : Writer<T> {
      override suspend fun tell(t: T) {
        value.acc = combine(value.acc, t)
      }

      override suspend fun <R> listen(block: suspend Writer<T>.() -> R): Pair<T, R> = run writer@{
        handleStateful(WriterData(initial), WriterData<T>::copy) {
          val res = block(WriterListener(this@writer, initial, combine, this))
          value.acc to res
        }
      }
    })
    value.acc to res
  }

private class WriterListener<T, R>(
  val delegate: Writer<T>,
  val initial: T,
  val combine: (T, T) -> T,
  p: StatefulPrompt<R, WriterData<T>>
) : Writer<T>, StatefulHandler<R, WriterData<T>> by p {
  override suspend fun tell(t: T) {
    value.acc = combine(value.acc, t)
    delegate.tell(t)
  }

  override suspend fun <R> listen(block: suspend Writer<T>.() -> R): Pair<T, R> =
    handleStateful(WriterData(initial), WriterData<T>::copy) {
      val res = block(WriterListener(this@WriterListener, initial, combine, this))
      value.acc to res
    }
}