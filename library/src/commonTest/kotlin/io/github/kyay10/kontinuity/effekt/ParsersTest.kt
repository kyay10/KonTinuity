package io.github.kyay10.kontinuity.effekt

import arrow.core.None
import arrow.core.Option
import arrow.core.Some
import arrow.core.recover
import io.github.kyay10.kontinuity.runTestCC
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class ParsersTest {
  suspend fun CharParsers.numberInParens(): Int = if (alternative()) {
    expect('(')
    val n = numberInParens()
    expect(')')
    n
  } else {
    number()
  }

  suspend fun CharParsers.something(): Int {
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

  suspend fun CharParsers.somethingPush(): Int = pushParser { something() }.feed(any()).feed('2').feedAll(this)

  suspend fun CharParsers.someNumberDot(printed: StringBuilder): Int = nonterminal("someNumberDot") {
    printed.appendLine("someNumberDot")
    number().also { expect('.') }
  }

  suspend fun CharParsers.backtrackingExample(printed: StringBuilder): Int =
    if (alternative()) someNumberDot(printed) + someNumberDot(printed)
    else someNumberDot(printed)

  suspend fun CharParsers.backtrackingDelegation(printed: StringBuilder): Int =
    pushParser { backtrackingExample(printed) }.feedAll(this)

  suspend fun CharParsers.backtrackingDelegation2(printed: StringBuilder): Int =
    if (alternative()) someNumberDot(printed) + someNumberDot(printed)
    else pushParser { someNumberDot(printed) }.feed('1').feedAll(this)

  @Test
  fun somethingTest() = runTestCC {
    CharParsers.parse("a1b") { something() } shouldBe Some(1)
    CharParsers.parse("a2b") { something() } shouldBe Some(2)
    CharParsers.parse("a3b") { something() } shouldBe None
  }

  @Test
  fun numberTest() = runTestCC {
    for (n in listOf(0, 13, 558)) {
      CharParsers.parse("$n") { number() } shouldBe Some(n)
    }
  }

  @Test
  fun numberInParensTest() = runTestCC {
    CharParsers.parse("558") { numberInParens() } shouldBe Some(558)
    CharParsers.parse("(558)") { numberInParens() } shouldBe Some(558)
    CharParsers.parse("(((558)))") { numberInParens() } shouldBe Some(558)
    CharParsers.parse("(((558())") { numberInParens() } shouldBe None
  }

  @Test
  fun somethingPushTest() = runTestCC {
    CharParsers.parse("ab") { somethingPush() } shouldBe Some(2)
  }

  @Test
  fun backtrackingExampleTest() = runTestCC {
    val printed = StringBuilder()
    CharParsers.parse("1234.") { backtrackingExample(printed) } shouldBe Some(1234)
    printed.toString() shouldBe """
      |someNumberDot
      |someNumberDot
      |
    """.trimMargin()
  }

  @Test
  fun backtrackingDelegationTest() = runTestCC {
    val printed = StringBuilder()
    CharParsers.parse("1234.") { backtrackingDelegation(printed) } shouldBe Some(1234)
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
    CharParsers.parse("1234.") { backtrackingDelegation2(printed) } shouldBe Some(11234)
    printed.toString() shouldBe """
      |someNumberDot
      |someNumberDot
      |someNumberDot
      |
    """.trimMargin()
  }
}

typealias CharParsers = Parser3<Char>

suspend fun CharParsers.digit(): Int {
  val c = any()
  if (c.isDigit()) return c.digitToInt()
  fail("Expected a digit")
}

suspend fun CharParsers.number(): Int {
  var res = digit()
  while (alternative()) {
    res = res * 10 + digit()
  }
  return res
}

interface Parser3<S> {
  // the reader effect
  suspend fun any(): S

  // the exception effect
  suspend fun fail(explanation: String): Nothing

  // the ambiguity effect
  // TODO for more control, define a combinator over P<A>s then we can handle the parser effects locally
  suspend fun alternative(): Boolean

  suspend fun <A> nonterminal(name: String, body: suspend () -> A): A

  companion object {
    suspend fun <A> parse(input: String, parser: suspend CharParsers.() -> A): Option<A> =
      handleStateful(StringParser.Data(0)) {
        Some(parser(StringParser(input, this)))
      }
  }
}

suspend fun <S> Parser3<S>.expect(token: S) {
  val res = any()
  if (res != token) fail("Expected $token, but got $res")
}

suspend fun Parser3<*>.alternatives(n: Int): Int {
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

  override suspend fun any(): Char {
    if (get().index >= input.length) fail("Unexpected EOS")
    return input[get().index++]
  }

  override suspend fun alternative(): Boolean = use { k ->
    // Note: our state is above us here, so we need to save and update
    val before = get().index
    // does this lead to left biased choice?
    k(true).recover {
      get().index = before
      k(false).bind()
    }
  }

  override suspend fun fail(explanation: String): Nothing {
    discard { None }
  }

  override suspend fun <A> nonterminal(name: String, body: suspend () -> A): A {
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
  override suspend fun any(): Char = use { k ->
    object : PushParser<R> {
      override val result = null
      override val isDone = false
      override suspend fun feed(el: Char) = k(el)
    }
  }

  // for now, push parsers normally don't memoize
  override suspend fun <A> nonterminal(name: String, body: suspend () -> A): A = body()
}

suspend fun <A> CharParsers.pushParser(block: suspend CharParsers.() -> A): PushParser<A> = handle {
  PushParser.succeed(ToPush<A>(this@pushParser, this).block())
}

// coalgebraic / push-based parsers, specialized to characters
interface PushParser<out R> {
  val result: R?
  val isDone: Boolean
  suspend fun feed(el: Char): PushParser<R>

  companion object {
    fun <R> succeed(result: R): PushParser<R> = object : PushParser<R> {
      override val result = result
      override val isDone = true
      override suspend fun feed(el: Char) = Fail
    }
  }

  object Fail : PushParser<Nothing> {
    override val result = null
    override val isDone = false
    override suspend fun feed(el: Char) = this
  }
}

suspend fun <R> PushParser<R>.feedAll(p: CharParsers): R {
  var self = this
  while (!self.isDone) {
    self = self.feed(p.any())
  }
  return self.result!!
}