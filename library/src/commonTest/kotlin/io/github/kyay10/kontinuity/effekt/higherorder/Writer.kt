package io.github.kyay10.kontinuity.effekt.higherorder

import io.github.kyay10.kontinuity.MultishotScope
import io.github.kyay10.kontinuity.effekt.StatefulHandler
import io.github.kyay10.kontinuity.effekt.StatefulPrompt
import io.github.kyay10.kontinuity.effekt.collect
import io.github.kyay10.kontinuity.effekt.flip
import io.github.kyay10.kontinuity.effekt.get
import io.github.kyay10.kontinuity.effekt.given
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
  suspend fun MultishotScope.tell(value: T)
  suspend fun <R> MultishotScope.listen(block: suspend context(Writer<T>) MultishotScope.() -> R): Pair<T, R>
}

context(w: Writer<T>)
suspend fun <T> MultishotScope.tell(value: T) = with(w) { tell(value) }

context(w: Writer<T>)
suspend fun <T, R> MultishotScope.listen(block: suspend context(Writer<T>) MultishotScope.() -> R): Pair<T, R> =
  with(w) { listen(block) }

private data class WriterData<T>(var acc: T)

suspend fun <T, R> MultishotScope.runWriter(
  initial: T,
  combine: (T, T) -> T,
  block: suspend context(Writer<T>) MultishotScope.() -> R
): Pair<T, R> =
  handleStateful(
    WriterData(initial), WriterData<T>::copy
  ) {
    val res = block(object : Writer<T> {
      override suspend fun MultishotScope.tell(value: T) {
        get().acc = combine(get().acc, value)
      }

      override suspend fun <R> MultishotScope.listen(block: suspend context(Writer<T>) MultishotScope.() -> R): Pair<T, R> =
        Unit.run {
        handleStateful(WriterData(initial), WriterData<T>::copy) {
          val res = block(
            WriterListener(
              given<Writer<T>>(),
              initial,
              combine,
              given<StatefulPrompt<Pair<T, R>, WriterData<T>>>()
            ), this
          )
          get().acc to res
        }
      }
    }, this)
    get().acc to res
  }

private class WriterListener<T, R>(
  val delegate: Writer<T>,
  val initial: T,
  val combine: (T, T) -> T,
  p: StatefulPrompt<R, WriterData<T>>
) : Writer<T>, StatefulHandler<R, WriterData<T>> by p {
  override suspend fun MultishotScope.tell(value: T) {
    get().acc = combine(get().acc, value)
    with(delegate) { tell(value) }
  }

  override suspend fun <R> MultishotScope.listen(block: suspend context(Writer<T>) MultishotScope.() -> R): Pair<T, R> =
    handleStateful(WriterData(initial), WriterData<T>::copy) {
      val res = block(
        WriterListener(this@WriterListener, initial, combine, given<StatefulPrompt<Pair<T, R>, WriterData<T>>>()),
        this
      )
      get().acc to res
    }
}