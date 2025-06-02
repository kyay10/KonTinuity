package io.github.kyay10.kontinuity

import arrow.core.Either.Left
import io.kotest.matchers.shouldBe
import kotlin.test.Test

// From https://github.com/arrow-kt/arrow-core/pull/226
class ArrowTest {
  @Test
  fun yieldAListAndStackSafety() = runTestCC {
    suspend fun <IR : OR, OR> PromptCont<List<Int>, IR, OR>.function() = kotlin.run {
      context(_: Prompt<List<A>, IR, OR>)
      suspend fun <A, IR : OR, OR> MultishotScope<IR>.yield(a: A): Unit = shiftOnce { k -> listOf(a) + k(Unit) }
      for (i in 0..10_000) yield(i)
      emptyList<Int>()
    }
    newReset { function() } shouldBe (0..10_000).toList()
  }

  @Test
  fun shortCircuit() = runTestCC {
    suspend fun <IR : OR, OR> PromptCont<Left<String>, IR, OR>.function(): Nothing = kotlin.run {
      shift { Left("No thank you") }
    }
    newReset { function() } shouldBe Left("No thank you")
  }

  @Test
  fun supportsMultishot() = runTestCC {
    suspend fun <IR : OR, OR> PromptCont<Int, IR, OR>.function() = kotlin.run {
      shift { it(1) + it(2) } + 1
    }
    newReset { function() } shouldBe 5
  }

  @Test
  fun resetTest() = runTestCC {
    suspend fun <IR : OR, OR> PromptCont<Int, IR, OR>.function() = kotlin.run {
      shift { it(1) }
    }
    newReset { function() } shouldBe 1
  }

  // This comes from http://homes.sice.indiana.edu/ccshan/recur/recur.pdf and shows how reset/shift should behave
  @Test
  fun multishotResetShift() = runTestCC {
    suspend fun <IR : OR, OR> PromptCont<List<Char>, IR, OR>.function() = kotlin.run {
      listOf('b') + shift<List<Char>, _, _, _> { f -> listOf('1') + f(f(listOf('c'))) }
    }
    listOf('a') + newReset<List<Char>, _> { function() } shouldBe listOf('a', '1', 'b', 'b', 'c')

    suspend fun <IR : OR, OR> PromptCont<List<Char>, IR, OR>.function2() = kotlin.run {
      listOf('b') + shift<List<Char>, _, _, _> { f -> listOf('1') + f(f(listOf('c'))) }
    }
    listOf('a') + newReset<List<Char>, _> { function2() } shouldBe listOf('a', '1', 'b', 'b', 'c')
  }

  // This also comes from http://homes.sice.indiana.edu/ccshan/recur/recur.pdf and shows that shift surrounds the
  //  captured continuation and the function receiving it with reset.
  @Test
  fun shiftAndControlDistinction() = runTestCC {
    suspend fun <IR : OR, OR> PromptCont<String, IR, OR>.function() = kotlin.run {
      shift { it("") }
      shift { f -> "a" + f("") }
    }
    // TODO this is not very accurate, probably not correct either
    newReset { function() } shouldBe "a"
  }

  @Test
  fun multshotNondet() = runTestCC {
    suspend fun <IR : OR, OR> PromptCont<List<Pair<Int, Int>>, IR, OR>.function() = kotlin.run {
      val i: Int = shift { k -> k(10) + k(20) }
      val j: Int = shift { k -> k(15) + k(25) }
      listOf(i to j)
    }
    newReset { function() } shouldBe listOf(10 to 15, 10 to 25, 20 to 15, 20 to 25)
  }

  @Test
  fun multishotMoreThanTwice() = runTestCC {
    suspend fun <IR : OR, OR> PromptCont<List<Pair<Pair<Int, Int>, Int>>, IR, OR>.function() = kotlin.run {
      val i: Int = shift { k -> k(10) + k(20) }
      val j: Int = shift { k -> k(15) + k(25) }
      val k: Int = shift { k -> k(17) + k(27) }
      listOf(i to j to k)
    }
    newReset { function() } shouldBe listOf(10, 20).flatMap { i ->
      listOf(15, 25).flatMap { j ->
        listOf(17, 27).map { k ->
          i to j to k
        }
      }
    }
  }

  @Test
  fun multishotMoreThanTwiceAndWithMoreMultishotInvocations() = runTestCC {
    suspend fun <IR : OR, OR> PromptCont<List<Pair<Pair<Int, Int>, Int>>, IR, OR>.function() = kotlin.run {
      val i: Int = shift { k -> k(10) + k(20) + k(30) + k(40) + k(50) }
      val j: Int = shift { k -> k(15) + k(25) + k(35) + k(45) + k(55) }
      val k: Int = shift { k -> k(17) + k(27) + k(37) + k(47) + k(57) }
      listOf(i to j to k)
    }
    newReset { function() } shouldBe listOf(10, 20, 30, 40, 50).flatMap { i ->
      listOf(15, 25, 35, 45, 55).flatMap { j ->
        listOf(17, 27, 37, 47, 57).map { k -> i to j to k }
      }
    }
  }

  @Test
  fun multishotIsStacksafeRegardlessOfStackSize() = runTestCC {
    suspend fun <IR : OR, OR> PromptCont<Int, IR, OR>.function() = kotlin.run {
      // bring 10k elements on the stack
      var sum = 0
      for (i0 in 1..10_000) sum += shiftOnce<Int, _, _, _> { it(i0) }

      // run the continuation from here 10k times and sum the results
      // This is about as bad as a scenario as it gets :)
      val j: Int = shift {
        var sum2 = 0
        for (i0 in 1..10_000) sum2 += it(i0)
        sum2
      }

      sum + j
    }
    newReset<Int, _> {
      function()
    }
  }

  @Test
  fun nestedResetCallingBetweenScopes() = runTestCC {
    suspend fun <IR : OR, OR> PromptCont<Unit, IR, OR>.function() = kotlin.run {
      val a: Int = shift { it(5) }
      suspend fun <IR : OR, OR> PromptCont<Int, IR, OR>.function() = kotlin.run {
        val fst = this
        val i: Int = shift { it(10) }
        newReset snd@{
          val j: Int = shift { it(20) }
          val k: Int = context(fst) { shift { it(30) } }
          i + j + k
        }

      }
      a + newReset<Int, _> fst@{ function() } shouldBe 65
    }
    newReset {
      function()
    }
  }

  @Test
  fun nestedResetCallingBetweenALotOfScopes() = runTestCC {
    suspend fun <IR : OR, OR> PromptCont<Unit, IR, OR>.fst() = kotlin.run {
      val fst = this
      val a: Int = shift { it(5) }

      suspend fun <IR : OR, OR> PromptCont<Int, IR, OR>.snd() = kotlin.run {
        val snd = this
        val i: Int = shift { it(10) }

        suspend fun <IR : OR, OR> PromptCont<Int, IR, OR>.third() = kotlin.run {
          val third = this
          val j: Int = shift { it(20) }
          val k: Int = context(fst) { shift { it(30) } } + context(snd) { shift<Int, _, _, _> { it(40) } }
          newReset fourth@{
            val p: Int = shift { it(20) }
            val k2: Int = context(fst) { shift { it(30) } } + context(snd) { shift<Int, _, _, _> { it(40) } }
            val t: Int = context(third) { shift { it(5) } }
            i + j + k + p + k2 + t
          }

        }
        newReset { third() }
      }
      a + newReset<Int, _> { snd() } shouldBe 200
    }
    newReset { fst() }
  }

  @Test
  fun nestedResetCallingBetweenScopesWithShortCircuit() = runTestCC {
    suspend fun <IR : OR, OR> PromptCont<Unit, IR, OR>.function() = kotlin.run {
      val a: Int = shift { it(5) }
      suspend fun <IR : OR, OR> PromptCont<Int, IR, OR>.function() = kotlin.run {
        val fst = this
        val i: Int = shift { it(10) }
        suspend fun <IR : OR, OR> PromptCont<Int, IR, OR>.function() = kotlin.run {
          val j: Int = shift { it(20) }
          val k: Int = context(fst) { shift { 5 } }
          i + j + k
        }
        newReset snd@{ function() }
      }
      a + newReset<Int, _> { function() } shouldBe 10
    }
    newReset { function() }
  }

  @Test
  fun nestedResetCallingBetweenALotOfScopesAndShortCircuit() = runTestCC {
    suspend fun <IR : OR, OR> PromptCont<Unit, IR, OR>.function() = kotlin.run {
      val fst = this
      val a: Int = shift { it(5) }
      suspend fun <IR : OR, OR> PromptCont<Int, IR, OR>.function() = kotlin.run {
        val snd = this
        val i: Int = shift { it(10) }
        suspend fun <IR : OR, OR> PromptCont<Int, IR, OR>.function() = kotlin.run {
          val third = this
          val j: Int = shift { it(20) }
          val k: Int = context(fst) { shift { it(30) } } + context(snd) { shift<Int, _, _, _> { it(40) } }
          suspend fun <IR : OR, OR> PromptCont<Int, IR, OR>.function() = kotlin.run {
            val p: Int = shift { it(20) }
            val k2: Int = context(fst) { shift { it(30) } } + context(snd) { shift<Int, _, _, _> { it(40) } }
            val t: Int = context(third) { shift { 5 } }
            i + j + k + p + k2 + t
          }
          newReset { function() }
        }
        newReset { function() }
      }
      a + newReset<Int, _> { function() } shouldBe 10
    }
    newReset { function() }
  }
}