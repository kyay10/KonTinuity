package io.github.kyay10.kontinuity

import kotlin.test.Test

class GeneratorTest {
  suspend fun Generator<Int>.numbers(to: Int) = repeatIteratorless(to + 1) { yield(it) }

  @Test
  fun countTo10() = runTestCC {
    val ints = effectfulIterable { numbers(10) }
    buildList {
      for (i in ints) {
        add(i)
      }
    } shouldEq (0..10).toList()
  }
}

interface EffectfulIterator<A> {
  suspend operator fun next(): A

  suspend operator fun hasNext(): Boolean
}

fun interface EffectfulIterable<A> {
  suspend operator fun iterator(): EffectfulIterator<A>
}

fun <A> effectfulIterable(block: suspend Generator<A>.() -> Unit) = EffectfulIterable {
  runState<(suspend (Unit) -> A)?, _>(null) {
    handle {
      block { element ->
        useOnce { resume ->
          value = {
            resume(Unit)
            element
          }
        }
      }
    }
    object : EffectfulIterator<A> {
      override suspend fun hasNext() = value != null

      override suspend fun next() = value!!.also { value = null }(Unit)
    }
  }
}

operator fun <A> EffectfulIterator<A>.iterator() = this

fun interface Yield<out In, in Out> {
  suspend fun yield(value: Out): In
}

typealias Generator<Out> = Yield<Unit, Out>

context(yield: Yield<In, Out>)
suspend fun <In, Out> yield(value: Out) = yield.yield(value)
