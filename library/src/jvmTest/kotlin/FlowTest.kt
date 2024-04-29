import app.cash.turbine.test
import arrow.core.raise.ensure
import arrow.fx.coroutines.resourceScope
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class FlowTest {
  @Test
  fun singleFlow() = runTest {
    resourceScope {
      val flow1 = flowOf(1, 2, 3).onEach {
        delay(500.milliseconds)
      }
      var counter = 0
      val result = flowReset {
        val item = flow1.bind()
        effect {
          counter++
        }
        item
      }
      result.test(10.seconds) {
        for (i in flow1.toList()) {
          awaitItem() shouldBe i
        }
        awaitComplete()
      }
      counter shouldBe flow1.toList().size
    }
  }

  @Test
  fun flows() = runTest {
    resourceScope {
      val flow1 = listOf(1, Int.MAX_VALUE, Int.MAX_VALUE, 2, Int.MAX_VALUE, 3).asFlow().onEach {
        delay(Random.nextLong(100, 500).milliseconds)
      }
      val flow2 = listOf(2, 3, Int.MAX_VALUE, 4).asFlow().onEach {
        delay(Random.nextLong(100, 500).milliseconds)
      }
      val flow3 = listOf(3, 4, 5).asFlow().onEach {
        delay(Random.nextLong(100, 500).milliseconds)
      }
      var firstCounter = 0
      var secondCounter = 0
      var thirdCounter = 0
      val result = flowReset {
        val first = flow1.bind()
        effect {
          ensure(first != Int.MAX_VALUE) { }
          firstCounter++
        }
        val second = flow2.bind()
        effect {
          ensure(second != Int.MAX_VALUE) { }
          secondCounter++
        }
        flow3.bind()
        effect {
          thirdCounter++
        }
        first to second
      }
      result.test(10.seconds) {
        for (i in flow1.toList().filter { it != Int.MAX_VALUE }) {
          for (j in flow2.toList().filter { it != Int.MAX_VALUE }) {
            for (k in flow3.toList()) {
              awaitItem() shouldBe (i to j)
            }
          }
        }
        awaitComplete()
      }
      firstCounter shouldBe 3
      secondCounter shouldBe 9
      thirdCounter shouldBe 27
    }
  }
}