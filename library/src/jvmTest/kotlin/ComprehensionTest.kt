import androidx.compose.runtime.getValue
import app.cash.turbine.test
import arrow.core.None
import arrow.core.raise.option
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

class ComprehensionTest {
  @Test
  fun singleList() = runTest {
    val list = listOf(1, 2, 3)
    var counter = 0
    val flow = listComprehension {
      option {
        val item by list.bind()
        effect {
          item
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
  fun filtering() = runTest {
    val list = listOf(1, 2, 3)
    val flow = listComprehension {
      option {
        val item by list.bind()
        if (item == 2) raise(None) else item
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
    val list1 = listOf(1, 2, 3)
    val list2 = listOf(2, 3, 4)
    val list3 = listOf(3, 4, 5)
    var firstCounter = 0
    var secondCounter = 0
    var firstAndSecondCounter = 0
    val flow = listComprehension {
      option {
        val first by list1.bind()
        effect {
          println("first: $first")
          firstCounter++
        }
        val second by list2.bind()
        effect {
          println("second: $second")
          secondCounter++
        }
        effect {
          println("first: $first, second: $second")
          firstAndSecondCounter++
        }
        val third = list3.bindHere()
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
    firstAndSecondCounter shouldBe list1.size * list2.size
  }

  @Test
  fun `bind here`() = runTest {
    val list1 = listOf(1, 2, 3)
    val list2 = listOf(2, 3, 4)
    val list3 = listOf(3, 4, 5)
    val flow = listComprehension {
      option {
        val first = list1.bindHere()
        val second = list2.bindHere()
        val third = list3.bindHere()
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
  }

  @Test
  fun nested() = runTest {
    val list = listOf(listOf(1, 2), listOf(3, 4), listOf(5, 6))
    var innerCount = 0
    var itemCount = 0
    val flow = listComprehension {
      option {
        val inner by list.bind()
        val item by inner.bind()
        effect {
          inner
          innerCount++
        }
        effect {
          item
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