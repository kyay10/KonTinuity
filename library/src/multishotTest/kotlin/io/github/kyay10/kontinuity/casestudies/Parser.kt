package io.github.kyay10.kontinuity.casestudies

import arrow.core.Either
import arrow.core.Either.Left
import arrow.core.Either.Right
import arrow.core.handleErrorWith
import arrow.core.left
import arrow.core.raise.Raise
import arrow.core.raise.context.ensure
import arrow.core.raise.context.ensureNotNull
import arrow.core.right
import io.github.kyay10.kontinuity.*
import io.github.kyay10.kontinuity.casestudies.TokenKind.*
import kotlin.test.Test

class ParserTest {
  @Test
  fun example() = runTestCC {
    parse("42") { parseCalls() } shouldEq Right(1)
    parse("foo(1)") { parseCalls() } shouldEq Right(2)
    parse("foo(1, 2)") { parseCalls() } shouldEq Right(3)
    parse("foo(1, 2, 3, 4)") { parseCalls() } shouldEq Right(5)
    parse("foo(1, 2, bar(4, 5))") { parseCalls() } shouldEq Right(6)
    parse("foo(1, 2,\nbar(4, 5))") { parseCalls() } shouldEq Right(6)

    parse("}42") { parseExpr() } shouldEq Left("Expected ( but got }")
    parse("42") { parseExpr() } shouldEq Right(Lit(42))
    parse("let x = 4 in 42") { parseExpr() } shouldEq Right(Let("x", Lit(4), Lit(42)))
    parse("let x = let y = 2 in 1 in 42") { parseExpr() } shouldEq Right(Let("x", Let("y", Lit(2), Lit(1)), Lit(42)))
    parse("let x = (let y = 2 in 1) in 42") { parseExpr() } shouldEq Right(Let("x", Let("y", Lit(2), Lit(1)), Lit(42)))
    parse("let x = (let y = f(42) in 1) in 42") { parseExpr() } shouldEq
      Right(Let("x", Let("y", App("f", Lit(42)), Lit(1)), Lit(42)))
    parse("let x = (let y = f(let z = 1 in z) in 1) in 42") { parseExpr() } shouldEq
      Right(Let("x", Let("y", App("f", Let("z", Lit(1), Var("z"))), Lit(1)), Lit(42)))
  }
}

context(_: Amb, _: Lexer, _: Raise<String>)
suspend inline fun accept(expectedText: String = "", predicate: (Token) -> Boolean) =
  read().also { ensure(predicate(it)) { "unexpected token $it, expected $expectedText" } }

context(_: Amb, _: Lexer, _: Raise<String>)
suspend fun accept(exp: TokenKind) = accept(exp.toString()) { t -> t.kind == exp }

@IgnorableReturnValue
context(_: Amb, _: Lexer, _: Raise<String>)
suspend fun ident() = accept(Ident).text

@IgnorableReturnValue
context(_: Amb, _: Lexer, _: Raise<String>)
suspend fun number() = accept(Number).text

context(_: Amb, _: Lexer, _: Raise<String>)
suspend fun punct(p: String) {
  val text = accept(Punct).text
  ensure(text == p) { "Expected $p but got $text" }
}

context(_: Amb, _: Lexer, _: Raise<String>)
suspend fun kw(exp: String) {
  val text = ident()
  ensure(text == exp) { "Expected keyword $exp but got $text" }
}

context(_: Amb)
suspend inline fun <R> opt(block: () -> R): R? = if (flip()) block() else null

context(_: Amb)
suspend inline fun many(block: () -> Unit) {
  while (flip()) block()
}

context(_: Amb)
suspend inline fun some(block: () -> Unit) {
  do block() while (flip())
}

sealed interface Tree

data class Lit(val value: Int) : Tree

data class Var(val name: String) : Tree

data class Let(val name: String, val binding: Tree, val body: Tree) : Tree

data class App(val name: String, val arg: Tree) : Tree

context(_: Amb, _: Lexer, _: Raise<String>)
suspend fun parseNum(): Tree {
  val num = number()
  return Lit(ensureNotNull(num.toIntOrNull()) { "Expected number, but cannot convert input to integer: $num" })
}

context(_: Amb, _: Lexer, _: Raise<String>)
suspend fun parseVar(): Tree = Var(ident())

context(_: Amb, _: Lexer, _: Raise<String>)
suspend fun parseAtom(): Tree = opt { parseNum() } ?: parseVar()

context(_: Amb, _: Lexer, _: Raise<String>)
suspend fun parseLet(): Tree {
  kw("let")
  val name = ident()
  punct("=")
  val binding = parseExpr()
  kw("in")
  val body = parseExpr()
  return Let(name, binding, body)
}

context(_: Amb, _: Lexer, _: Raise<String>)
suspend fun parseGroup(): Tree =
  opt { parseAtom() }
    ?: run {
      punct("(")
      val expr = parseExpr()
      punct(")")
      return expr
    }

context(_: Amb, _: Lexer, _: Raise<String>)
suspend fun parseApp(): Tree {
  val name = ident()
  punct("(")
  val arg = parseExpr()
  punct(")")
  return App(name, arg)
}

context(_: Amb, _: Lexer, _: Raise<String>)
suspend fun parseExpr(): Tree =
  when {
    flip() -> parseLet()
    flip() -> parseApp()
    else -> parseGroup()
  }

// <EXPR> ::= <NUMBER> | <IDENT> `(` <EXPR> (`,` <EXPR>)*  `)`
context(_: Amb, _: Lexer, _: Raise<String>)
suspend fun parseCalls(): Int =
  if (flip()) {
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

suspend fun <R> parse(
  input: String,
  block: suspend context(Amb, Lexer, Raise<String>) () -> R,
): ParseResult<R> = handle {
  with(Raise<LexerError, _> { Left("${it.msg}: ${it.pos}") }) {
    lexer(
      input,
      {
        skipWhitespace {
          block({ use { k -> k(true).handleErrorWith { k(false) } } }, contextOf<Lexer>(), Raise { it.left() }).right()
        }
      },
    )
  }
}
