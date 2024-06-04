import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class ReaderTest {
  @Test
  fun simple() = runTest {
    runReader(1) reader@{
      newReset reset@{
        pushReader(2) {
          this@reset.shift { f -> this@reader.ask() }
        }
      }
    } shouldBe 1
  }

  sealed class R<in A, out B> {
    data class R<out B>(val b: B) : ReaderTest.R<Any?, B>()
    data class J<in A, out B>(val f: suspend (A) -> ReaderTest.R<A, B>) : ReaderTest.R<A, B>()
  }

  // https://www.brinckerhoff.org/clements/csc530-sp08/Readings/kiselyov-2006.pdf
  @Test
  fun example6FromDBDCPaperWithCollapsingOfNearbyContexts() = runTest {
    runCC {
      val p = Reader<Int>()
      val r = Reader<Int>()
      val tag = Prompt<R<Int, Int>>()
      val f = tag.pushPrompt(p.context(1)) {
        r.pushReader(10) {
          tag.shift {
            p.ask() shouldBe 1
            R.J(it)
          } shouldBe 0
          R.R(p.ask() + r.ask())
        }
      }.shouldBeInstanceOf<R.J<Int, Int>>()
      pushContext(p.context(2) + r.context(20)) {
        f.f(0)
      }
    }.shouldBeInstanceOf<R.R<Int>>().b shouldBe 12
  }

  @Test
  fun example6FromDBDCPaper() = runTest {
    runCC {
      val p = Reader<Int>()
      val r = Reader<Int>()
      val f = p.pushReader(1) {
        newReset<R<Int, Int>> {
          r.pushReader(10) {
            shift {
              p.ask() shouldBe 1
              R.J(it)
            } shouldBe 0
            R.R(p.ask() + r.ask())
          }
        }
      }.shouldBeInstanceOf<R.J<Int, Int>>()
      p.pushReader(2) {
        r.pushReader(20) {
          f.f(0)
        }
      }
    }.shouldBeInstanceOf<R.R<Int>>().b shouldBe 12
  }
}