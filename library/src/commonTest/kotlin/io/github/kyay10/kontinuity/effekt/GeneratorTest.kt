package io.github.kyay10.kontinuity.effekt

import io.github.kyay10.kontinuity.MultishotScope
import io.github.kyay10.kontinuity.SubCont
import io.github.kyay10.kontinuity.runTestCC
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class GeneratorTest {
  context(gen: Generator<Int>)
  suspend fun MultishotScope.numbers(to: Int) {
    var i = 0
    while (i <= to) {
      yield(i++)
    }
  }

  context(amb: Amb, gen: Generator<Int>)
  suspend fun MultishotScope.numbersFlip(to: Int) {
    var i = 0
    while (i <= to) {
      yield(if (flip()) i else -i)
      i++
    }
  }

  @Test
  fun countTo10() = runTestCC {
    val ints = effectfulIterable {
      numbers(10)
    }
    buildList {
      forEach(ints) {
        add(it)
      }
    } shouldBe (0..10).toList()
  }

  @Test
  fun flipCount() = runTestCC {
    ambList {
      val intsIterator = iterator(effectfulIterable {
        numbers(10)
      })
      next(intsIterator) shouldBe 0
      next(intsIterator) shouldBe 1
      if (flip()) {
        next(intsIterator) shouldBe 2
        next(intsIterator) shouldBe 3
      } else {
        // since `intsIterator` is mutated outside of the scope of the ambient handler state.
        next(intsIterator) shouldBe 4
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
        forEach(ints) {
          add(it)
        }
      }.size shouldBe 8
    } shouldBe listOf(0, 1, 2, 1, -2, 0, -1, 2, -1, -2, 0, 1, 2, 1, -2, 0, -1, 2, -1, -2)
  }
}

interface EffectfulIterator<A> {
  suspend fun MultishotScope.next(): A
  suspend fun MultishotScope.hasNext(): Boolean
}

suspend fun <A> MultishotScope.next(it: EffectfulIterator<A>): A = with(it) { next() }
suspend fun <A> MultishotScope.hasNext(it: EffectfulIterator<A>): Boolean = with(it) { hasNext() }

fun interface EffectfulIterable<A> {
  suspend fun MultishotScope.iterator(): EffectfulIterator<A>
}

suspend fun <A> MultishotScope.forEach(e: EffectfulIterable<A>, block: suspend MultishotScope.(A) -> Unit) {
  val it = iterator(e)
  while (hasNext(it)) {
    block(next(it))
  }
}
suspend fun <A> MultishotScope.iterator(e: EffectfulIterable<A>): EffectfulIterator<A> = with(e) { iterator() }

fun <A> effectfulIterable(body: suspend context(Generator<A>) MultishotScope.() -> Unit) = EffectfulIterable {
  EffectfulIteratorImpl(handle {
    body(Iterate(given<HandlerPrompt<EffectfulIteratorStep<A>>>()), this)
    EffectfulIteratorStep.Done
  })
}

class EffectfulIteratorImpl<A>(var current: EffectfulIteratorStep<A>) : EffectfulIterator<A> {
  override suspend fun MultishotScope.next(): A {
    return when (val step = current) {
      is EffectfulIteratorStep.Value -> {
        current = step.next(Unit)
        step.value
      }

      EffectfulIteratorStep.Done -> throw NoSuchElementException()
    }
  }

  override suspend fun MultishotScope.hasNext(): Boolean {
    return when (current) {
      is EffectfulIteratorStep.Value -> true
      EffectfulIteratorStep.Done -> false
    }
  }
}

sealed interface EffectfulIteratorStep<out A> {
  data class Value<A>(val value: A, val next: SubCont<Unit, EffectfulIteratorStep<A>>) : EffectfulIteratorStep<A>
  object Done : EffectfulIteratorStep<Nothing>
}

fun interface Generator<A> {
  suspend fun MultishotScope.yield(value: A)
}

context(gen: Generator<A>)
suspend fun <A> MultishotScope.yield(value: A) = with(gen) { yield(value) }

class Iterate<A>(prompt: HandlerPrompt<EffectfulIteratorStep<A>>) : Handler<EffectfulIteratorStep<A>> by prompt,
  Generator<A> {
  override suspend fun MultishotScope.yield(value: A): Unit = use {
    EffectfulIteratorStep.Value(value, it)
  }
}