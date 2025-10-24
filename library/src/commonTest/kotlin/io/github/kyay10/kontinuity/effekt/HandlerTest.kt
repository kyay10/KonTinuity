package io.github.kyay10.kontinuity.effekt

import arrow.core.None
import arrow.core.Option
import arrow.core.Some
import arrow.core.recover
import io.github.kyay10.kontinuity.*
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
context(_: Amb<Region>, _: Exc<Region>, _: Input<Region>, _: MultishotScope<Region>)
private suspend fun <Region> parseAsB(): Int = if (flip()) {
  accept('a')
  parseAsB() + 1
} else {
  accept('b')
  0
}

context(_: Input<Region>, _: Exc<Region>, _: MultishotScope<Region>)
private suspend fun <Region> accept(exp: Char) {
  if (read() != exp) raise("Expected $exp")
}

context(_: Amb<Region>, _: Exc<Region>, _: MultishotScope<Region>)
private suspend fun <Region> drunkFlip(): String {
  val caught = flip()
  val heads = if (caught) flip() else raise("We dropped the coin.")
  return if (heads) "Heads" else "Tails"
}

context(_: Amb<Region>, _: Exc<Region>, _: Input<Region>, _: MultishotScope<Region>)
private suspend fun <Region> digit(): Int = when (val c = read()) {
  in '0'..'9' -> c - '0'
  else -> raise("Not a digit: $c")
}

context(_: Amb<Region>, _: Exc<Region>, _: Input<Region>, _: MultishotScope<Region>)
private suspend fun <Region> number(): Int {
  var res = digit()
  while (true) {
    if (flip()) {
      res = res * 10 + digit()
    } else {
      return res
    }
  }
}

context(_: Exc<Region>, _: MultishotScope<Region>)
suspend fun <R, Region> stringInput(input: String, block: suspend context(NewScope<Region>) Input<Region>.() -> R): R = region {
  val pos = field(0)
  block {
    if (pos.get() >= input.length) raise("EOS")
    else input[pos.get().also { pos.set(it + 1) }]
  }
}

context(_: MultishotScope<Region>)
suspend fun <R, Region> nondet(block: suspend context(Amb<NewRegion>, Exc<NewRegion>, NewScope<Region>) () -> R): List<R> = handle {
  listOf(context(Collect(this), Exc<HandleRegion> { discard { emptyList() } }) { block() })
}

context(_: MultishotScope<Region>)
suspend fun <R, Region> backtrack2(block: suspend context(Amb<NewRegion>, Exc<NewRegion>, NewScope<Region>) () -> R): Option<R> =
  handle {
    val amb = Amb {
      use { resume ->
        resume(true).recover { resume(false).bind() }
      }
    }
    Some(context(amb, Maybe(this)) { block() })
  }

context(_: MultishotScope<Region>)
private suspend fun <Region> Generator<Int, Region>.numbers(upto: Int) {
  (0..upto).forEachIteratorless { i ->
    yield(i)
  }
}

private class Iterate2<A, in IR, OR>(
  val nextValue: StateScope.Field<(suspend context(MultishotScope<OR>) () -> A)?>,
  p: HandlerPrompt<EffectfulIterator2<A, OR>, IR, OR>
) :
  Generator<A, IR>, Handler<EffectfulIterator2<A, OR>, IR, OR> by p {
  context(_: MultishotScope<IR>)
  override suspend fun yield(value: A) = use { resume ->
    nextValue.set {
      resume(Unit)
      value
    }
    EffectfulIteratorImpl(nextValue)
  }

  private class EffectfulIteratorImpl<A, Region>(private val nextValue: StateScope.Field<(suspend context(MultishotScope<Region>) () -> A)?>) :
    EffectfulIterator2<A, Region> {
    context(_: MultishotScope<Region>)
    override suspend fun hasNext(): Boolean = nextValue.get() != null

    context(_: MultishotScope<Region>)
    override suspend fun next(): A = nextValue.get()!!.also { nextValue.set(null) }()
  }
}

context(_: MultishotScope<Region>)
suspend fun <A, Region> StateScope.iterate2(block: suspend context(NewScope<Region>) Generator<A, NewRegion>.() -> Unit) =
  handle {
    block(Iterate2(field(null), this))
    EmptyEffectfulIterator
  }

private object EmptyEffectfulIterator : EffectfulIterator2<Nothing, Any?> {
  context(_: MultishotScope<Any?>)
  override suspend fun hasNext(): Boolean = false

  context(_: MultishotScope<Any?>)
  override suspend fun next(): Nothing = throw NoSuchElementException()
}

interface EffectfulIterator2<out A, in Region> {
  context(_: MultishotScope<Region>)
  suspend operator fun hasNext(): Boolean

  context(_: MultishotScope<Region>)
  suspend operator fun next(): A
}

operator fun <A, Region> EffectfulIterator2<A, Region>.iterator() = this