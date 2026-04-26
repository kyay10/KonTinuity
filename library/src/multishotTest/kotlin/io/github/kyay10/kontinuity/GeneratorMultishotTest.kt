package io.github.kyay10.kontinuity

import kotlin.test.Test

class GeneratorMultishotTest {
  context(amb: Amb)
  suspend fun Generator<Int>.numbersFlip(to: Int) = repeatIteratorless(to + 1) { yield(if (amb.flip()) it else -it) }

  @Test
  fun flipCount() = runTestCC {
    ambList {
        val intsIterator = effectfulIterable { numbers(10) }.iterator()
        intsIterator.next() shouldEq 0
        intsIterator.next() shouldEq 1
        if (flip()) {
          intsIterator.next() shouldEq 2
          intsIterator.next() shouldEq 3
        } else {
          // since `intsIterator` is mutated outside the scope of the ambient handler state.
          intsIterator.next() shouldEq 4
        }
      }
      .size shouldEq 2
  }

  @Test
  fun flipCountInside() = runTestCC {
    buildList {
      ambList {
          val ints = effectfulIterable { numbersFlip(2) }
          for (i in ints) add(i)
        }
        .size shouldEq 8
    } shouldEq listOf(0, 1, 2, 1, -2, 0, -1, 2, -1, -2, 0, 1, 2, 1, -2, 0, -1, 2, -1, -2)
  }
}
