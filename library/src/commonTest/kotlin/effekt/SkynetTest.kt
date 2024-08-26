package effekt

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import runTestCC
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes

// skynet benchmark:
//    https://github.com/atemerev/skynet
class SkynetTest {
  // not using effects at all
  @Test
  fun skynetNoEffects() = runTestCC(UnconfinedTestDispatcher(), timeout = 10.minutes) {
    suspend fun skynet(num: Int, size: Int, div: Int): Long {
      if (size <= 1) return num.toLong()
      val children = Array(div) {
        val subNum = num + it * (size / div)
        Fibre.create {
          skynet(subNum, size / div, div)
        }
      }
      return children.sumOf {
        it.resume()
        it.result
      }
    }

    val f = Fibre.create { skynet(0, 1_000_000, 10) }
    f.resume()
    f.result shouldBe 499_999_500_000L
  }

  // not suspending so not using algebraic effects at all.
  @Test
  fun skynetOverhead() = runTestCC {
    suspend fun skynet(num: Int, size: Int, div: Int): Long {
      if (size <= 1) return num.toLong()
      val children = Array<suspend () -> Long>(div) {
        val subNum = num + it * (size / div)
        { skynet(subNum, size / div, div) }
      }
      return children.sumOf { it() }
    }
    skynet(0, 1_000_000, 10) shouldBe 499_999_500_000L
  }

  // not using fibers, but cooperative multitasking with a scheduler
  //
  // we capture too large parts of the stack in this implementation.
  // fibers only capture "their" stack not everything up to the scheduler.
  //
  // we also perform "busy waiting" which is pretty slow since it captures the
  // continuation on every wait cycle ...
  @Test
  fun skynetScheduler() = runTestCC(UnconfinedTestDispatcher(), timeout = 10.minutes) {
    data class SkynetData(var sum: Long, var returned: Int)

    suspend fun Scheduler2.skynet(num: Int, size: Int, div: Int): Long {
      if (size <= 1) return num.toLong()
      val data = SkynetData(0, 0)
      repeat(div) {
        val subNum = num + it * (size / div)
        fork {
          val res = skynet(subNum, size / div, div)
          data.returned++
          data.sum += res
        }
      }
      while (data.returned < div) {
        yield()
      }
      yield()
      return data.sum
    }

    scheduler2 { skynet(0, 1_000_000, 10) shouldBe 499_999_500_000L }
  }

  // every fiber suspends once before returning the result.
  @Test
  fun skynetSuspend() = runTestCC(UnconfinedTestDispatcher(), timeout = 10.minutes) {
    suspend fun Suspendable.skynet(num: Int, size: Int, div: Int): Long {
      if (size <= 1) return num.toLong().also { suspend() }
      val children = Array(div) {
        val subNum = num + it * (size / div)
        Fibre.create {
          skynet(subNum, size / div, div)
        }
      }
      children.forEach { it.resume() }
      return children.sumOf {
        it.resume()
        it.result
      }.also { suspend() }
    }

    val f = Fibre.create { skynet(0, 1_000_000, 10) }
    f.resume()
    f.resume()
    f.result shouldBe 499_999_500_000L
  }
}
