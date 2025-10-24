package io.github.kyay10.kontinuity.effekt

import io.github.kyay10.kontinuity.MultishotScope
import io.github.kyay10.kontinuity.NewRegion
import io.github.kyay10.kontinuity.NewScope
import io.github.kyay10.kontinuity.SubCont
import io.github.kyay10.kontinuity.runTestCC
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class GeneratorTest {
  context(_: MultishotScope<Region>)
  suspend fun <Region> Generator<Int, Region>.numbers(to: Int) {
    var i = 0
    while (i <= to) {
      yield(i++)
    }
  }

  context(amb: Amb<Region>, _: MultishotScope<Region>)
  suspend fun <Region> Generator<Int, Region>.numbersFlip(to: Int) {
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
          numbersFlip(2)
        }
        for (i in ints) {
          add(i)
        }
      }.size shouldBe 8
    } shouldBe listOf(0, 1, 2, 1, -2, 0, -1, 2, -1, -2, 0, 1, 2, 1, -2, 0, -1, 2, -1, -2)
  }
}

interface EffectfulIterator<A, Region> {
  context(_: MultishotScope<Region>)
  suspend operator fun next(): A
  context(_: MultishotScope<Region>)
  suspend operator fun hasNext(): Boolean
}

fun interface EffectfulIterable<A, Region> {
  context(_: MultishotScope<Region>)
  suspend operator fun iterator(): EffectfulIterator<A, Region>
}

context(_: MultishotScope<Region>)
fun <A, Region> effectfulIterable(body: suspend context(NewScope<Region>) Generator<A, NewRegion>.() -> Unit) = EffectfulIterable {
  EffectfulIteratorImpl(handle<_, Region> {
    body(Iterate(this))
    EffectfulIteratorStep.Done
  })
}

class EffectfulIteratorImpl<A, Region>(var current: EffectfulIteratorStep<A, Region>) : EffectfulIterator<A, Region> {
  context(_: MultishotScope<Region>)
  override suspend fun next(): A {
    return when (val step = current) {
      is EffectfulIteratorStep.Value -> {
        current = step.next(Unit)
        step.value
      }

      EffectfulIteratorStep.Done -> throw NoSuchElementException()
    }
  }

  context(_: MultishotScope<Region>)
  override suspend fun hasNext(): Boolean {
    return when (current) {
      is EffectfulIteratorStep.Value -> true
      EffectfulIteratorStep.Done -> false
    }
  }
}

sealed interface EffectfulIteratorStep<out A, in IR> {
  data class Value<A, in Region>(val value: A, val next: SubCont<Unit, EffectfulIteratorStep<A, Region>, Region>) : EffectfulIteratorStep<A, Region>
  object Done : EffectfulIteratorStep<Nothing, Any?>
}

operator fun <A, Region> EffectfulIterator<A, Region>.iterator() = this

fun interface Generator<A, in IR> {
  context(_: MultishotScope<IR>)
  suspend fun yield(value: A)
}

class Iterate<A, in IR, OR>(prompt: HandlerPrompt<EffectfulIteratorStep<A, OR>, IR, OR>) : Handler<EffectfulIteratorStep<A, OR>, IR, OR> by prompt,
  Generator<A, IR> {
  context(_: MultishotScope<IR>)
  override suspend fun yield(value: A): Unit = use {
    EffectfulIteratorStep.Value(value, it)
  }
}