package io.github.kyay10.kontinuity.effekt

import arrow.core.Option
import arrow.core.Some
import arrow.core.recover
import io.github.kyay10.kontinuity.MultishotScope
import io.github.kyay10.kontinuity.runTestCC
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class UseCasesTest {
  context(_: MultishotScope)
  suspend fun <R> String.parseAll(parser: suspend context(Amb, Exc, Receive<Char>, MultishotScope) () -> R): List<R> =
    nonDet {
      stringReader(this@parseAll) {
        parser()
      }
    }

  context(_: MultishotScope)
  suspend fun <R> String.parseBacktrack(parser: suspend context(Amb, Exc, Receive<Char>, MultishotScope) () -> R): Option<R> =
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

context(_: Amb, _: Exc, _: Receive<Char>, _: MultishotScope)
suspend inline fun accept(p: (Char) -> Boolean): Char =
  receive().also { if (!p(it)) raise("Didn't match $it") }

context(_: Amb, _: Exc, _: Receive<Char>, _: MultishotScope)
suspend fun digit(): Int = accept(Char::isDigit).digitToInt()

context(_: Amb, _: Exc, _: Receive<Char>, _: MultishotScope)
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

context(_: Exc, _: MultishotScope)
suspend fun <R> stringReader(input: String, block: suspend context(MultishotScope) Receive<Char>.() -> R): R =
  handleStateful(StringReaderData()) {
    block {
      if (get().pos >= input.length) raise("Unexpected EOS")
      input[get().pos++]
    }
  }

context(_: MultishotScope)
suspend fun <E> nonDet(block: suspend context(Amb, Exc, MultishotScope) () -> E): List<E> = handle {
  context(AmbList(this), Exc { discard { emptyList() } }) {
    listOf(block())
  }
}

class Backtrack<R>(p: HandlerPrompt<Option<R>>) : Amb, Handler<Option<R>> by p {
  context(_: MultishotScope)
  override suspend fun flip(): Boolean = use { resume ->
    resume(true).recover { resume(false).bind() }
  }
}

context(_: MultishotScope)
suspend fun <R> backtrack(block: suspend context(Amb, Exc, MultishotScope) () -> R): Option<R> = handle {
  context(Backtrack(this), Maybe(this)) {
    Some(block())
  }
}

fun interface Receive<A> {
  context(_: MultishotScope)
  suspend fun receive(): A
}

context(receive: Receive<A>, _: MultishotScope)
suspend fun <A> receive(): A = receive.receive()