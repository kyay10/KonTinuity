package io.github.kyay10.kontinuity.effekt

import arrow.core.Option
import arrow.core.Some
import arrow.core.recover
import io.github.kyay10.kontinuity.runTestCC
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class UseCasesTest {
  suspend fun <R> String.parseAll(parser: suspend Parser2.() -> R): List<R> = nonDet {
    stringReader(this@parseAll) {
      parser(Parser2Impl(this@nonDet, this@nonDet, this@stringReader))
    }
  }

  suspend fun <R> String.parseBacktrack(parser: suspend Parser2.() -> R): Option<R> = backtrack {
    stringReader(this@parseBacktrack) {
      parser(Parser2Impl(this@backtrack, this@backtrack, this@stringReader))
    }
  }

  @Test
  fun example() = runTestCC {
    "123".parseAll(Parser2::number) shouldBe listOf(123, 12, 1)
    "123".parseBacktrack { number() } shouldBe Some(123)
  }
}

interface Parser2 : Amb, Exc, Receive<Char>

// just delegate to other handlers
class Parser2Impl(amb: Amb, exc: Exc, receive: Receive<Char>) : Parser2, Amb by amb, Exc by exc,
  Receive<Char> by receive

suspend inline fun Parser2.accept(p: (Char) -> Boolean): Char =
  receive().also { it -> if (!p(it)) raise("Didn't match $it") }

suspend fun Parser2.digit(): Int = accept(Char::isDigit).digitToInt()
suspend fun Parser2.number(): Int {
  var res = digit()
  while (flip()) {
    res = res * 10 + digit()
  }
  return res
}

class StringReader<R>(val input: String, val exc: Exc, prompt: StatefulPrompt<R, Data>) : Receive<Char>,
  StatefulHandler<R, StringReader.Data> by prompt {
  data class Data(var pos: Int = 0) : Stateful<Data> {
    override fun fork() = copy()
  }

  override suspend fun receive(): Char {
    if (get().pos >= input.length) exc.raise("Unexpected EOS")
    return input[get().pos++]
  }
}

suspend fun <R> Exc.stringReader(input: String, block: suspend Receive<Char>.() -> R): R =
  handleStateful(StringReader.Data()) { block(StringReader(input, this@stringReader, this)) }

interface AmbExc : Amb, Exc

class NonDet<E>(p: HandlerPrompt<List<E>>) : Amb by AmbList<E>(p), AmbExc, Handler<List<E>> by p {
  override suspend fun raise(msg: String): Nothing = discard { emptyList() }
}

suspend fun <E> nonDet(block: suspend AmbExc.() -> E): List<E> = handle {
  listOf(block(NonDet(this)))
}

class Backtrack<R>(p: HandlerPrompt<Option<R>>) : Exc by Maybe(p), Handler<Option<R>> by p, AmbExc {
  override suspend fun flip(): Boolean = use { resume ->
    resume(true).recover { resume(false).bind() }
  }
}

suspend fun <R> backtrack(block: suspend AmbExc.() -> R): Option<R> = handle {
  Some(block(Backtrack(this)))
}

interface Receive<A> {
  suspend fun receive(): A
}