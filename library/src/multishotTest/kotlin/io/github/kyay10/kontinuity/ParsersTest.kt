package io.github.kyay10.kontinuity

import arrow.core.None
import arrow.core.Option
import arrow.core.Some
import arrow.core.some
import kotlin.test.Test

class ParsersTest {
  context(_: Amb, _: Exc, _: Input)
  suspend fun numberInParens(): Int =
    if (flip()) {
      expect('(')
      val n = numberInParens()
      expect(')')
      n
    } else number()

  context(_: Amb, _: Exc, _: Input)
  suspend fun something(): Int {
    expect('a')
    val res =
      if (flip()) {
        expect('1')
        1
      } else {
        expect('2')
        2
      }
    expect('b')
    return res
  }

  context(_: Amb, _: Exc, _: Input)
  suspend fun somethingPush(): Int = pushParser { something() }.feed(read()).feed('2').feedAll()

  context(_: Amb, _: Exc, _: Input, _: Nonterminal)
  suspend fun someNumberDot(printed: StringBuilder): Int =
    nonterminal("someNumberDot") {
      printed.appendLine("someNumberDot")
      number().also { expect('.') }
    }

  context(_: Amb, _: Exc, _: Input, _: Nonterminal)
  suspend fun backtrackingExample(printed: StringBuilder): Int =
    if (flip()) someNumberDot(printed) + someNumberDot(printed) else someNumberDot(printed)

  context(_: Amb, _: Exc, _: Input)
  suspend fun backtrackingDelegation(printed: StringBuilder): Int =
    pushParser { backtrackingExample(printed) }.feedAll()

  context(_: Amb, _: Exc, _: Input, _: Nonterminal)
  suspend fun backtrackingDelegation2(printed: StringBuilder): Int =
    if (flip()) someNumberDot(printed) + someNumberDot(printed)
    else pushParser { someNumberDot(printed) }.feed('1').feedAll()

  @Test
  fun somethingTest() = runTestCC {
    parseChars("a1b") { something() } shouldEq Some(1)
    parseChars("a2b") { something() } shouldEq Some(2)
    parseChars("a3b") { something() } shouldEq None
  }

  @Test
  fun numberTest() = runTestCC {
    for (n in listOf(0, 13, 558)) {
      parseChars("$n") { number() } shouldEq Some(n)
    }
  }

  @Test
  fun numberInParensTest() = runTestCC {
    parseChars("558") { numberInParens() } shouldEq Some(558)
    parseChars("(558)") { numberInParens() } shouldEq Some(558)
    parseChars("(((558)))") { numberInParens() } shouldEq Some(558)
    parseChars("(((558())") { numberInParens() } shouldEq None
  }

  @Test fun somethingPushTest() = runTestCC { parseChars("ab") { somethingPush() } shouldEq Some(2) }

  @Test
  fun backtrackingExampleTest() = runTestCC {
    val printed = StringBuilder()
    parseChars("1234.") { backtrackingExample(printed) } shouldEq Some(1234)
    printed.toString() shouldEq
      """
      |someNumberDot
      |someNumberDot
      |"""
        .trimMargin()
  }

  @Test
  fun backtrackingDelegationTest() = runTestCC {
    val printed = StringBuilder()
    parseChars("1234.") { backtrackingDelegation(printed) } shouldEq Some(1234)
    printed.toString() shouldEq
      """
      |someNumberDot
      |someNumberDot
      |someNumberDot
      |"""
        .trimMargin()
  }

  @Test
  fun backtrackingDelegation2Test() = runTestCC {
    val printed = StringBuilder()
    parseChars("1234.") { backtrackingDelegation2(printed) } shouldEq Some(11234)
    printed.toString() shouldEq
      """
      |someNumberDot
      |someNumberDot
      |someNumberDot
      |"""
        .trimMargin()
  }
}

// TODO for more control, define a combinator over P<A>s then we can handle the parser effects locally
interface Nonterminal {
  suspend fun <A> nonterminal(name: String, body: suspend () -> A): A
}

suspend fun <A> parseChars(
  input: String,
  parser: suspend context(Amb, Exc, Input, Nonterminal) () -> A,
): Option<A> =
  runState(0) {
    runMapBuilder<Pair<Int, String>, Pair<Int, Any?>, _> {
      handle {
        context(exc) {
          context(
            object : Amb, Input, Nonterminal {
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
                val (p, res) =
                  getOrPut(key) {
                    val res = body()
                    value to res
                  }
                value = p
                @Suppress("UNCHECKED_CAST")
                return res as A
              }
            }
          ) {
            parser().some()
          }
        }
      }
    }
  }

context(nt: Nonterminal)
suspend fun <A> nonterminal(name: String, body: suspend () -> A): A = nt.nonterminal(name, body)

context(_: Exc, _: Read<S>)
suspend fun <S> expect(token: S) {
  val _ = accept { it == token }
}

suspend fun <A> pushParser(block: suspend context(Input, Nonterminal) () -> A): PushParser<A> = handle {
  PushParser.Success(
    block(
      { use { PushParser(it::invoke) } },
      object : Nonterminal {
        // for now, push parsers normally don't memoize
        override suspend fun <A> nonterminal(name: String, body: suspend () -> A): A = body()
      },
    )
  )
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

context(_: Input)
suspend fun <R> PushParser<R>.feedAll(): R {
  var self = this
  while (self !is PushParser.Success) self = self.feed(read())
  return self.result
}
