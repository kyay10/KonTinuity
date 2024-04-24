import androidx.compose.runtime.Composable
import app.cash.turbine.test
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlin.random.Random
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime

@Suppress("UNUSED_VARIABLE")
class ComprehensionTest {
  @Composable
  fun <T> Reset<List<T>>.yield(x: T) = shift { k -> listOf(x) + k(Unit) }

  @Test
  fun simpleContinuations() = runTest {
    // Examples from https://en.wikipedia.org/wiki/Delimited_continuation
    reset<Int> {
      maybe {
        val value by shift { k -> k(5) }
        val value2 by shift { k -> k(value + 1) }
        value2
      }
    } * 2 shouldBe 12
    reset {
      maybe {
        val value by shift { k -> k(k(4)) }
        value * 2
      }
    } + 1 shouldBe 17
    reset {
      maybe {
        yield(1).bind()
        yield(2).bind()
        yield(3).bind()
        emptyList()
      }
    } shouldBe listOf(1, 2, 3)
    // Example from https://www.scala-lang.org/old/node/2096
    @Composable
    fun Reset<Int>.foo(): Maybe<Int> = maybe {
      1 + shift { k -> k(k(k(7))) }.bind()
    }

    @Composable
    fun Reset<Int>.bar(): Maybe<Int> = maybe {
      2 * foo().bind()
    }

    suspend fun baz(): Int = reset {
      bar()
    }
    baz() shouldBe 70
  }

  @Test
  fun nestedContinuations() = runTest {}

  @OptIn(ExperimentalTime::class, ExperimentalCoroutinesApi::class)
  @Test
  fun await() = runTest {
    val mark = testTimeSource.markNow()
    val a = async {
      delay(500.milliseconds)
      5
    }
    val result = reset {
      maybe {
        val value by await {
          delay(200.milliseconds)
          a.await()
        }
        val value2 by await { value + 1 }
        value2
      }
    }
    mark.elapsedNow() shouldBe 500.milliseconds
    result shouldBe 6
  }

  @Test
  fun singleList() = runTest {
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
  fun singleFlow() = runTest {
    val flow1 = flowOf(1, 2, 3).onEach {
      delay(500.milliseconds)
    }
    var counter = 0
    val result = lazyReset<Flow<Int>> {
      maybe {
        val item by flow1.bind()
        effect {
          counter++
        }
        flowOf(item)
      }
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
  fun lists() = runTest {
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
      maybe {
        val first by list1.bind()
        effect {
          if (first == Int.MAX_VALUE) return@maybe emptyFlow()
          firstCounter++
        }
        val second by list2.bind()
        effect {
          if (second == Int.MAX_VALUE) return@maybe emptyFlow()
          secondCounter++
        }
        val third by list3.bind()
        effect {
          thirdCounter++
        }
        flowOf(first to second)
      }
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

  @Test
  fun bindHere() = runTest {
    val list1 = listOf(1, 2, 3)
    val list2 = listOf(2, 3, 4)
    val list3 = listOf(3, 4, 5)
    var firstCounter = 0
    var secondCounter = 0
    var thirdCounter = 0
    val result = reset<List<Pair<Int, Int>>> {
      maybe {
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
        listOf(first to second)
      }
    }
    result shouldBe list1.flatMap { first ->
      list2.flatMap { second ->
        list3.map { _ ->
          first to second
        }
      }
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
    var counter = 0
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