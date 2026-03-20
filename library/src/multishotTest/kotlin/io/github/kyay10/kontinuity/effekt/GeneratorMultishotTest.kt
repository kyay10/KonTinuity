package io.github.kyay10.kontinuity.effekt

import io.github.kyay10.kontinuity.runTestCC
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class GeneratorMultishotTest {
  context(amb: Amb)
  suspend fun Generator<Int>.numbersFlip(to: Int) {
    var i = 0
    while (i <= to) {
      yield(if (amb.flip()) i else -i)
      i++
    }
  }

  @Test
  fun flipCount() = runTestCC {
    ambList {
      val intsIterator = effectfulIterable {
        numbers(10)
      }.iterator()
      intsIterator.next() shouldBe 0
      intsIterator.next() shouldBe 1
      if (flip()) {
        intsIterator.next() shouldBe 2
        intsIterator.next() shouldBe 3
      } else {
        // since `intsIterator` is mutated outside of the scope of the ambient handler state.
        intsIterator.next() shouldBe 4
      }
    }.size shouldBe 2
  }

  @Test
  fun flipCountInside() = runTestCC {
    buildList {
      ambList {
        val ints = effectfulIterable {
          numbersFlip(2)
        }
        for (i in ints) {
          add(i)
        }
      }.size shouldBe 8
    } shouldBe listOf(0, 1, 2, 1, -2, 0, -1, 2, -1, -2, 0, 1, 2, 1, -2, 0, -1, 2, -1, -2)
  }
}