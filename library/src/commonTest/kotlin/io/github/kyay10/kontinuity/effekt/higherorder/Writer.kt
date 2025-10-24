package io.github.kyay10.kontinuity.effekt.higherorder

import io.github.kyay10.kontinuity.MultishotScope
import io.github.kyay10.kontinuity.NewRegion
import io.github.kyay10.kontinuity.NewScope
import io.github.kyay10.kontinuity.effekt.*
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

interface Writer<T, in Region> {
  context(_: MultishotScope<Region>)
  suspend fun tell(value: T)

  context(_: MultishotScope<IR>)
  suspend fun <R, IR: Region> listen(block: suspend context(NewScope<IR>) Writer<T, NewRegion>.() -> R): Pair<T, R>
}

private data class WriterData<T>(var acc: T)

context(_: MultishotScope<Region>)
suspend fun <T, R, Region> runWriter(
  initial: T,
  combine: (T, T) -> T,
  block: suspend context(MultishotScope<Region>) Writer<T, Region>.() -> R
): Pair<T, R> =
  handleStateful(
    WriterData(initial), WriterData<T>::copy
  ) {
    val res = block(object : Writer<T, Region> {
      context(_: MultishotScope<Region>)
      override suspend fun tell(value: T) {
        get().acc = combine(get().acc, value)
      }

      context(_: MultishotScope<IR>)
      override suspend fun <R, IR: Region> listen(block: suspend context(NewScope<IR>) Writer<T, NewRegion>.() -> R): Pair<T, R> =
        run writer@{
          handleStateful(WriterData(initial), WriterData<T>::copy) {
            val res = block(WriterListener(this@writer, initial, combine, this))
            get().acc to res
          }
        }
    })
    get().acc to res
  }

private class WriterListener<T, R, IR: OR, OR>(
  val delegate: Writer<T, OR>,
  val initial: T,
  val combine: (T, T) -> T,
  p: StatefulPrompt<R, WriterData<T>, IR, OR>
) : Writer<T, IR>, StatefulHandler<R, WriterData<T>, IR, OR> by p {
  context(_: MultishotScope<IR>)
  override suspend fun tell(value: T) {
    get().acc = combine(get().acc, value)
    delegate.tell(value)
  }

  context(_: MultishotScope<IIR>)
  override suspend fun <R, IIR: IR> listen(block: suspend context(NewScope<IIR>) Writer<T, NewRegion>.() -> R): Pair<T, R> =
    handleStateful(WriterData(initial), WriterData<T>::copy) {
      val res = block(WriterListener(this@WriterListener, initial, combine, this))
      get().acc to res
    }
}