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
          println("item: $item")
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
        effect {
          println("item: $item")
        }
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
    val list1 = listOf(1, Int.MAX_VALUE, 2, Int.MAX_VALUE, 3)
    val list2 = listOf(2, 3, Int.MAX_VALUE, 4)
    val list3 = listOf(3, 4, 5)
    var firstCounter = 0
    var secondCounter = 0
    var thirdCounter = 0
    val flow = listComprehension {
      option {
        val first by (list1 + Int.MAX_VALUE).bind()
        ensure(first != Int.MAX_VALUE)
        effect("first") {
          firstCounter++
        }
        val second by (list2 + Int.MAX_VALUE).bind()
        ensure(second != Int.MAX_VALUE)
        effect("second") {
          secondCounter++
        }
        val third by list3.bind()
        effect("third") {
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
    firstCounter shouldBe 3
    secondCounter shouldBe 9
    thirdCounter shouldBe 27
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
        effect {
          println("inner: $inner")
          innerCount++
        }
        val item by inner.bind()
        effect {
          println("item: $item")
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