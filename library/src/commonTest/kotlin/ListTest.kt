import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class ListTest {
  @Test
  fun empty() = runTest {
    val list = emptyList<Int>()
    var counter = 0
    val result = reset<List<Int>> {
      maybe {
        val item by list.bind()
        effect {
          counter++
        }
        listOf(item)
      }
    }
    result shouldBe list
    counter shouldBe list.size
  }

  @Test
  fun single() = runTest {
    val list = listOf(1, 2, 3)
    var counter = 0
    val result = reset<List<Int>> {
      maybe {
        val item by list.bind()
        effect {
          counter++
        }
        listOf(item)
      }
    }
    result shouldBe list
    counter shouldBe list.size
  }

  @Test
  fun filtering() = runTest {
    val list = listOf(1, 2, 3)
    val result = reset<List<Int>> {
      maybe {
        val item by list.bind()
        effect {
          if (item == 2) emptyList() else listOf(item)
        }
      }
    }
    result shouldBe list.filter { it != 2 }
  }

  @Test
  fun multiple() = runTest {
    val list1 = listOf(1, Int.MAX_VALUE, 2, Int.MAX_VALUE, 3)
    val list2 = listOf(2, 3, Int.MAX_VALUE, 4)
    val list3 = listOf(3, 4, 5)
    var noObservedCounter = 0
    var firstCounter = 0
    var secondCounter = 0
    var thirdCounter = 0
    val result = reset<List<Pair<Int, Int>>> {
      maybe {
        effect {
          noObservedCounter++
        }
        val first by (list1 + Int.MAX_VALUE).bind()
        effect {
          if (first == Int.MAX_VALUE) return@maybe emptyList()
          firstCounter++
        }
        val second by (list2 + Int.MAX_VALUE).bind()
        effect {
          if (second == Int.MAX_VALUE) return@maybe emptyList()
          secondCounter++
        }
        val third by list3.bind()
        effect {
          thirdCounter++
        }
        listOf(first to second)
      }
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
      maybe {
        val inner by list.bind()
        effect {
          innerCount++
        }
        val item by inner.bind()
        effect {
          itemCount++
        }
        listOf(item)
      }
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
      maybe {
        val x by listOf(1, 2, 2, 3).bind()
        // Compose restriction: no throwing in if-else
        ifSafe(x == 2) {
          val y by twoElements.bind()
          listOf("firstBranch")
        }.elseSafe {
          val y by twoElements.bind()
          val z by twoElements.bind()
          listOf("secondBranch")
        }.getOrThrow()
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
      maybe {
        // Compose restriction: no throwing in for loops
        // One can also do `val x = listOf(i, i).bind().getOrElse { return@reset nothing() }`
        (1..10).forEachSafe { i ->
          val x by listOf(i, i).bind()
        }.getOrThrow()
        listOf(0)
      }
    }
    result shouldBe List(1024) { 0 }
  }
}