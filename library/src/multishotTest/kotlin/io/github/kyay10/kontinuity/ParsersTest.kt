package io.github.kyay10.kontinuity

import arrow.core.None
import arrow.core.Option
import arrow.core.Some
import arrow.core.some
import kotlin.test.Test

class ParsersTest {
  suspend fun CharParsers.numberInParens(): Int = if (flip()) {
    expect('(')
    val n = numberInParens()
    expect(')')
    n
  } else {
    number()
  }

  suspend fun CharParsers.something(): Int {
    expect('a')
    val res = if (flip()) {
      expect('1')
      1
    } else {
      expect('2')
      2
    }
    expect('b')
    return res
  }

  suspend fun CharParsers.somethingPush(): Int =
    pushParser { something() }.feed(this@somethingPush.read()).feed('2').feedAll(this)

  suspend fun CharParsers.someNumberDot(printed: StringBuilder): Int = nonterminal("someNumberDot") {
    printed.appendLine("someNumberDot")
    number().also { expect('.') }
  }

  suspend fun CharParsers.backtrackingExample(printed: StringBuilder): Int =
    if (flip()) someNumberDot(printed) + someNumberDot(printed)
    else someNumberDot(printed)

  suspend fun CharParsers.backtrackingDelegation(printed: StringBuilder): Int =
    pushParser { backtrackingExample(printed) }.feedAll(this)

  suspend fun CharParsers.backtrackingDelegation2(printed: StringBuilder): Int =
    if (flip()) someNumberDot(printed) + someNumberDot(printed)
    else pushParser { someNumberDot(printed) }.feed('1').feedAll(this)

  @Test
  fun somethingTest() = runTestCC {
    CharParsers.parse("a1b") { something() } shouldEq Some(1)
    CharParsers.parse("a2b") { something() } shouldEq Some(2)
    CharParsers.parse("a3b") { something() } shouldEq None
  }

  @Test
  fun numberTest() = runTestCC {
    for (n in listOf(0, 13, 558)) {
      CharParsers.parse("$n") { number() } shouldEq Some(n)
    }
  }

  @Test
  fun numberInParensTest() = runTestCC {
    CharParsers.parse("558") { numberInParens() } shouldEq Some(558)
    CharParsers.parse("(558)") { numberInParens() } shouldEq Some(558)
    CharParsers.parse("(((558)))") { numberInParens() } shouldEq Some(558)
    CharParsers.parse("(((558())") { numberInParens() } shouldEq None
  }

  @Test
  fun somethingPushTest() = runTestCC {
    CharParsers.parse("ab") { somethingPush() } shouldEq Some(2)
  }

  @Test
  fun backtrackingExampleTest() = runTestCC {
    val printed = StringBuilder()
    CharParsers.parse("1234.") { backtrackingExample(printed) } shouldEq Some(1234)
    printed.toString() shouldEq """
      |someNumberDot
      |someNumberDot
      |
    """.trimMargin()
  }

  @Test
  fun backtrackingDelegationTest() = runTestCC {
    val printed = StringBuilder()
    CharParsers.parse("1234.") { backtrackingDelegation(printed) } shouldEq Some(1234)
    printed.toString() shouldEq """
      |someNumberDot
      |someNumberDot
      |someNumberDot
      |
    """.trimMargin()
  }

  @Test
  fun backtrackingDelegation2Test() = runTestCC {
    val printed = StringBuilder()
    CharParsers.parse("1234.") { backtrackingDelegation2(printed) } shouldEq Some(11234)
    printed.toString() shouldEq """
      |someNumberDot
      |someNumberDot
      |someNumberDot
      |
    """.trimMargin()
  }
}

typealias CharParsers = Parser3<Char>

// TODO for more control, define a combinator over P<A>s then we can handle the parser effects locally
interface Parser3<S> : Amb, Exc, Read<S> {
  suspend fun <A> nonterminal(name: String, body: suspend () -> A): A

  companion object {
    suspend fun <A> parse(input: String, parser: suspend CharParsers.() -> A): Option<A> = runState(0) {
      runMapBuilder<Pair<Int, String>, Pair<Int, Any?>, _> {
        handle {
          parser(object : CharParsers, Exc by exc {
            override suspend fun read(): Char {
              ensure(value < input.length) // Unexpected EOS
              return input[value++]
            }

            override suspend fun flip(): Boolean = use { k ->
              // Note: our state is above us here, so we need to save and update
              val before = value
              // does this lead to left biased choice?
              k(true).handleErrorWith {
                value = before
                k(false)
              }
            }

            override suspend fun <A> nonterminal(name: String, body: suspend () -> A): A {
              // We could as well use body.getClass().getCanonicalName() as key.
              val key = value to name
              val (p, res) = getOrPut(key) {
                val res = body()
                value to res
              }
              value = p
              @Suppress("UNCHECKED_CAST") return res as A
            }
          }).some()
        }
      }
    }
  }
}

suspend fun <S> Parser3<S>.expect(token: S) {
  val _ = accept { it == token }
}

suspend fun <A> CharParsers.pushParser(block: suspend CharParsers.() -> A): PushParser<A> = handle {
  PushParser.Success(block(object : CharParsers, Amb by this@pushParser, Exc by this@pushParser {
    override suspend fun read(): Char = use { PushParser(it::invoke) }

    // for now, push parsers normally don't memoize
    override suspend fun <A> nonterminal(name: String, body: suspend () -> A): A = body()
  }))
}

// coalgebraic / push-based parsers, specialized to characters
fun interface PushParser<out R> {
  suspend fun feed(el: Char): PushParser<R>

  data class Success<out R>(val result: R) : PushParser<R> {
    override suspend fun feed(el: Char) = Fail
  }

  object Fail : PushParser<Nothing> {
    override suspend fun feed(el: Char) = this
  }
}

suspend fun <R> PushParser<R>.feedAll(p: CharParsers): R {
  var self = this
  while (self !is PushParser.Success) self = self.feed(p.read())
  return self.result
}