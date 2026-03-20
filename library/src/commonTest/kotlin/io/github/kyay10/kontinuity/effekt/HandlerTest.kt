package io.github.kyay10.kontinuity.effekt

import io.github.kyay10.kontinuity.repeatIteratorless
import io.github.kyay10.kontinuity.runCC
import io.github.kyay10.kontinuity.runTest
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class HandlerTest {
  @Test
  fun ex5dot3dot5() = runTest {
    buildList {
      runCC {
        region {
          for (i in iterate2 { numbers(10) }) {
            add(i)
          }
        }
      }
    } shouldBe (0..10).toList()
  }
}

suspend fun Generator<Int>.numbers(upto: Int) {
  repeatIteratorless(upto + 1) { yield(it) }
}

private class Iterate2<A>(field: StateScope.Field<(suspend (Unit) -> A)?>, p: HandlerPrompt<EffectfulIterator2<A>>) :
  Generator<A>, Handler<EffectfulIterator2<A>> by p, EffectfulIterator2<A> {
  private var nextValue by field
  override suspend fun yield(value: A) = useOnce { resume ->
    nextValue = {
      resume(Unit)
      value
    }
    this
  }

  override suspend fun hasNext(): Boolean = nextValue != null
  override suspend fun next(): A = nextValue!!.also { nextValue = null }.invoke(Unit)
}

suspend fun <A> StateScope.iterate2(block: suspend Generator<A>.() -> Unit) = handle {
  block(Iterate2(field(null), this))
  EmptyEffectfulIterator
}

private object EmptyEffectfulIterator : EffectfulIterator2<Nothing> {
  override suspend fun hasNext(): Boolean = false
  override suspend fun next(): Nothing = throw NoSuchElementException()
}

interface EffectfulIterator2<out A> {
  suspend operator fun hasNext(): Boolean
  suspend operator fun next(): A
}

operator fun <A> EffectfulIterator2<A>.iterator() = this