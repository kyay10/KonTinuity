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
          forEach(iterate2 { numbers(10) }) {i ->
            add(i)
          }
        }
      }
    } shouldBe (0..10).toList()
  }
}

// AB ::= a AB | b
context(_: Amb, _: Exc, _: Input)
private suspend fun MultishotScope.parseAsB(): Int = if (flip()) {
  accept('a')
  parseAsB() + 1
} else {
  accept('b')
  0
}

context(_: Input, _: Exc)
private suspend fun MultishotScope.accept(exp: Char) {
  if (read() != exp) raise("Expected $exp")
}

context(_: Amb, _: Exc)
private suspend fun MultishotScope.drunkFlip(): String {
  val caught = flip()
  val heads = if (caught) flip() else raise("We dropped the coin.")
  return if (heads) "Heads" else "Tails"
}

context(_: Amb, _: Exc, _: Input)
private suspend fun MultishotScope.digit(): Int = when (val c = read()) {
  in '0'..'9' -> c - '0'
  else -> raise("Not a digit: $c")
}

context(_: Amb, _: Exc, _: Input)
private suspend fun MultishotScope.number(): Int {
  var res = digit()
  while (true) {
    if (flip()) {
      res = res * 10 + digit()
    } else {
      return res
    }
  }
}

context(_: Exc)
suspend fun <R> MultishotScope.stringInput(input: String, block: suspend context(Input) MultishotScope.() -> R): R =
  region {
  val pos = field(0)
    block({
    if (pos.get() >= input.length) raise("EOS")
    else input[pos.get().also { pos.set(it + 1) }]
    }, this)
}

suspend fun <R> MultishotScope.nondet(block: suspend context(Amb, Exc) MultishotScope.() -> R): List<R> = handle {
  listOf(block(Collect(given<HandlerPrompt<List<R>>>()), { discard { emptyList() } }, this))
}

suspend fun <R> MultishotScope.backtrack2(block: suspend context(Amb, Exc) MultishotScope.() -> R): Option<R> = handle {
  val amb = Amb {
    use { resume ->
      resume(true).recover { resume(false).bind() }
    }
  }
  Some(block(amb, Maybe(given<HandlerPrompt<Option<R>>>()), this))
}

context(_: Generator<Int>)
private suspend fun MultishotScope.numbers(upto: Int) {
  (0..upto).forEachIteratorless { i ->
    yield(i)
  }
}

private class Iterate2<A>(
  val nextValue: StateScope.Field<(suspend MultishotScope.(Unit) -> A)?>,
  p: HandlerPrompt<EffectfulIterator2<A>>
) :
  Generator<A>, Handler<EffectfulIterator2<A>> by p {
  override suspend fun MultishotScope.yield(value: A) = use { resume ->
    nextValue.set {
      resume(Unit)
      value
    }
    EffectfulIteratorImpl(nextValue)
  }

  private class EffectfulIteratorImpl<A>(private val nextValue: StateScope.Field<(suspend MultishotScope.(Unit) -> A)?>) :
    EffectfulIterator2<A> {
    override fun hasNext(): Boolean = nextValue.get() != null
    override suspend fun MultishotScope.next(): A = nextValue.get()!!.also { nextValue.set(null) }(Unit)
  }
}

context(s: StateScope)
suspend fun <A> MultishotScope.iterate2(block: suspend context(Generator<A>) MultishotScope.() -> Unit) = handle {
  block(Iterate2(field(null), given<HandlerPrompt<EffectfulIterator2<A>>>()), this)
  EmptyEffectfulIterator
}

private object EmptyEffectfulIterator : EffectfulIterator2<Nothing> {
  override fun hasNext(): Boolean = false
  override suspend fun MultishotScope.next(): Nothing = throw NoSuchElementException()
}

interface EffectfulIterator2<out A> {
  fun hasNext(): Boolean
  suspend fun MultishotScope.next(): A
}

context(e: EffectfulIterator2<*>)
fun hasNext(): Boolean = e.hasNext()

suspend fun <A> MultishotScope.next(e: EffectfulIterator2<A>): A = with(e) { next() }

suspend fun <A> MultishotScope.forEach(it: EffectfulIterator2<A>, block: suspend MultishotScope.(A) -> Unit) {
  while (it.hasNext()) {
    block(next(it))
  }
}