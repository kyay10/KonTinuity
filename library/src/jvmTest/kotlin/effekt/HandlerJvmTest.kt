package effekt

import arrow.core.None
import arrow.core.Option
import arrow.core.Some
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.Test
import runCC

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
      backtrack {
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
          for (i in iterate { numbers(10) }) {
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

fun interface Nondet<R> : Collect<R>, NonDetermined {
  override suspend fun raise(msg: String): Nothing = useAbort { emptyList() }
}

suspend fun <R> nondet(block: suspend NonDetermined.() -> R): List<R> = handle(::Nondet, block)

fun interface Backtrack2<R> : Maybe<R>, NonDetermined {
  override suspend fun flip(): Boolean = use { resume ->
    resume(true).onNone { return@use resume(false) }
  }
}

suspend fun <R> backtrack(block: suspend NonDetermined.() -> R): Option<R> = handle(::Backtrack2, block)

interface Generator<A> {
  suspend fun yield(value: A)
}

private suspend fun Generator<Int>.numbers(upto: Int) {
  for (i in 0..upto) yield(i)
}

interface Iterate<A> : Generator<A>, Handler<Unit, EffectfulIterator<A>> {
  val nextValue: StateScope.Field<(suspend (Unit) -> A)?>
  override suspend fun unit(value: Unit): EffectfulIterator<A> = EffectfulIteratorImpl(nextValue)
  override suspend fun yield(value: A) = use { resume ->
    nextValue.set {
      resume(Unit)
      value
    }
    EffectfulIteratorImpl(nextValue)
  }

  private class EffectfulIteratorImpl<A>(private val nextValue: StateScope.Field<(suspend (Unit) -> A)?>) :
    EffectfulIterator<A> {
    override suspend fun hasNext(): Boolean = nextValue.get() != null
    override suspend fun next(): A = nextValue.get()!!.also { nextValue.set(null) }.invoke(Unit)
  }
}

suspend fun <A> StateScope.iterate(block: suspend Generator<A>.() -> Unit): EffectfulIterator<A> {
  val nextValue = field<(suspend (Unit) -> A)?>(null)
  return handle({
    object : Iterate<A> {
      override val nextValue = nextValue
      override fun prompt(): ObscurePrompt<EffectfulIterator<A>> = it()
    }
  }, block)
}

interface EffectfulIterator<out A> {
  suspend operator fun hasNext(): Boolean
  suspend operator fun next(): A
}

suspend operator fun <A> EffectfulIterator<A>.iterator() = this