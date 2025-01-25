package effekt

import arrow.core.None
import arrow.core.Option
import arrow.core.Some
import arrow.core.recover
import io.kotest.matchers.shouldBe
import org.junit.Test
import runCC
import runTest

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
context(Amb, Exc, Input)
private suspend fun parseAsB(): Int = if (flip()) {
  accept('a')
  parseAsB() + 1
} else {
  accept('b')
  0
}

context(Input, Exc)
private suspend fun accept(exp: Char) {
  if (read() != exp) raise("Expected $exp")
}

context(Amb, Exc)
private suspend fun drunkFlip(): String {
  val caught = flip()
  val heads = if (caught) flip() else raise("We dropped the coin.")
  return if (heads) "Heads" else "Tails"
}

context(Amb, Exc, Input)
private suspend fun digit(): Int = when (val c = read()) {
  in '0'..'9' -> c - '0'
  else -> raise("Not a digit: $c")
}

context(Amb, Exc, Input)
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

interface StringInput<R> : Input {
  val exc: Exc
  val input: String
  val pos: StateScope.Field<Int>
  override suspend fun read(): Char = if (pos.get() >= input.length) exc.raise("EOS")
  else input[pos.get().also { pos.set(it + 1) }]
}

context(Exc)
suspend fun <R> stringInput(input: String, block: suspend StringInput<R>.() -> R): R = region {
  val pos = field(0)
  block(object : StringInput<R> {
    override val exc: Exc = this@Exc
    override val input: String = input
    override val pos: StateScope.Field<Int> = pos
  })
}

class Nondet2<R>(p: HandlerPrompt<List<R>>) : Amb by Collect(p), AmbExc, Handler<List<R>> by p {
  override suspend fun raise(msg: String): Nothing = discard { emptyList() }
}

suspend fun <R> nondet(block: suspend AmbExc.() -> R): List<R> = handle {
  listOf(block(Nondet2(this)))
}

class Backtrack2<R>(p: HandlerPrompt<Option<R>>) : Exc by Maybe(p), AmbExc, Handler<Option<R>> by p {
  override suspend fun flip(): Boolean = use { resume ->
    resume(true).recover { resume(false).bind() }
  }
}

suspend fun <R> backtrack2(block: suspend AmbExc.() -> R): Option<R> = handle {
  Some(block(Backtrack2(this)))
}

interface Generator2<A> {
  suspend fun yield(value: A)
}

private suspend fun Generator2<Int>.numbers(upto: Int) {
  for (i in 0..upto) yield(i)
}

class Iterate2<A>(val nextValue: StateScope.Field<(suspend (Unit) -> A)?>, p: HandlerPrompt<EffectfulIterator2<A>>) : Generator2<A>, Handler<EffectfulIterator2<A>> by p {
  override suspend fun yield(value: A) = use { resume ->
    nextValue.set {
      resume(Unit)
      value
    }
    EffectfulIteratorImpl(nextValue)
  }

  private class EffectfulIteratorImpl<A>(private val nextValue: StateScope.Field<(suspend (Unit) -> A)?>) :
    EffectfulIterator2<A> {
    override suspend fun hasNext(): Boolean = nextValue.get() != null
    override suspend fun next(): A = nextValue.get()!!.also { nextValue.set(null) }.invoke(Unit)
  }
}

suspend fun <A> StateScope.iterate2(block: suspend Generator2<A>.() -> Unit) = handle {
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