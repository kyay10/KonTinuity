package io.github.kyay10.kontinuity

import io.kotest.matchers.sequences.shouldContainExactly
import kotlinx.collections.immutable.persistentListOf
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
    result shouldEq list
    counter shouldEq 0
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
    result shouldEq list
    counter shouldEq list.size
  }

  @Test
  fun filtering() = runTestCC {
    val list = listOf(1, 2, 3)
    val result = runList {
      val item = list.bind()
      ensure(item != 2)
      item
    }
    result shouldEq list.filter { it != 2 }
  }

  @Test
  fun multiple() = runTestCC {
    val list1 = listOf(1, Int.MAX_VALUE, 2, Int.MAX_VALUE, 3, Int.MAX_VALUE)
    val list2 = listOf(2, 3, Int.MAX_VALUE, 4, Int.MAX_VALUE)
    val list3 = listOf(Unit, Unit, Unit)
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
    result shouldEq list1.filter { it != Int.MAX_VALUE }.flatMap { first ->
      list2.filter { it != Int.MAX_VALUE }.flatMap { second ->
        list3.map { _ ->
          first to second
        }
      }
    }
    noObservedCounter shouldEq 1
    firstCounter shouldEq 3
    secondCounter shouldEq 9
    thirdCounter shouldEq 27
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
    result shouldEq list.flatten()
    innerCount shouldEq list.size
    itemCount shouldEq list.sumOf { it.size }
  }

  @Test
  fun ifElse() = runTestCC {
    val list = listOf(1, 2, 2, 3)
    val twoElements = listOf(Unit, Unit)
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
    result shouldEq list.flatMap {
      if (it == 2) twoElements.map { "firstBranch" } else twoElements.flatMap {
        twoElements.map { "secondBranch" }
      }
    }
  }

  @Test
  fun forLoops() = runTestCC {
    val result = runList {
      repeatIteratorless(10) { listOf(Unit, Unit).bind() }
      0
    }
    result shouldEq List(1024) { 0 }
  }

  @Test
  fun allEightBitPatterns() = runTestCC {
    val result = runList {
      replicate(8) {
        choose(0, 1)
      }
    }
    result.map { it.joinToString("").toInt(2) } shouldEq (0..255).toList()
  }

  @Test
  fun allEightBitPatternsWithOnlyChange() = runTestCC {
    val result = buildString {
      val _ = runList {
        repeatIteratorless(8) { append(choose(0, 1)) }
        appendLine()
      }
    }.lines().drop(1).dropLast(1)
    result shouldEq List(256) { it.toString(2).padStart(8, '0') }.zipWithNext { a, b ->
      b.removePrefix(a.commonPrefixWith(b))
    }
  }

  @Test
  fun permutations() = runTestCC {
    val numbers = (1..5).toList()
    val result = runList {
      numbers.foldRightIteratorless(persistentListOf<Int>()) { i, acc -> acc.insert(i) }
    }
    result.asSequence().shouldContainExactly(numbers.permutations())
  }
}