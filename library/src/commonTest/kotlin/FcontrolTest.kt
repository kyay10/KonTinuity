import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class FcontrolTest {
  @Test
  fun infiniteSequence() = runTest {
    runCC {
      suspend fun product(s: Iterator<Int>): Int = newResetWithHandler<_, Nothing, _>({ error, _ -> error }) {
        var acc = 1
        for (i in s) {
          if (i == 0) fcontrol(0)
          acc *= i
        }
        acc
      }
      product(generateSequence(5) { it - 1 }.iterator()) shouldBe 0
    }
  }

  @Test
  fun coroutineAndReader() = runTest {
    val printed = mutableListOf<Int>()
    runCC {
      runReader(10) {
        newResetWithHandler<Int, Unit, _>({ error, cont ->
          printed.add(error)
          pushReader(ask() + 1) { cont(Unit) }
        }) {
          fcontrol(ask())
          fcontrol(ask())
          pushReader(ask() + 10) {
            fcontrol(ask())
            fcontrol(ask())
          }
        }
      }
    }
    printed shouldBe listOf(10, 11, 21, 21)
  }

  @Test
  fun coroutineAndReaderWithNestedHandler() = runTest {
    val printed = mutableListOf<Int>()
    runCC {
      runReader(10) {
        newResetWithHandler<Int, Unit, _>({ error, cont ->
          mapWithHandler({ e, k ->
            printed.add(e)
            k(this(e))
          }) { pushReader(ask() + 1) { cont(Unit) } }
        }) {
          fcontrol(ask())
          fcontrol(ask())
          pushReader(ask() + 10) {
            fcontrol(ask())
            fcontrol(ask())
          }
        }
      }
    }
    printed shouldBe listOf(11, 21, 21)
  }
}