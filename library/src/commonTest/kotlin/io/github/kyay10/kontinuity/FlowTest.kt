package io.github.kyay10.kontinuity

import app.cash.turbine.test
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flattenConcat
import kotlinx.coroutines.flow.toList
import kotlin.test.Test

class FlowTest {
  @Test
  fun empty() = runTest {
    val flow = emptyFlow<Int>()
    var counter = 0
    val result = runFlowCC {
      val item = flow.bind()
      counter++
      item
    }
    result.test {
      awaitComplete()
    }
    counter shouldBe 0
  }

  @Test
  fun single() = runTest {
    val flow1 = flowOfWithDelay(1, 2, 3)
    var counter = 0
    val result = runFlowCC {
      val item = flow1.bind()
      counter++
      item
    }
    result.test {
      for (i in flow1.toList()) {
        awaitItem() shouldBe i
      }
      awaitComplete()
    }
    counter shouldBe flow1.toList().size
  }

  @Test
  fun filtering() = runTest {
    val flow = flowOfWithDelay(1, 2, 3)
    val result = runFlowCC {
      val item = flow.bind()
      ensure(item != 2)
      item
    }
    result.test {
      for (i in flow.toList().filter { it != 2 }) {
        awaitItem() shouldBe i
      }
      awaitComplete()
    }
  }

  @Test
  fun multiple() = runTest {
    val flow1 = flowOfWithDelay(1, Int.MAX_VALUE, Int.MAX_VALUE, 2, Int.MAX_VALUE, 3)
    val flow2 = flowOfWithDelay(2, 3, Int.MAX_VALUE, 4)
    val flow3 = flowOfWithDelay(3, 4, 5)
    var firstCounter = 0
    var secondCounter = 0
    var thirdCounter = 0
    val result = runFlowCC {
      val first = flow1.bind()
      ensure(first != Int.MAX_VALUE)
      firstCounter++
      val second = flow2.bind()
      ensure(second != Int.MAX_VALUE)
      secondCounter++
      flow3.bind()
      thirdCounter++
      first to second
    }
    result.test {
      for (i in flow1.toList().filter { it != Int.MAX_VALUE }) {
        for (j in flow2.toList().filter { it != Int.MAX_VALUE }) {
          flow3.toList().forEach { _ ->
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

  @OptIn(ExperimentalCoroutinesApi::class)
  @Test
  fun nested() = runTest {
    val flow = flowOfWithDelay(flowOfWithDelay(1, 2), flowOfWithDelay(3, 4), flowOfWithDelay(5, 6))
    var innerCount = 0
    var itemCount = 0
    val result = runFlowCC {
      val inner = flow.bind()
      innerCount++
      val item = inner.bind()
      itemCount++
      item
    }
    result.test {
      for (i in flow.flattenConcat().toList()) {
        awaitItem() shouldBe i
      }
      awaitComplete()
    }
    innerCount shouldBe flow.toList().size
    itemCount shouldBe flow.toList().sumOf { it.toList().size }
  }

  @Test
  fun ifElse() = runTest {
    val flow = flowOfWithDelay(1, 2, 2, 3)
    val twoElements = flowOfWithDelay(0, 0)
    val result = runFlowCC {
      val x = flow.bind()
      if (x == 2) {
        twoElements.bind()
        "firstBranch"
      } else {
        repeatIteratorless(2) {
          twoElements.bind()
        }
        "secondBranch"
      }
    }
    result.test {
      for (i in flow.toList()) {
        if (i == 2) {
          twoElements.toList().forEach { _ ->
            awaitItem() shouldBe "firstBranch"
          }
        } else {
          twoElements.toList().forEach { _ ->
            twoElements.toList().forEach { _ ->
              awaitItem() shouldBe "secondBranch"
            }
          }
        }
      }
      awaitComplete()
    }
  }

  @Test
  fun forLoops() = runTest {
    val result = runFlowCC {
      (1..10).forEachIteratorless { i ->
        flowOfWithDelay(i, i).bind()
      }
      0
    }
    result.test {
      repeat(1024) {
        awaitItem() shouldBe 0
      }
      awaitComplete()
    }
  }

  @Test
  fun permutations() = runTest {
    val numbers = (1..5).toList()
    val result = runFlowCC {
      numbers.foldRightIteratorless(emptyList<Int>()) { i, acc -> acc.insert(i) }
    }
    result.test {
      for (i in numbers.permutations()) {
        awaitItem() shouldBe i
      }
      awaitComplete()
    }
  }
}