package io.github.kyay10.kontinuity.effekt

import arrow.core.Option
import arrow.core.Some
import arrow.core.recover
import io.github.kyay10.kontinuity.MultishotScope
import io.github.kyay10.kontinuity.runTestCC
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class UseCasesTest {
  suspend fun <R> MultishotScope.parseAll(string: String, parser: suspend context(Amb, Exc, Receive<Char>) MultishotScope.() -> R): List<R> =
    nonDet {
      stringReader(string) {
        parser()
      }
    }

  suspend fun <R> MultishotScope.parseBacktrack(string: String, parser: suspend context(Amb, Exc, Receive<Char>) MultishotScope.() -> R): Option<R> =
    backtrack {
      stringReader(string) {
        parser()
      }
    }

  @Test
  fun example() = runTestCC {
    parseAll("123") { number() } shouldBe listOf(123, 12, 1)
    parseBacktrack("123") { number() } shouldBe Some(123)
  }
}

context(_: Amb, _: Exc, _: Receive<Char>)
suspend inline fun MultishotScope.accept(p: (Char) -> Boolean): Char =
  receive().also { it -> if (!p(it)) raise("Didn't match $it") }

context(_: Amb, _: Exc, _: Receive<Char>)
suspend fun MultishotScope.digit(): Int = accept(Char::isDigit).digitToInt()

context(_: Amb, _: Exc, _: Receive<Char>)
suspend fun MultishotScope.number(): Int {
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
suspend fun <R> MultishotScope.stringReader(input: String, block: suspend context(Receive<Char>) MultishotScope.() -> R): R =
  handleStateful(StringReaderData()) {
    block({
      if (get().pos >= input.length) raise("Unexpected EOS")
      input[get().pos++]
    }, this)
  }

suspend fun <E> MultishotScope.nonDet(block: suspend context(Amb, Exc) MultishotScope.() -> E): List<E> = handle {
  listOf(block(AmbList(given<HandlerPrompt<List<E>>>()), {
    discard { emptyList() }
  }, this))
}

class Backtrack<R>(p: HandlerPrompt<Option<R>>) : Amb, Handler<Option<R>> by p {
  override suspend fun MultishotScope.flip(): Boolean = use { resume ->
    resume(true).recover { resume(false).bind() }
  }
}

suspend fun <R> MultishotScope.backtrack(block: suspend context(Amb, Exc) MultishotScope.() -> R): Option<R> = handle {
  Some(block(Backtrack(given<HandlerPrompt<Option<R>>>()), Maybe(given<HandlerPrompt<Option<R>>>()), this))
}

fun interface Receive<A> {
  suspend fun MultishotScope.receive(): A
}

context(receive: Receive<A>)
suspend fun <A> MultishotScope.receive(): A = with(receive) { receive() }