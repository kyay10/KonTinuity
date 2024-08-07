package effekt

import arrow.core.Option
import arrow.core.Some
import arrow.core.recover
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
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
  fun example() = runTest {
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

class StringReader<R>(val input: String, val exc: Exc, prompt: HandlerPrompt<R>) : Receive<Char>,
  StatefulHandler<R, Int>, Handler<R> by prompt {
  override suspend fun receive(): Char {
    val curPos = get()
    if (curPos >= input.length) exc.raise("Unexpected EOS")
    set(curPos + 1)
    return input[curPos]
  }
}

suspend fun <R> Exc.stringReader(input: String, block: suspend StringReader<R>.() -> R): R =
  with(StringReader(input, this, HandlerPrompt<R>())) {
    handleStateful(0) { block() }
  }

interface AmbExc : Amb, Exc

fun interface NonDet<E> : AmbList<E>, AmbExc {
  override suspend fun raise(msg: String): Nothing = discard { emptyList() }
}

suspend fun <E> nonDet(block: suspend AmbExc.() -> E): List<E> = handle(::NonDet) {
  listOf(block())
}

fun interface Backtrack<R> : Maybe<R>, AmbExc {
  override suspend fun flip(): Boolean = use { resume ->
    resume(true).recover { resume(false).bind() }
  }
}

suspend fun <R> backtrack(block: suspend AmbExc.() -> R): Option<R> = handle(::Backtrack) {
  Some(block())
}

interface Receive<A> {
  suspend fun receive(): A
}

interface Send<A> {
  suspend fun send(a: A)
}