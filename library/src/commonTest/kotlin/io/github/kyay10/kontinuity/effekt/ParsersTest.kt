package io.github.kyay10.kontinuity.effekt

import arrow.core.None
import arrow.core.Option
import arrow.core.Some
import arrow.core.recover
import io.github.kyay10.kontinuity.MultishotScope
import io.github.kyay10.kontinuity.effekt.Parser3.Companion.parse
import io.github.kyay10.kontinuity.runTestCC
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class ParsersTest {
  context(_: CharParsers)
  suspend fun MultishotScope.numberInParens(): Int = if (alternative()) {
    expect('(')
    val n = numberInParens()
    expect(')')
    n
  } else {
    number()
  }

  context(_: CharParsers)
  suspend fun MultishotScope.something(): Int {
    expect('a')
    val res = if (alternative()) {
      expect('1')
      1
    } else {
      expect('2')
      2
    }
    expect('b')
    return res
  }

  context(_: CharParsers)
  suspend fun MultishotScope.somethingPush(): Int = feedAll(feed(feed(pushParser { something() }, any()), '2'))

  context(_: CharParsers)
  suspend fun MultishotScope.someNumberDot(printed: StringBuilder): Int = nonterminal("someNumberDot") {
    printed.appendLine("someNumberDot")
    number().also { expect('.') }
  }

  context(_: CharParsers)
  suspend fun MultishotScope.backtrackingExample(printed: StringBuilder): Int =
    if (alternative()) someNumberDot(printed) + someNumberDot(printed)
    else someNumberDot(printed)

  context(c: CharParsers)
  suspend fun MultishotScope.backtrackingDelegation(printed: StringBuilder): Int =
    feedAll(pushParser { backtrackingExample(printed) })

  context(c: CharParsers)
  suspend fun MultishotScope.backtrackingDelegation2(printed: StringBuilder): Int =
    if (alternative()) someNumberDot(printed) + someNumberDot(printed)
    else feedAll(feed(pushParser { someNumberDot(printed) }, '1'))

  @Test
  fun somethingTest() = runTestCC {
    parse("a1b") { something() } shouldBe Some(1)
    parse("a2b") { something() } shouldBe Some(2)
    parse("a3b") { something() } shouldBe None
  }

  @Test
  fun numberTest() = runTestCC {
    for (n in listOf(0, 13, 558)) {
      parse("$n") { number() } shouldBe Some(n)
    }
  }

  @Test
  fun numberInParensTest() = runTestCC {
    parse("558") { numberInParens() } shouldBe Some(558)
    parse("(558)") { numberInParens() } shouldBe Some(558)
    parse("(((558)))") { numberInParens() } shouldBe Some(558)
    parse("(((558())") { numberInParens() } shouldBe None
  }

  @Test
  fun somethingPushTest() = runTestCC {
    parse("ab") { somethingPush() } shouldBe Some(2)
  }

  @Test
  fun backtrackingExampleTest() = runTestCC {
    val printed = StringBuilder()
    parse("1234.") { backtrackingExample(printed) } shouldBe Some(1234)
    printed.toString() shouldBe """
      |someNumberDot
      |someNumberDot
      |
    """.trimMargin()
  }

  @Test
  fun backtrackingDelegationTest() = runTestCC {
    val printed = StringBuilder()
    parse("1234.") { backtrackingDelegation(printed) } shouldBe Some(1234)
    printed.toString() shouldBe """
      |someNumberDot
      |someNumberDot
      |someNumberDot
      |
    """.trimMargin()
  }

  @Test
  fun backtrackingDelegation2Test() = runTestCC {
    val printed = StringBuilder()
    parse("1234.") { backtrackingDelegation2(printed) } shouldBe Some(11234)
    printed.toString() shouldBe """
      |someNumberDot
      |someNumberDot
      |someNumberDot
      |
    """.trimMargin()
  }
}

typealias CharParsers = Parser3<Char>

context(_: CharParsers)
suspend fun MultishotScope.digit(): Int {
  val c = any()
  if (c.isDigit()) return c.digitToInt()
  fail("Expected a digit")
}

context(_: CharParsers)
suspend fun MultishotScope.number(): Int {
  var res = digit()
  while (alternative()) {
    res = res * 10 + digit()
  }
  return res
}

interface Parser3<S> {
  // the reader effect
  suspend fun MultishotScope.any(): S

  // the exception effect
  suspend fun MultishotScope.fail(explanation: String): Nothing

  // the ambiguity effect
  // TODO for more control, define a combinator over P<A>s then we can handle the parser effects locally
  suspend fun MultishotScope.alternative(): Boolean

  suspend fun <A> MultishotScope.nonterminal(name: String, body: suspend MultishotScope.() -> A): A

  companion object {
    suspend fun <A> MultishotScope.parse(input: String, parser: suspend context(CharParsers) MultishotScope.() -> A): Option<A> =
      handleStateful(StringParser.Data(0)) {
        Some(parser(StringParser(input, given<StatefulPrompt<Option<A>, StringParser.Data>>()), this))
      }
  }
}

context(p: Parser3<S>)
suspend fun <S> MultishotScope.any(): S = with(p) { any() }
context(p: Parser3<S>)
suspend fun <S> MultishotScope.alternative(): Boolean = with(p) { alternative() }
context(p: Parser3<S>)
suspend fun <S> MultishotScope.fail(explanation: String): Nothing = with(p) { fail(explanation) }
context(p: Parser3<S>)
suspend fun <S, R> MultishotScope.nonterminal(name: String, body: suspend MultishotScope.() -> R): R =
  with(p) { nonterminal(name, body) }


context(_: Parser3<*>)
suspend fun <S> MultishotScope.expect(token: S) {
  val res = any()
  if (res != token) fail("Expected $token, but got $res")
}

context(_: Parser3<*>)
suspend fun MultishotScope.alternatives(n: Int): Int {
  var i = 1
  while (i < n) {
    if (alternative()) return i
    i++
  }
  return 0
}

class StringParser<R>(val input: String, prompt: StatefulPrompt<Option<R>, Data>) : CharParsers,
  StatefulHandler<Option<R>, StringParser.Data> by prompt {
  data class Data(var index: Int) : Stateful<Data> {
    override fun fork() = copy()
  }

  private val cache = mutableMapOf<Pair<Int, String>, Pair<Int, Any?>>()

  override suspend fun MultishotScope.any(): Char {
    if (get().index >= input.length) fail("Unexpected EOS")
    return input[get().index++]
  }

  override suspend fun MultishotScope.alternative(): Boolean = use { k ->
    // Note: our state is above us here, so we need to save and update
    val before = get().index
    // does this lead to left biased choice?
    k(true).recover {
      get().index = before
      k(false).bind()
    }
  }

  override suspend fun MultishotScope.fail(explanation: String): Nothing {
    discard { None }
  }

  override suspend fun <A> MultishotScope.nonterminal(name: String, body: suspend MultishotScope.() -> A): A {
    // We could as well use body.getClass().getCanonicalName() as key.
    val key = Pair(get().index, name)
    val (p, res) = cache.getOrPut(key) {
      val res = body()
      get().index to res
    }
    get().index = p
    @Suppress("UNCHECKED_CAST") return res as A
  }
}

class ToPush<R>(val outer: CharParsers, prompt: HandlerPrompt<PushParser<R>>) : Handler<PushParser<R>> by prompt,
  CharParsers by outer {
  override suspend fun MultishotScope.any(): Char = use { k ->
    object : PushParser<R> {
      override val result = null
      override val isDone = false
      override suspend fun MultishotScope.feed(el: Char) = k(el)
    }
  }

  // for now, push parsers normally don't memoize
  override suspend fun <A> MultishotScope.nonterminal(name: String, body: suspend MultishotScope.() -> A): A = body()
}

context(c: CharParsers)
suspend fun <A> MultishotScope.pushParser(block: suspend context(CharParsers) MultishotScope.() -> A): PushParser<A> = handle {
  PushParser.succeed(block(ToPush(c, given<HandlerPrompt<PushParser<A>>>()), this))
}

// coalgebraic / push-based parsers, specialized to characters
interface PushParser<out R> {
  val result: R?
  val isDone: Boolean
  suspend fun MultishotScope.feed(el: Char): PushParser<R>

  companion object {
    fun <R> succeed(result: R): PushParser<R> = object : PushParser<R> {
      override val result = result
      override val isDone = true
      override suspend fun MultishotScope.feed(el: Char) = Fail
    }
  }

  object Fail : PushParser<Nothing> {
    override val result = null
    override val isDone = false
    override suspend fun MultishotScope.feed(el: Char) = Fail
  }
}

suspend fun <R> MultishotScope.feed(push: PushParser<R>, el: Char): PushParser<R> = with(push) { feed(el) }

context(_: CharParsers)
suspend fun <R> MultishotScope.feedAll(push: PushParser<R>): R {
  var self = push
  while (!self.isDone) {
    self = feed(self, any())
  }
  return self.result!!
}