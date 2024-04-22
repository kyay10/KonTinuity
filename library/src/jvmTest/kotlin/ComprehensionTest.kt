import app.cash.turbine.test
import arrow.core.None
import arrow.core.raise.option
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

@Suppress("UNUSED_VARIABLE")
class ComprehensionTest {
  @Test
  fun singleList() = runTest {
    val list = listOf(1, 2, 3)
    var counter = 0
    val flow = listComprehension {
      option {
        val item by list.bind()
        effect {
          counter++
        }
        item
      }
    }
    flow.test(10.seconds) {
      for (i in list) {
        awaitItem() shouldBe i
      }
      awaitComplete()
    }
    counter shouldBe list.size
  }

  @Test
  fun singleFlow() = runTest {
    val flow1 = flowOf(1, 2, 3).onEach {
      delay(500.milliseconds)
    }
    var counter = 0
    val flow = listComprehension {
      option {
        val item by flow1.bind()
        effect {
          counter++
        }
        item
      }
    }
    flow.test(10.seconds) {
      for (i in flow1.toList()) {
        awaitItem() shouldBe i
      }
      awaitComplete()
    }
    counter shouldBe flow1.toList().size
  }

  @Test
  fun filtering() = runTest {
    val list = listOf(1, 2, 3)
    val flow = listComprehension {
      option {
        val item by list.bind()
        effect {
          if (item == 2) raise(None) else item
        }
      }
    }
    flow.test(10.seconds) {
      for (i in list.filterNot { it == 2 }) {
        awaitItem() shouldBe i
      }
      awaitComplete()
    }
  }

  @Test
  fun lists() = runTest {
    val list1 = listOf(1, Int.MAX_VALUE, 2, Int.MAX_VALUE, 3)
    val list2 = listOf(2, 3, Int.MAX_VALUE, 4)
    val list3 = listOf(3, 4, 5)
    var noObservedCounter = 0
    var firstCounter = 0
    var secondCounter = 0
    var thirdCounter = 0
    val flow = listComprehension {
      option {
        effect {
          noObservedCounter++
        }
        val first by (list1 + Int.MAX_VALUE).bind()
        effect {
          ensure(first != Int.MAX_VALUE)
          firstCounter++
        }
        val second by (list2 + Int.MAX_VALUE).bind()
        effect {
          ensure(second != Int.MAX_VALUE)
          secondCounter++
        }
        val third by list3.bind()
        effect {
          thirdCounter++
        }
        first to second
      }
    }
    flow.test(10.seconds) {
      for (i in list1.filter { it != Int.MAX_VALUE }) {
        for (j in list2.filter { it != Int.MAX_VALUE }) {
          for (k in list3) {
            awaitItem() shouldBe (i to j)
          }
        }
      }
      awaitComplete()
    }
    noObservedCounter shouldBe 1
    firstCounter shouldBe 3
    secondCounter shouldBe 9
    thirdCounter shouldBe 27
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
    val flow = listComprehension {
      option {
        val first by list1.bind()
        effect {
          ensure(first != Int.MAX_VALUE)
          firstCounter++
        }
        val second by list2.bind()
        effect {
          ensure(second != Int.MAX_VALUE)
          secondCounter++
        }
        val third by list3.bind()
        effect {
          thirdCounter++
        }
        first to second
      }
    }
    flow.test(10.seconds) {
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
  }

  @Test
  fun `bind here`() = runTest {
    val list1 = listOf(1, 2, 3)
    val list2 = listOf(2, 3, 4)
    val list3 = listOf(3, 4, 5)
    var firstCounter = 0
    var secondCounter = 0
    var thirdCounter = 0
    val flow = listComprehension {
      option {
        val first = list1.bind().bind()
        effect {
          firstCounter++
        }
        val second = list2.bind().bind()
        effect {
          secondCounter++
        }
        val third = list3.bind().bind()
        effect {
          thirdCounter++
        }
        first to second
      }
    }
    flow.test(10.seconds) {
      for (i in list1) {
        for (j in list2) {
          for (k in list3) {
            awaitItem() shouldBe (i to j)
          }
        }
      }
      awaitComplete()
    }
    firstCounter shouldBe list1.size
    secondCounter shouldBe list1.size * list2.size
    thirdCounter shouldBe list1.size * list2.size * list3.size
  }


  @Test
  fun nested() = runTest {
    val list = listOf(listOf(1, 2), listOf(3, 4), listOf(5, 6))
    var innerCount = 0
    var itemCount = 0
    val flow = listComprehension {
      option {
        val inner by list.bind()
        effect {
          innerCount++
        }
        val item by inner.bind()
        effect {
          itemCount++
        }
        item
      }
    }
    flow.test(10.seconds) {
      for (i in 1..6) {
        awaitItem() shouldBe i
      }
      awaitComplete()
    }
    innerCount shouldBe list.size
    itemCount shouldBe list.sumOf { it.size }
  }
}