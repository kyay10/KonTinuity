package io.github.kyay10.kontinuity.effekt.casestudies

import arrow.core.Either
import arrow.core.Either.Left
import arrow.core.Either.Right
import arrow.core.left
import arrow.core.raise.Raise
import arrow.core.recover
import arrow.core.right
import io.github.kyay10.kontinuity.effekt.casestudies.TokenKind.*
import io.github.kyay10.kontinuity.Raise
import io.github.kyay10.kontinuity.effekt.handle
import io.github.kyay10.kontinuity.effekt.use
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

// Temporary product until contexts are multiplatform
interface Parser : Nondet, Lexer, Raise<String>

suspend inline fun Parser.accept(expectedText: String = "", predicate: (Token) -> Boolean): Token {
  val token = next()
  return if (predicate(token)) token
  else raise("unexpected token $token, expected $expectedText")
}

suspend fun Parser.any() = accept { t -> true }
suspend fun Parser.accept(exp: TokenKind) = accept(exp.toString()) { t -> t.kind == exp }
suspend fun Parser.ident() = accept(Ident).text
suspend fun Parser.number() = accept(Number).text
suspend fun Parser.punct(p: String) {
  val text = accept(Punct).text
  if (text != p) raise("Expected $p but got $text")
}

suspend fun Parser.kw(exp: String) {
  val text = ident()
  if (text != exp) raise("Expected keyword $exp but got $text")
}

suspend inline fun <R> Nondet.opt(block: () -> R): R? = if (alt()) block() else null

suspend inline fun Nondet.many(block: () -> Unit) {
  while (alt()) block()
}

suspend inline fun Nondet.some(block: () -> Unit) {
  do block() while (alt())
}

sealed interface Tree
data class Lit(val value: Int) : Tree
data class Var(val name: String) : Tree
data class Let(val name: String, val binding: Tree, val body: Tree) : Tree
data class App(val name: String, val arg: Tree) : Tree

suspend fun Parser.parseNum(): Tree {
  val num = number()
  return Lit(num.toIntOrNull() ?: raise("Expected number, but cannot convert input to integer: $num"))
}

suspend fun Parser.parseVar(): Tree = Var(ident())

suspend fun Parser.parseAtom(): Tree = opt { parseNum() } ?: parseVar()

suspend fun Parser.parseLet(): Tree {
  kw("let")
  val name = ident()
  punct("=")
  val binding = parseExpr()
  kw("in")
  val body = parseExpr()
  return Let(name, binding, body)
}

suspend fun Parser.parseGroup(): Tree = opt { parseAtom() } ?: run {
  punct("(")
  val expr = parseExpr()
  punct(")")
  return expr
}

suspend fun Parser.parseApp(): Tree {
  val name = ident()
  punct("(")
  val arg = parseExpr()
  punct(")")
  return App(name, arg)
}

suspend fun Parser.parseExpr(): Tree = when {
  alt() -> parseLet()
  alt() -> parseApp()
  else -> parseGroup()
}

// <EXPR> ::= <NUMBER> | <IDENT> `(` <EXPR> (`,` <EXPR>)*  `)`
suspend fun Parser.parseCalls(): Int = if (alt()) {
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

class CombinedParser(lexer: Lexer, nondet: Nondet, raise: Raise<String>) : Parser, Nondet by nondet, Lexer by lexer,
  Raise<String> by raise

suspend fun <R> parse(input: String, block: suspend Parser.() -> R): ParseResult<R> = handle {
  Raise<LexerError, _> { Left("${it.msg}: ${it.pos}") }.lexer(input) {
    skipWhitespace {
      CombinedParser(
        this,
        Nondet { use { k -> k(true).recover { k(false).bind() } } },
        Raise { it.left() }
      ).block().right()
    }
  }
}