package io.github.kyay10.kontinuity.effekt.casestudies

import arrow.core.Either
import arrow.core.Either.Left
import arrow.core.Either.Right
import arrow.core.left
import arrow.core.raise.Raise
import arrow.core.recover
import arrow.core.right
import io.github.kyay10.kontinuity.*
import io.github.kyay10.kontinuity.effekt.HandlerPrompt
import io.github.kyay10.kontinuity.effekt.casestudies.NondetLexerScope
import io.github.kyay10.kontinuity.effekt.casestudies.TokenKind.*
import io.github.kyay10.kontinuity.effekt.handle
import io.github.kyay10.kontinuity.effekt.use
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class ParserTest {
  @Test
  fun example() = runTestCC {
    context(_: Raise<String>)
    suspend fun <R> NondetLexerScope<R>.parseCallsHere() = parseCalls()
    parse("42") { parseCallsHere() } shouldBe Right(1)
    parse("foo(1)") { parseCallsHere() } shouldBe Right(2)
    parse("foo(1, 2)") { parseCallsHere() } shouldBe Right(3)
    parse("foo(1, 2, 3, 4)") { parseCallsHere() } shouldBe Right(5)
    parse("foo(1, 2, bar(4, 5))") { parseCallsHere() } shouldBe Right(6)
    parse("foo(1, 2,\nbar(4, 5))") { parseCallsHere() } shouldBe Right(6)

    context(_: Raise<String>)
    suspend fun <R> NondetLexerScope<R>.parseExprHere() = parseExpr()
    parse("}42") { parseExprHere() } shouldBe Left("Expected ( but got }")
    parse("42") { parseExprHere() } shouldBe Right(Lit(42))
    parse("let x = 4 in 42") { parseExprHere() } shouldBe Right(Let("x", Lit(4), Lit(42)))
    parse("let x = let y = 2 in 1 in 42") { parseExprHere() } shouldBe Right(Let("x", Let("y", Lit(2), Lit(1)), Lit(42)))
    parse("let x = (let y = 2 in 1) in 42") { parseExprHere() } shouldBe Right(Let("x", Let("y", Lit(2), Lit(1)), Lit(42)))
    parse("let x = (let y = f(42) in 1) in 42") { parseExprHere() } shouldBe Right(
      Let(
        "x", Let("y", App("f", Lit(42)), Lit(1)), Lit(42)
      )
    )
    parse("let x = (let y = f(let z = 1 in z) in 1) in 42") { parseExprHere() } shouldBe Right(
      Let(
        "x", Let("y", App("f", Let("z", Lit(1), Var("z"))), Lit(1)), Lit(42)
      )
    )
  }
}

@DslMarker annotation class NondetDsl
@NondetDsl
fun interface Nondet<in R> {
  suspend fun MultishotScope<R>.alt(): Boolean
}

context(nondet: Nondet<R>)
suspend fun <R> MultishotScope<R>.alt() = with(nondet) { alt() }

context(_: Nondet<R>, _: Lexer<R>, _: Raise<String>)
suspend inline fun <R> MultishotScope<R>.accept(expectedText: String = "", predicate: (Token) -> Boolean): Token {
  val token = next()
  return if (predicate(token)) token
  else raise("unexpected token $token, expected $expectedText")
}

context(_: Nondet<R>, _: Lexer<R>, _: Raise<String>)
suspend fun <R> MultishotScope<R>.any() = accept { t -> true }

context(_: Nondet<R>, _: Lexer<R>, _: Raise<String>)
suspend fun <R> MultishotScope<R>.accept(exp: TokenKind) = accept(exp.toString()) { t -> t.kind == exp }

context(_: Nondet<R>, _: Lexer<R>, _: Raise<String>)
suspend fun <R> MultishotScope<R>.ident() = accept(Ident).text

context(_: Nondet<R>, _: Lexer<R>, _: Raise<String>)
suspend fun <R> MultishotScope<R>.number() = accept(Number).text

context(_: Nondet<R>, _: Lexer<R>, _: Raise<String>)
suspend fun <R> MultishotScope<R>.punct(p: String) {
  val text = accept(Punct).text
  if (text != p) raise("Expected $p but got $text")
}

context(_: Nondet<R>, _: Lexer<R>, _: Raise<String>)
suspend fun <R> MultishotScope<R>.kw(exp: String) {
  val text = ident()
  if (text != exp) raise("Expected keyword $exp but got $text")
}

context(_: Nondet<R>)
suspend inline fun <Ret, R> MultishotScope<R>.opt(block: () -> Ret): Ret? = if (alt()) block() else null

context(_: Nondet<R>)
suspend inline fun <R> MultishotScope<R>.many(block: () -> Unit) {
  while (alt()) block()
}

context(_: Nondet<R>)
suspend inline fun <R> MultishotScope<R>.some(block: () -> Unit) {
  do block() while (alt())
}

sealed interface Tree
data class Lit(val value: Int) : Tree
data class Var(val name: String) : Tree
data class Let(val name: String, val binding: Tree, val body: Tree) : Tree
data class App(val name: String, val arg: Tree) : Tree

context(_: Nondet<R>, _: Lexer<R>, _: Raise<String>)
suspend fun <R> MultishotScope<R>.parseNum(): Tree {
  val num = number()
  return Lit(num.toIntOrNull() ?: raise("Expected number, but cannot convert input to integer: $num"))
}

context(_: Nondet<R>, _: Lexer<R>, _: Raise<String>)
suspend fun <R> MultishotScope<R>.parseVar(): Tree = Var(ident())

context(_: Nondet<R>, _: Lexer<R>, _: Raise<String>)
suspend fun <R> MultishotScope<R>.parseAtom(): Tree = opt { parseNum() } ?: parseVar()

context(_: Nondet<R>, _: Lexer<R>, _: Raise<String>)
suspend fun <R> MultishotScope<R>.parseLet(): Tree {
  kw("let")
  val name = ident()
  punct("=")
  val binding = parseExpr()
  kw("in")
  val body = parseExpr()
  return Let(name, binding, body)
}

context(_: Nondet<R>, _: Lexer<R>, _: Raise<String>)
suspend fun <R> MultishotScope<R>.parseGroup(): Tree = opt { parseAtom() } ?: Unit.run {
  punct("(")
  val expr = parseExpr()
  punct(")")
  return expr
}

context(_: Nondet<R>, _: Lexer<R>, _: Raise<String>)
suspend fun <R> MultishotScope<R>.parseApp(): Tree {
  val name = ident()
  punct("(")
  val arg = parseExpr()
  punct(")")
  return App(name, arg)
}

context(_: Nondet<R>, _: Lexer<R>, _: Raise<String>)
suspend fun <R> MultishotScope<R>.parseExpr(): Tree = when {
  alt() -> parseLet()
  alt() -> parseApp()
  else -> parseGroup()
}

// <EXPR> ::= <NUMBER> | <IDENT> `(` <EXPR> (`,` <EXPR>)*  `)`
context(_: Nondet<R>, _: Lexer<R>, _: Raise<String>)
suspend fun <R> MultishotScope<R>.parseCalls(): Int = if (alt()) {
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

@NondetDsl
@LexerDsl
class NondetLexerScope<R>(nondet: Nondet<R>, lexer: Lexer<R>, token: MultishotToken<R>) :
  DelegatingMultishotScope<R>(token), Nondet<R> by nondet, Lexer<R> by lexer

context(nondet: Nondet<R>, lexer: Lexer<R>)
val <R> MultishotScope<R>.nondetLexerScope: NondetLexerScope<R>
  get() = NondetLexerScope(nondet, lexer, token)

suspend fun <Ret, R> MultishotScope<R>.parse(
  input: String,
  block: suspend context(Raise<String>) NondetLexerScope<out R>.() -> Ret
): ParseResult<Ret> {
  suspend fun <IR : R> HandlerPrompt<ParseResult<Ret>, IR, R>.function() =
    with(Raise<LexerError, _> { Left("${it.msg}: ${it.pos}") }) {
      suspend fun <IIR : IR> LexerScope<IIR>.function() = kotlin.run {
        suspend fun <IIIR : IIR> LexerScope<IIIR>.function() =
          context(Nondet { use { k -> k(true).recover { k(false).bind() } } }, Raise<String, _> { it.left() }) {
            scoped(nondetLexerScope) { block().right() }
        }
        skipWhitespace { function() }
      }
      lexer(input) { function() }
    }
  return handle { function() }
}