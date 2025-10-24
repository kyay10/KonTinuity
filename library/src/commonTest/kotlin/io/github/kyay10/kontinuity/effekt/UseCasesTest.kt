package io.github.kyay10.kontinuity.effekt

import arrow.core.Option
import arrow.core.Some
import arrow.core.recover
import io.github.kyay10.kontinuity.MultishotScope
import io.github.kyay10.kontinuity.NewRegion
import io.github.kyay10.kontinuity.NewScope
import io.github.kyay10.kontinuity.runTestCC
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class UseCasesTest {
  context(_: MultishotScope<Region>)
  suspend fun <R, Region> String.parseAll(parser: suspend context(Amb<NewRegion>, Exc<NewRegion>, Receive<Char, NewRegion>, NewScope<Region>) () -> R): List<R> =
    nonDet {
      stringReader(this@parseAll) {
        parser()
      }
    }

  context(_: MultishotScope<Region>)
  suspend fun <R, Region> String.parseBacktrack(parser: suspend context(Amb<NewRegion>, Exc<NewRegion>, Receive<Char, NewRegion>, NewScope<Region>) () -> R): Option<R> =
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

context(_: Amb<Region>, _: Exc<Region>, _: Receive<Char, Region>, _: MultishotScope<Region>)
suspend inline fun <Region> accept(p: (Char) -> Boolean): Char =
  receive().also { if (!p(it)) raise("Didn't match $it") }

context(_: Amb<Region>, _: Exc<Region>, _: Receive<Char, Region>, _: MultishotScope<Region>)
suspend fun <Region> digit(): Int = accept(Char::isDigit).digitToInt()

context(_: Amb<Region>, _: Exc<Region>, _: Receive<Char, Region>, _: MultishotScope<Region>)
suspend fun <Region> number(): Int {
  var res = digit()
  while (flip()) {
    res = res * 10 + digit()
  }
  return res
}

data class StringReaderData(var pos: Int = 0) : Stateful<StringReaderData> {
  override fun fork() = copy()
}

context(_: Exc<Region>, _: MultishotScope<Region>)
suspend fun <R, Region> stringReader(
  input: String,
  block: suspend context(NewScope<Region>) Receive<Char, NewRegion>.() -> R
): R =
  handleStateful(StringReaderData()) {
    block {
      if (get().pos >= input.length) raise("Unexpected EOS")
      input[get().pos++]
    }
  }

context(_: MultishotScope<Region>)
suspend fun <E, Region> nonDet(block: suspend context(Amb<NewRegion>, Exc<NewRegion>, NewScope<Region>) () -> E): List<E> =
  handle {
    context(AmbList(this), Exc<HandleRegion> { discard { emptyList() } }) {
      listOf(block())
    }
  }

class Backtrack<R, IR, OR>(p: HandlerPrompt<Option<R>, IR, OR>) : Amb<IR>, Handler<Option<R>, IR, OR> by p {
  context(_: MultishotScope<IR>)
  override suspend fun flip(): Boolean = use { resume ->
    resume(true).recover { resume(false).bind() }
  }
}

context(_: MultishotScope<Region>)
suspend fun <R, Region> backtrack(block: suspend context(Amb<NewRegion>, Exc<NewRegion>, NewScope<Region>) () -> R): Option<R> =
  handle {
    context(Backtrack(this), Maybe(this)) {
      Some(block())
    }
  }

fun interface Receive<A, Region> {
  context(_: MultishotScope<Region>)
  suspend fun receive(): A
}

context(receive: Receive<A, Region>, _: MultishotScope<Region>)
suspend fun <A, Region> receive(): A = receive.receive()