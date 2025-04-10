package io.github.kyay10.kontinuity.effekt

import io.github.kyay10.kontinuity.runTestCC
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class GeneratorTest {
  suspend fun Generator<Int>.numbers(to: Int) {
    var i = 0
    while (i <= to) {
      yield(i++)
    }
  }

  suspend fun Generator<Int>.numbersFlip(to: Int, amb: Amb) {
    var i = 0
    while (i <= to) {
      yield(if (amb.flip()) i else -i)
      i++
    }
  }

  @Test
  fun countTo10() = runTestCC {
    val ints = effectfulIterable {
      numbers(10)
    }
    buildList {
      for (i in ints) {
        add(i)
      }
    } shouldBe (0..10).toList()
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
          numbersFlip(2, this@ambList)
        }
        for (i in ints) {
          add(i)
        }
      }.size shouldBe 8
    } shouldBe listOf(0, 1, 2, 1, -2, 0, -1, 2, -1, -2, 0, 1, 2, 1, -2, 0, -1, 2, -1, -2)
  }
}

interface EffectfulIterator<A> {
  suspend operator fun next(): A
  suspend operator fun hasNext(): Boolean
}

fun interface EffectfulIterable<A> {
  suspend operator fun iterator(): EffectfulIterator<A>
}

fun <A> effectfulIterable(body: suspend Generator<A>.() -> Unit) = EffectfulIterable<A> {
  EffectfulIteratorImpl<A>(handle {
    body(Iterate(this))
    EffectfulIteratorStep.Done
  })
}

class EffectfulIteratorImpl<A>(var current: EffectfulIteratorStep<A>) : EffectfulIterator<A> {
  override suspend fun next(): A {
    return when (val step = current) {
      is EffectfulIteratorStep.Value -> {
        current = step.next(Unit)
        step.value
      }

      EffectfulIteratorStep.Done -> throw NoSuchElementException()
    }
  }

  override suspend fun hasNext(): Boolean {
    return when (current) {
      is EffectfulIteratorStep.Value -> true
      EffectfulIteratorStep.Done -> false
    }
  }
}

sealed interface EffectfulIteratorStep<out A> {
  data class Value<A>(val value: A, val next: Cont<Unit, EffectfulIteratorStep<A>>) : EffectfulIteratorStep<A>
  object Done : EffectfulIteratorStep<Nothing>
}

operator fun <A> EffectfulIterator<A>.iterator() = this

fun interface Generator<A> {
  suspend fun yield(value: A)
}

class Iterate<A>(prompt: HandlerPrompt<EffectfulIteratorStep<A>>) : Handler<EffectfulIteratorStep<A>> by prompt,
  Generator<A> {
  override suspend fun yield(value: A): Unit = use {
    EffectfulIteratorStep.Value(value, it)
  }
}