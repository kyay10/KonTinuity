package io.github.kyay10.kontinuity.stacks

import io.github.kyay10.kontinuity.runTestCC
import io.github.kyay10.kontinuity.shouldEq
import kotlin.test.Test

class StackTest {
  @Test
  fun testLeastPrimeFactor() = runTestCC {
    val iter = leastPrimeFactorSequence().iterator()
    buildList { repeat(9) { add(iter.next()) } } shouldEq
      listOf(2 to 2, 3 to 3, 4 to 2, 5 to 5, 6 to 2, 7 to 7, 8 to 2, 9 to 3, 10 to 2)
  }
}

fun leastPrimeFactorSequence(): Sequence<Pair<Int, Int>> = sequence {
  var primes = iterator {
    var i = 1
    while (i != Int.MAX_VALUE) yield(++i)
  }
  while (true) {
    val candidates = primes
    val prime = candidates.next()
    yield(Pair(prime, prime))
    primes = iterator {
      candidates.forEach { candidate ->
        if (candidate % prime == 0) this@sequence.yield(Pair(candidate, prime)) else yield(candidate)
      }
    }
  }
}
