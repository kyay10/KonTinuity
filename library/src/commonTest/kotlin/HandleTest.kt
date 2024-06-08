import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class HandleTest {
  @Test
  fun coroutineAndReader() = runTest {
    val printed = mutableListOf<Int>()
    runCC {
      runReader(10) {
        suspend fun Handle<Int, Unit>.handler(error: Int, cont: Cont<Unit, Unit>) {
          printed.add(error)
          handleShallow(::handler) { pushReader(ask() + 1) { cont(Unit) } }
        }
        newHandleShallow<Int, Unit, _>(Handle<Int, Unit>::handler) {
          call(ask())
          call(ask())
          pushReader(ask() + 10) {
            call(ask())
            call(ask())
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
        suspend fun Handle<Int, Unit>.handler(error: Int, cont: Cont<Unit, Unit>) {
          handleShallow({ e, k ->
            printed.add(e)
            handler(e, k)
          }) { pushReader(ask() + 1) { cont(Unit) } }
        }
        newHandleShallow<Int, Unit, _>(Handle<Int, Unit>::handler) {
          call(ask())
          call(ask())
          pushReader(ask() + 10) {
            call(ask())
            call(ask())
          }
        }
      }
    }
    printed shouldBe listOf(11, 21, 21)
  }

  @Test
  fun readerSimulation() = runTest {
    runCC {
      newHandle<Unit, Int, _>({ _, cont -> cont(42) }) {
        call(Unit) shouldBe 42
        handleShallow({ _, cont -> cont(43) }) {
          call(Unit) shouldBe 43
          call(Unit) shouldBe 42
        }
        call(Unit) shouldBe 42
        handle({ _, cont -> cont(44) }) {
          call(Unit) shouldBe 44
          call(Unit) shouldBe 44
        }
        call(Unit) shouldBe 42
      }
    }
  }
}