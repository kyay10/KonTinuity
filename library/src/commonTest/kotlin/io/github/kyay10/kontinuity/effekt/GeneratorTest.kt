package io.github.kyay10.kontinuity.effekt

import io.github.kyay10.kontinuity.SubContFinal
import io.github.kyay10.kontinuity.repeatIteratorless
import io.github.kyay10.kontinuity.runTestCC
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class GeneratorTest {
  suspend fun Generator<Int>.numbers(to: Int) = repeatIteratorless(to + 1) { yield(it) }

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
}

interface EffectfulIterator<A> {
  suspend operator fun next(): A
  suspend operator fun hasNext(): Boolean
}

fun interface EffectfulIterable<A> {
  suspend operator fun iterator(): EffectfulIterator<A>
}

fun <A> effectfulIterable(body: suspend Generator<A>.() -> Unit) = EffectfulIterable {
  EffectfulIteratorImpl(handle {
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
  data class Value<A>(val value: A, val next: SubContFinal<Unit, EffectfulIteratorStep<A>>) : EffectfulIteratorStep<A>
  object Done : EffectfulIteratorStep<Nothing>
}

operator fun <A> EffectfulIterator<A>.iterator() = this

fun interface Generator<A> {
  suspend fun yield(value: A)
}

class Iterate<A>(prompt: HandlerPrompt<EffectfulIteratorStep<A>>) : Handler<EffectfulIteratorStep<A>> by prompt,
  Generator<A> {
  override suspend fun yield(value: A): Unit = useOnce {
    EffectfulIteratorStep.Value(value, it)
  }
}