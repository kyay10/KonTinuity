package io.github.kyay10.kontinuity.effekt.casestudies

import arrow.core.Either
import arrow.core.Either.Left
import arrow.core.Either.Right
import arrow.core.left
import arrow.core.raise.Raise
import arrow.core.recover
import arrow.core.right
import io.github.kyay10.kontinuity.Raise
import io.github.kyay10.kontinuity.effekt.casestudies.TokenKind.*
import io.github.kyay10.kontinuity.effekt.handle
import io.github.kyay10.kontinuity.effekt.use
import io.github.kyay10.kontinuity.raise
import io.github.kyay10.kontinuity.runTestCC
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class ParserTest {
  @Test
  fun example() = runTestCC {
    parse("42") { parseCalls() } shouldBe Right(1)
    parse("foo(1)") { parseCalls() } shouldBe Right(2)
    parse("foo(1, 2)") { parseCalls() } shouldBe Right(3)
    parse("foo(1, 2, 3, 4)") { parseCalls() } shouldBe Right(5)
    parse("foo(1, 2, bar(4, 5))") { parseCalls() } shouldBe Right(6)
    parse("foo(1, 2,\nbar(4, 5))") { parseCalls() } shouldBe Right(6)

    parse("}42") { parseExpr() } shouldBe Left("Expected ( but got }")
    parse("42") { parseExpr() } shouldBe Right(Lit(42))
    parse("let x = 4 in 42") { parseExpr() } shouldBe Right(Let("x", Lit(4), Lit(42)))
    parse("let x = let y = 2 in 1 in 42") { parseExpr() } shouldBe Right(Let("x", Let("y", Lit(2), Lit(1)), Lit(42)))
    parse("let x = (let y = 2 in 1) in 42") { parseExpr() } shouldBe Right(Let("x", Let("y", Lit(2), Lit(1)), Lit(42)))
    parse("let x = (let y = f(42) in 1) in 42") { parseExpr() } shouldBe Right(
      Let(
        "x", Let("y", App("f", Lit(42)), Lit(1)), Lit(42)
      )
    )
    parse("let x = (let y = f(let z = 1 in z) in 1) in 42") { parseExpr() } shouldBe Right(
      Let(
        "x", Let("y", App("f", Let("z", Lit(1), Var("z"))), Lit(1)), Lit(42)
      )
    )
  }
}

fun interface Nondet {
  suspend fun alt(): Boolean
}

context(nondet: Nondet)
suspend fun alt() = nondet.alt()

context(_: Nondet, _: Lexer, _: Raise<String>)
suspend inline fun accept(expectedText: String = "", predicate: (Token) -> Boolean): Token {
  val token = next()
  return if (predicate(token)) token
  else raise("unexpected token $token, expected $expectedText")
}

context(_: Nondet, _: Lexer, _: Raise<String>)
suspend fun any() = accept { t -> true }

context(_: Nondet, _: Lexer, _: Raise<String>)
suspend fun accept(exp: TokenKind) = accept(exp.toString()) { t -> t.kind == exp }

context(_: Nondet, _: Lexer, _: Raise<String>)
suspend fun ident() = accept(Ident).text

context(_: Nondet, _: Lexer, _: Raise<String>)
suspend fun number() = accept(Number).text

context(_: Nondet, _: Lexer, _: Raise<String>)
suspend fun punct(p: String) {
  val text = accept(Punct).text
  if (text != p) raise("Expected $p but got $text")
}

context(_: Nondet, _: Lexer, _: Raise<String>)
suspend fun kw(exp: String) {
  val text = ident()
  if (text != exp) raise("Expected keyword $exp but got $text")
}

context(_: Nondet)
suspend inline fun <R> opt(block: () -> R): R? = if (alt()) block() else null

context(_: Nondet)
suspend inline fun many(block: () -> Unit) {
  while (alt()) block()
}

context(_: Nondet)
suspend inline fun some(block: () -> Unit) {
  do block() while (alt())
}

sealed interface Tree
data class Lit(val value: Int) : Tree
data class Var(val name: String) : Tree
data class Let(val name: String, val binding: Tree, val body: Tree) : Tree
data class App(val name: String, val arg: Tree) : Tree

context(_: Nondet, _: Lexer, _: Raise<String>)
suspend fun parseNum(): Tree {
  val num = number()
  return Lit(num.toIntOrNull() ?: raise("Expected number, but cannot convert input to integer: $num"))
}

context(_: Nondet, _: Lexer, _: Raise<String>)
suspend fun parseVar(): Tree = Var(ident())

context(_: Nondet, _: Lexer, _: Raise<String>)
suspend fun parseAtom(): Tree = opt { parseNum() } ?: parseVar()

context(_: Nondet, _: Lexer, _: Raise<String>)
suspend fun parseLet(): Tree {
  kw("let")
  val name = ident()
  punct("=")
  val binding = parseExpr()
  kw("in")
  val body = parseExpr()
  return Let(name, binding, body)
}

context(_: Nondet, _: Lexer, _: Raise<String>)
suspend fun parseGroup(): Tree = opt { parseAtom() } ?: run {
  punct("(")
  val expr = parseExpr()
  punct(")")
  return expr
}

context(_: Nondet, _: Lexer, _: Raise<String>)
suspend fun parseApp(): Tree {
  val name = ident()
  punct("(")
  val arg = parseExpr()
  punct(")")
  return App(name, arg)
}

context(_: Nondet, _: Lexer, _: Raise<String>)
suspend fun parseExpr(): Tree = when {
  alt() -> parseLet()
  alt() -> parseApp()
  else -> parseGroup()
}

// <EXPR> ::= <NUMBER> | <IDENT> `(` <EXPR> (`,` <EXPR>)*  `)`
context(_: Nondet, _: Lexer, _: Raise<String>)
suspend fun parseCalls(): Int = if (alt()) {
  number()
  1
} else {
  var count = 1
  ident()
  punct("(")
  count += parseCalls()
  many {
    punct(",")
    count += parseCalls()
  }
  punct(")")
  count
}

typealias ParseResult<R> = Either<String, R>

suspend fun <R> parse(input: String, block: suspend context(Nondet, Lexer, Raise<String>) () -> R): ParseResult<R> =
  handle {
    Raise<LexerError, _> { Left("${it.msg}: ${it.pos}") }.lexer(input) {
      skipWhitespace {
        block(
          Nondet { use { k -> k(true).recover { k(false).bind() } } },
          this,
          Raise { it.left() }
        ).right()
      }
    }
  }