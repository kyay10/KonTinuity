import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class ListTest {
  @Test
  fun empty() = runTest {
    val list = emptyList<Int>()
    var counter = 0
    val result = reset<List<Int>> {
      val item = list.bind()
      effect {
        counter++
      }
      listOf(item)
    }
    result shouldBe list
    counter shouldBe list.size
  }

  @Test
  fun single() = runTest {
    val list = listOf(1, 2, 3)
    var counter = 0
    val result = reset<List<Int>> {
      val item = list.bind()
      effect {
        counter++
      }
      listOf(item)
    }
    result shouldBe list
    counter shouldBe list.size
  }

  @Test
  fun filtering() = runTest {
    val list = listOf(1, 2, 3)
    val result = reset<List<Int>> {
      val item = list.bind()
      effect {
        if (item == 2) emptyList() else listOf(item)
      }
    }
    result shouldBe list.filter { it != 2 }
  }

  @Test
  fun multiple() = runTest {
    val list1 = listOf(1, Int.MAX_VALUE, 2, Int.MAX_VALUE, 3, Int.MAX_VALUE)
    val list2 = listOf(2, 3, Int.MAX_VALUE, 4, Int.MAX_VALUE)
    val list3 = listOf(3, 4, 5)
    var noObservedCounter = 0
    var firstCounter = 0
    var secondCounter = 0
    var thirdCounter = 0
    val result = reset<List<Pair<Int, Int>>> {
      effect {
        noObservedCounter++
      }
      val first = list1.bind()
      effect {
        if (first == Int.MAX_VALUE) return@reset emptyList()
        firstCounter++
      }
      val second = list2.bind()
      effect {
        if (second == Int.MAX_VALUE) return@reset emptyList()
        secondCounter++
      }
      list3.bind()
      effect {
        thirdCounter++
      }
      listOf(first to second)
    }
    result shouldBe list1.filter { it != Int.MAX_VALUE }.flatMap { first ->
      list2.filter { it != Int.MAX_VALUE }.flatMap { second ->
        list3.map { _ ->
          first to second
        }
      }
    }
    noObservedCounter shouldBe 1
    firstCounter shouldBe 3
    secondCounter shouldBe 9
    thirdCounter shouldBe 27
  }

  @Test
  fun nested() = runTest {
    val list = listOf(listOf(1, 2), listOf(3, 4), listOf(5, 6))
    var innerCount = 0
    var itemCount = 0
    val result = reset<List<Int>> {
      val inner = list.bind()
      effect {
        innerCount++
      }
      val item = inner.bind()
      effect {
        itemCount++
      }
      listOf(item)
    }
    result shouldBe list.flatten()
    innerCount shouldBe list.size
    itemCount shouldBe list.sumOf { it.size }
  }

  @Test
  fun ifElse() = runTest {
    val list = listOf(1, 2, 2, 3)
    val twoElements = listOf(0, 0)
    val result = reset<List<String>> {
      val x = listOf(1, 2, 2, 3).bind()
      if (x == 2) {
        twoElements.bind()
        listOf("firstBranch")
      } else {
        repeat(2) {
          twoElements.bind()
        }
        listOf("secondBranch")
      }
    }
    result shouldBe list.flatMap {
      if (it == 2) twoElements.map { "firstBranch" } else twoElements.flatMap {
        twoElements.map { "secondBranch" }
      }
    }
  }

  @Test
  fun forLoops() = runTest {
    val result = reset<List<Int>> {
      for (i in 1..10) {
        listOf(i, i).bind()
      }
      listOf(0)
    }
    result shouldBe List(1024) { 0 }
  }
}