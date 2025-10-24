package io.github.kyay10.kontinuity.effekt

import arrow.core.None
import arrow.core.Option
import arrow.core.Some
import arrow.core.recover
import io.github.kyay10.kontinuity.MultishotScope
import io.github.kyay10.kontinuity.NewRegion
import io.github.kyay10.kontinuity.NewScope
import io.github.kyay10.kontinuity.runTestCC
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class ParsersTest {
  context(_: MultishotScope<Region>)
  suspend fun <Region> CharParsers<Region>.numberInParens(): Int = if (alternative()) {
    expect('(')
    val n = numberInParens()
    expect(')')
    n
  } else {
    number()
  }

  context(_: MultishotScope<Region>)
  suspend fun <Region> CharParsers<Region>.something(): Int {
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

  context(_: MultishotScope<Region>)
  suspend fun <Region> CharParsers<Region>.somethingPush(): Int = pushParser { something() }.feed(any()).feed('2').feedAll(this)

  context(_: MultishotScope<Region>)
  suspend fun <Region> CharParsers<Region>.someNumberDot(printed: StringBuilder): Int = nonterminal("someNumberDot") {
    printed.appendLine("someNumberDot")
    number().also { expect('.') }
  }

  context(_: MultishotScope<Region>)
  suspend fun <Region> CharParsers<Region>.backtrackingExample(printed: StringBuilder): Int =
    if (alternative()) someNumberDot(printed) + someNumberDot(printed)
    else someNumberDot(printed)

  context(_: MultishotScope<Region>)
  suspend fun <Region> CharParsers<Region>.backtrackingDelegation(printed: StringBuilder): Int =
    pushParser { backtrackingExample(printed) }.feedAll(this)

  context(_: MultishotScope<Region>)
  suspend fun <Region> CharParsers<Region>.backtrackingDelegation2(printed: StringBuilder): Int =
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

typealias CharParsers<Region> = Parser3<Char, Region>

context(_: MultishotScope<Region>)
suspend fun <Region> CharParsers<Region>.digit(): Int {
  val c = any()
  if (c.isDigit()) return c.digitToInt()
  fail("Expected a digit")
}

context(_: MultishotScope<Region>)
suspend fun <Region> CharParsers<Region>.number(): Int {
  var res = digit()
  while (alternative()) {
    res = res * 10 + digit()
  }
  return res
}

interface Parser3<S, in Region> {
  // the reader effect
  context(_: MultishotScope<Region>)
  suspend fun any(): S

  // the exception effect
  context(_: MultishotScope<Region>)
  suspend fun fail(explanation: String): Nothing

  // the ambiguity effect
  // TODO for more control, define a combinator over P<A>s then we can handle the parser effects locally
  context(_: MultishotScope<Region>)
  suspend fun alternative(): Boolean

  context(_: MultishotScope<IR>)
  suspend fun <A, IR: Region> nonterminal(name: String, body: suspend context(MultishotScope<IR>) () -> A): A

  companion object {
    context(_: MultishotScope<Region>)
    suspend fun <A, Region> parse(
      input: String,
      parser: suspend context(NewScope<Region>) CharParsers<NewRegion>.() -> A
    ): Option<A> =
      handleStateful(StringParser.Data(0)) {
        Some(parser(StringParser(input, this)))
      }
  }
}

context(_: MultishotScope<Region>)
suspend fun <S, Region> Parser3<S, Region>.expect(token: S) {
  val res = any()
  if (res != token) fail("Expected $token, but got $res")
}

context(_: MultishotScope<Region>)
suspend fun <Region> Parser3<*, Region>.alternatives(n: Int): Int {
  var i = 1
  while (i < n) {
    if (alternative()) return i
    i++
  }
  return 0
}

class StringParser<R, IR, OR>(val input: String, prompt: StatefulPrompt<Option<R>, Data, IR, OR>) : CharParsers<IR>,
  StatefulHandler<Option<R>, StringParser.Data, IR, OR> by prompt {
  data class Data(var index: Int) : Stateful<Data> {
    override fun fork() = copy()
  }

  private val cache = mutableMapOf<Pair<Int, String>, Pair<Int, Any?>>()

  context(_: MultishotScope<IR>)
  override suspend fun any(): Char {
    if (get().index >= input.length) fail("Unexpected EOS")
    return input[get().index++]
  }

  context(_: MultishotScope<IR>)
  override suspend fun alternative(): Boolean = use { k ->
    // Note: our state is above us here, so we need to save and update
    val before = get().index
    // does this lead to left biased choice?
    k(true).recover {
      get().index = before
      k(false).bind()
    }
  }

  context(_: MultishotScope<IR>)
  override suspend fun fail(explanation: String): Nothing {
    discard { None }
  }

  context(_: MultishotScope<IIR>)
  override suspend fun <A, IIR: IR> nonterminal(name: String, body: suspend context(MultishotScope<IIR>) () -> A): A {
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

class ToPush<R, IR: OR, OR>(val outer: CharParsers<OR>, prompt: HandlerPrompt<PushParser<R, OR>, IR, OR>) :
  Handler<PushParser<R, OR>, IR, OR> by prompt,
  CharParsers<IR> by outer {
  context(_: MultishotScope<IR>)
  override suspend fun any(): Char = use { k ->
    object : PushParser<R, OR> {
      override val result = null
      override val isDone = false

      context(_: MultishotScope<OR>)
      override suspend fun feed(el: Char) = k(el)
    }
  }

  // for now, push parsers normally don't memoize
  context(_: MultishotScope<IIR>)
  override suspend fun <A, IIR: IR> nonterminal(name: String, body: suspend context(MultishotScope<IIR>) () -> A): A = body()
}

context(_: MultishotScope<Region>)
suspend fun <A, Region> CharParsers<Region>.pushParser(block: suspend context(NewScope<Region>) CharParsers<NewRegion>.() -> A): PushParser<A, Region> =
  handle {
    PushParser.succeed(ToPush(this@pushParser, this).block())
  }

// coalgebraic / push-based parsers, specialized to characters
interface PushParser<out R, in Region> {
  val result: R?
  val isDone: Boolean

  context(_: MultishotScope<Region>)
  suspend fun feed(el: Char): PushParser<R, Region>

  companion object {
    fun <R> succeed(result: R): PushParser<R, Any?> = object : PushParser<R, Any?> {
      override val result = result
      override val isDone = true

      context(_: MultishotScope<Any?>)
      override suspend fun feed(el: Char) = Fail
    }
  }

  object Fail : PushParser<Nothing, Any?> {
    override val result = null
    override val isDone = false

    context(_: MultishotScope<Any?>)
    override suspend fun feed(el: Char) = this
  }
}

context(_: MultishotScope<Region>)
suspend fun <R, Region> PushParser<R, Region>.feedAll(p: CharParsers<Region>): R {
  var self = this
  while (!self.isDone) {
    self = self.feed(p.any())
  }
  return self.result!!
}