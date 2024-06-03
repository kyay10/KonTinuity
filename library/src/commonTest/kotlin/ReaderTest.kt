import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class ReaderTest {
  @Test
  fun simple() = runTest {
    runReader(1) reader@{
      newReset reset@{
        pushReader(2) {
          this@reset.shift { f -> this@reader.get() }
        }
      }
    } shouldBe 1
  }

  // https://www.brinckerhoff.org/clements/csc530-sp08/Readings/kiselyov-2006.pdf
  @Test
  @Suppress("UNCHECKED_CAST")
  fun example6FromDBDCPaper() = runTest {
    runCC {
      val p = Reader<Int>()
      val r = Reader<Int>()
      val tag = Prompt<Any?>()
      val f =
        tag.pushPrompt(p.context(1)) {
          r.pushReader(10) {
            tag.shift<Int, _> {
              p.get() shouldBe 1
              it
            } shouldBe 0
            p.get() + r.get()
          }
      } as suspend (Int) -> Int
      p.pushReader(2) {
        r.pushReader(20) {
          f(0)
        }
      }
    } shouldBe 12
  }
}