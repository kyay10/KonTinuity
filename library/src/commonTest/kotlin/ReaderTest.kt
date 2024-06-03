import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class ReaderTest {
    @Test
    fun readerMonad() = runTest {
      runReader(1) reader@{
        newReset reset@{
          pushReader(2) {
            this@reset.shift { f -> this@reader.get() }
          }
        }
      } shouldBe 1
    }
}