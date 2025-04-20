package io.github.kyay10.kontinuity

import io.kotest.matchers.sequences.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class ListTest {
  @Test
  fun empty() = runTestCC {
    val list = emptyList<Int>()
    var counter = 0
    val result = runList {
      val item = list.bind()
      counter++
      item
    }
    result shouldBe list
    counter shouldBe 0
  }

  @Test
  fun single() = runTestCC {
    val list = listOf(1, 2, 3)
    var counter = 0
    val result = runList {
      val item = list.bind()
      counter++
      item
    }
    result shouldBe list
    counter shouldBe list.size
  }

  @Test
  fun filtering() = runTestCC {
    val list = listOf(1, 2, 3)
    val result = runList {
      val item = list.bind()
      ensure(item != 2)
      item
    }
    result shouldBe list.filter { it != 2 }
  }

  @Test
  fun multiple() = runTestCC {
    val list1 = listOf(1, Int.MAX_VALUE, 2, Int.MAX_VALUE, 3, Int.MAX_VALUE)
    val list2 = listOf(2, 3, Int.MAX_VALUE, 4, Int.MAX_VALUE)
    val list3 = listOf(3, 4, 5)
    var noObservedCounter = 0
    var firstCounter = 0
    var secondCounter = 0
    var thirdCounter = 0
    val result = runList {
      noObservedCounter++
      val first = list1.bind()
      ensure(first != Int.MAX_VALUE)
      firstCounter++
      val second = list2.bind()
      ensure(second != Int.MAX_VALUE)
      secondCounter++
      list3.bind()
      thirdCounter++
      first to second
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
  fun nested() = runTestCC {
    val list = listOf(listOf(1, 2), listOf(3, 4), listOf(5, 6))
    var innerCount = 0
    var itemCount = 0
    val result = runList {
      val inner = list.bind()
      innerCount++
      val item = inner.bind()
      itemCount++
      item
    }
    result shouldBe list.flatten()
    innerCount shouldBe list.size
    itemCount shouldBe list.sumOf { it.size }
  }

  @Test
  fun ifElse() = runTestCC {
    val list = listOf(1, 2, 2, 3)
    val twoElements = listOf(0, 0)
    val result = runList {
      val x = list.bind()
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
    result shouldBe list.flatMap {
      if (it == 2) twoElements.map { "firstBranch" } else twoElements.flatMap {
        twoElements.map { "secondBranch" }
      }
    }
  }

  @Test
  fun forLoops() = runTestCC {
    val result = runList {
      (1..10).forEachIteratorless { i ->
        listOf(i, i).bind()
      }
      0
    }
    result shouldBe List(1024) { 0 }
  }

  @Test
  fun allEightBitPatterns() = runTestCC {
    val result = runList {
      replicate(8) {
        choose(0, 1)
      }
    }
    result.map { it.joinToString("").toInt(2) } shouldBe (0..255).toList()
  }

  @Test
  fun allEightBitPatternsWithOnlyChange() = runTestCC {
    val result = buildString {
      runList {
        repeatIteratorless(8) {
          append(choose(0, 1))
        }
        appendLine()
      }
    }.lines().drop(1).dropLast(1)
    result shouldBe List(256) { it.toString(2).padStart(8, '0') }.zipWithNext { a, b ->
      b.removePrefix(a.commonPrefixWith(b))
    }
  }

  @Test
  fun permutations() = runTestCC {
    val numbers = (1..5).toList()
    val result = runList {
      numbers.foldRight(emptyList<Int>()) { i, acc -> acc.insert(i) }
    }
    result.asSequence().shouldContainExactly(numbers.permutations())
  }
}