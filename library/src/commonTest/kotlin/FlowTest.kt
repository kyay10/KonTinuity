import app.cash.turbine.test
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.emptyFlow
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
    val flow1 = flowOf(1, 2, 3).onEach {
      delay(500.milliseconds)
    }
    var counter = 0
    val result = lazyReset<Flow<Int>> {
      val item = flow1.bind()
      effect {
        counter++
      }
      flowOf(item)
    }.await()
    result.test(10.seconds) {
      for (i in flow1.toList()) {
        awaitItem() shouldBe i
      }
      awaitComplete()
    }
    counter shouldBe flow1.toList().size
    coroutineContext.cancelChildren()
  }

  @Test
  fun flows() = runTest {
    val list1 = listOf(1, Int.MAX_VALUE, Int.MAX_VALUE, 2, Int.MAX_VALUE, 3).asFlow().onEach {
      delay(Random.nextLong(100, 500).milliseconds)
    }
    val list2 = listOf(2, 3, Int.MAX_VALUE, 4).asFlow().onEach {
      delay(Random.nextLong(100, 500).milliseconds)
    }
    val list3 = listOf(3, 4, 5).asFlow().onEach {
      delay(Random.nextLong(100, 500).milliseconds)
    }
    var firstCounter = 0
    var secondCounter = 0
    var thirdCounter = 0
    val result = lazyReset<Flow<Pair<Int, Int>>> {
      val first = list1.bind()
      effect {
        if (first == Int.MAX_VALUE) return@lazyReset emptyFlow()
        firstCounter++
      }
      val second = list2.bind()
      effect {
        if (second == Int.MAX_VALUE) return@lazyReset emptyFlow()
        secondCounter++
      }
      list3.bind()
      effect {
        thirdCounter++
      }
      flowOf(first to second)
    }.await()
    result.test(10.seconds) {
      for (i in list1.toList().filter { it != Int.MAX_VALUE }) {
        for (j in list2.toList().filter { it != Int.MAX_VALUE }) {
          for (k in list3.toList()) {
            awaitItem() shouldBe (i to j)
          }
        }
      }
      awaitComplete()
    }
    firstCounter shouldBe 3
    secondCounter shouldBe 9
    thirdCounter shouldBe 27
    coroutineContext.cancelChildren()
  }
}