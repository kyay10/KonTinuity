package io.github.kyay10.kontinuity

import kotlin.random.Random
import kotlin.test.Test

class ProbabilisticTest {
  @Test
  fun tracing() = runTestCC {
    repeat(10) {
      tracing {
        when {
          flip() -> if (flip()) 1 else 2
          flip() -> 3
          else -> 4
        }
      }
    }
  }

  suspend fun Prob.falsePositive(): Boolean {
    val sick = bernoulli(0.01)
    ensure(
      if (sick) {
        bernoulli(0.99)
      } else {
        bernoulli(0.1)
      }
    )
    return sick
  }

  @Test
  fun falsePositive() = runTestCC {
    probabilistic { falsePositive() } shouldEq listOf(Weighted(false, 0.099), Weighted(true, 0.0099))
  }
}

suspend fun <R> probabilistic(body: suspend Prob.() -> R): List<Weighted<R>> =
  runState(1.0) {
    handle {
      val res =
        body(
          object : Prob, Exc by exc {
            override suspend fun flip(): Boolean = use { k ->
              val previous = value
              k(false).also { value = previous } + k(true)
            }

            override fun factor(p: Double) {
              value *= p
            }
          }
        )
      listOf(Weighted(res, value))
    }
  }

data class Weighted<T>(val value: T, val weight: Double)

suspend fun tracing(body: suspend Amb.() -> Int) =
  runListBuilder<suspend () -> Int, _> {
    add {
      handle {
        body {
          use { k ->
            val choice = Random.nextBoolean()
            add { k(!choice) }
            k(choice)
          }
        }
      }
    }
    // ok some very specialized sampling:
    //   We are trying to find a result which is == 1
    while (removeRandom()() != 1) check(isNotEmpty()) { "Could not find samples to produce expected result" }
  }

fun <E> MutableList<E>.removeRandom() = removeAt(Random.nextInt(0, size))

interface Prob : Amb, Exc {
  fun factor(p: Double)
}

// could also be the primitive effect op and `flip = bernoulli(0.5)`
suspend fun Prob.bernoulli(p: Double): Boolean =
  if (flip()) {
    factor(p)
    true
  } else {
    factor(1 - p)
    false
  }
