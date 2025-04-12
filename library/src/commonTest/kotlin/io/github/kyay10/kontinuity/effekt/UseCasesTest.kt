package io.github.kyay10.kontinuity.effekt

import arrow.core.Option
import arrow.core.Some
import arrow.core.recover
import io.github.kyay10.kontinuity.runTestCC
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class UseCasesTest {
  suspend fun <R> String.parseAll(parser: suspend context(Amb, Exc, Receive<Char>) () -> R): List<R> = nonDet {
    stringReader(this@parseAll) {
      parser()
    }
  }

  suspend fun <R> String.parseBacktrack(parser: suspend context(Amb, Exc, Receive<Char>) () -> R): Option<R> =
    backtrack {
      stringReader(this@parseBacktrack) {
        parser()
      }
    }

  @Test
  fun example() = runTestCC {
    "123".parseAll { number() } shouldBe listOf(123, 12, 1)
    "123".parseBacktrack { number() } shouldBe Some(123)
  }
}

context(_: Amb, _: Exc, _: Receive<Char>)
suspend inline fun accept(p: (Char) -> Boolean): Char =
  receive().also { it -> if (!p(it)) raise("Didn't match $it") }

context(_: Amb, _: Exc, _: Receive<Char>)
suspend fun digit(): Int = accept(Char::isDigit).digitToInt()

context(_: Amb, _: Exc, _: Receive<Char>)
suspend fun number(): Int {
  var res = digit()
  while (flip()) {
    res = res * 10 + digit()
  }
  return res
}

data class StringReaderData(var pos: Int = 0) : Stateful<StringReaderData> {
  override fun fork() = copy()
}

context(_: Exc)
suspend fun <R> stringReader(input: String, block: suspend Receive<Char>.() -> R): R =
  handleStateful(StringReaderData()) {
    block {
      if (get().pos >= input.length) raise("Unexpected EOS")
      input[get().pos++]
    }
  }

suspend fun <E> nonDet(block: suspend context(Amb, Exc) () -> E): List<E> = handle {
  listOf(block(AmbList(this)) {
    discard { emptyList() }
  })
}

class Backtrack<R>(p: HandlerPrompt<Option<R>>) : Amb, Handler<Option<R>> by p {
  override suspend fun flip(): Boolean = use { resume ->
    resume(true).recover { resume(false).bind() }
  }
}

suspend fun <R> backtrack(block: suspend context(Amb, Exc) () -> R): Option<R> = handle {
  Some(block(Backtrack(this), Maybe(this)))
}

fun interface Receive<A> {
  suspend fun receive(): A
}

context(receive: Receive<A>)
suspend fun <A> receive(): A = receive.receive()