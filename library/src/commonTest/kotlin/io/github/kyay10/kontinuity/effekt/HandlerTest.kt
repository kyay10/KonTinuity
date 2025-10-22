package io.github.kyay10.kontinuity.effekt

import arrow.core.None
import arrow.core.Option
import arrow.core.Some
import arrow.core.recover
import io.github.kyay10.kontinuity.MultishotScope
import io.github.kyay10.kontinuity.forEachIteratorless
import io.github.kyay10.kontinuity.runCC
import io.github.kyay10.kontinuity.runTest
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class HandlerJvmTest {
  @Test
  fun testDrunkFlip() = runTest {
    runCC {
      val res = collect {
        maybe {
          drunkFlip()
        }
      }
      res shouldBe listOf(Some("Heads"), Some("Tails"), None)
    }
    runCC {
      val res = maybe {
        collect {
          drunkFlip()
        }
      }
      res shouldBe None
    }
  }

  @Test
  fun ex5dot3dot4() = runTest {
    runCC {
      nondet {
        stringInput("123") {
          number()
        }
      }
    } shouldBe listOf(123, 12, 1)
    runCC {
      backtrack2 {
        stringInput("123") {
          number()
        }
      }
    } shouldBe Some(123)
  }

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

// AB ::= a AB | b
context(_: Amb, _: Exc, _: Input, _: MultishotScope)
private suspend fun parseAsB(): Int = if (flip()) {
  accept('a')
  parseAsB() + 1
} else {
  accept('b')
  0
}

context(_: Input, _: Exc, _: MultishotScope)
private suspend fun accept(exp: Char) {
  if (read() != exp) raise("Expected $exp")
}

context(_: Amb, _: Exc, _: MultishotScope)
private suspend fun drunkFlip(): String {
  val caught = flip()
  val heads = if (caught) flip() else raise("We dropped the coin.")
  return if (heads) "Heads" else "Tails"
}

context(_: Amb, _: Exc, _: Input, _: MultishotScope)
private suspend fun digit(): Int = when (val c = read()) {
  in '0'..'9' -> c - '0'
  else -> raise("Not a digit: $c")
}

context(_: Amb, _: Exc, _: Input, _: MultishotScope)
private suspend fun number(): Int {
  var res = digit()
  while (true) {
    if (flip()) {
      res = res * 10 + digit()
    } else {
      return res
    }
  }
}

context(_: Exc, _: MultishotScope)
suspend fun <R> stringInput(input: String, block: suspend context(MultishotScope) Input.() -> R): R = region {
  val pos = field(0)
  block {
    if (pos.get() >= input.length) raise("EOS")
    else input[pos.get().also { pos.set(it + 1) }]
  }
}

context(_: MultishotScope)
suspend fun <R> nondet(block: suspend context(Amb, Exc, MultishotScope) () -> R): List<R> = handle {
  listOf(context(Collect(this), Exc { discard { emptyList() } }) { block() })
}

context(_: MultishotScope)
suspend fun <R> backtrack2(block: suspend context(Amb, Exc, MultishotScope) () -> R): Option<R> = handle {
  val amb = Amb {
    use { resume ->
      resume(true).recover { resume(false).bind() }
    }
  }
  Some(context(amb, Maybe(this)) { block() })
}

context(_: MultishotScope)
private suspend fun Generator<Int>.numbers(upto: Int) {
  (0..upto).forEachIteratorless { i ->
    yield(i)
  }
}

private class Iterate2<A>(
  val nextValue: StateScope.Field<(suspend context(MultishotScope) () -> A)?>,
  p: HandlerPrompt<EffectfulIterator2<A>>
) :
  Generator<A>, Handler<EffectfulIterator2<A>> by p {
  context(_: MultishotScope)
  override suspend fun yield(value: A) = use { resume ->
    nextValue.set {
      resume(Unit)
      value
    }
    EffectfulIteratorImpl(nextValue)
  }

  private class EffectfulIteratorImpl<A>(private val nextValue: StateScope.Field<(suspend context(MultishotScope) () -> A)?>) :
    EffectfulIterator2<A> {
    context(_: MultishotScope)
    override suspend fun hasNext(): Boolean = nextValue.get() != null

    context(_: MultishotScope)
    override suspend fun next(): A = nextValue.get()!!.also { nextValue.set(null) }()
  }
}

context(_: MultishotScope)
suspend fun <A> StateScope.iterate2(block: suspend context(MultishotScope) Generator<A>.() -> Unit) = handle {
  block(Iterate2(field(null), this))
  EmptyEffectfulIterator
}

private object EmptyEffectfulIterator : EffectfulIterator2<Nothing> {
  context(_: MultishotScope)
  override suspend fun hasNext(): Boolean = false

  context(_: MultishotScope)
  override suspend fun next(): Nothing = throw NoSuchElementException()
}

interface EffectfulIterator2<out A> {
  context(_: MultishotScope)
  suspend operator fun hasNext(): Boolean

  context(_: MultishotScope)
  suspend operator fun next(): A
}

operator fun <A> EffectfulIterator2<A>.iterator() = this