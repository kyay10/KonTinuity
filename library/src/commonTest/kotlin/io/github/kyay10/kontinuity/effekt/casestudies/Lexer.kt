package io.github.kyay10.kontinuity.effekt.casestudies

import arrow.core.raise.Raise
import arrow.core.raise.recover
import io.github.kyay10.kontinuity.DelegatingMultishotScope
import io.github.kyay10.kontinuity.MultishotScope
import io.github.kyay10.kontinuity.MultishotToken
import io.github.kyay10.kontinuity.ResetDsl
import io.github.kyay10.kontinuity.effekt.Stateful
import io.github.kyay10.kontinuity.effekt.StatefulPrompt
import io.github.kyay10.kontinuity.effekt.get
import io.github.kyay10.kontinuity.effekt.handleStateful
import io.github.kyay10.kontinuity.raise
import io.github.kyay10.kontinuity.runTestCC
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class LexerTest {
  val exampleTokens = listOf(
    Token(TokenKind.Ident, "foo", Position(1, 1, 0)), Token(TokenKind.Punct, "(", Position(1, 4, 3)), Token(TokenKind.Punct, ")", Position(1, 5, 4))
  )
  val dummyTokens = exampleTokens.map { it.copy(position = dummyPosition) }

  @Test
  fun example1() = runTestCC {
    report {
      lexerFromList(dummyTokens) {
        for (token in dummyTokens) {
          suspend fun <R> LexerScope<R>.check() = next() shouldBe token
          check()
        }
      }
    }
  }

  @Test
  fun example2() = runTestCC {
    report {
      lexer("foo()") {
        for (token in exampleTokens) {
          suspend fun <R> LexerScope<R>.check() = next() shouldBe token
          check()
        }
      }
    }
  }

  @Test
  fun example3() = runTestCC {
    report {
      suspend fun <R> LexerScope<R>.block() {
        suspend fun <IR: R> LexerScope<IR>.block() {
          for (token in listOf(
            Token(TokenKind.Ident, "foo", Position(1, 1, 0)),
            Token(TokenKind.Punct, "(", Position(1, 5, 4)),
            Token(TokenKind.Punct, ")", Position(2, 2, 11))
          )) {
            next() shouldBe token
          }
        }
        skipWhitespace { block() }
      }
      lexer("foo (   \n  )") { block() }
    }
  }
}

data class Position(val line: Int, val col: Int, val index: Int)

enum class TokenKind {
  Number, Ident, Punct, Space
}

data class Token(val kind: TokenKind, val text: String, val position: Position)

@LexerDsl
interface Lexer<in R> {
  suspend fun MultishotScope<R>.peek(): Token?
  suspend fun MultishotScope<R>.next(): Token
}

context(lexer: Lexer<R>)
suspend fun <R> MultishotScope<R>.next() = with(lexer) { next() }

context(lexer: Lexer<R>)
suspend fun <R> MultishotScope<R>.peek() = with(lexer) { peek() }

data class LexerError(val msg: String, val pos: Position)

val dummyPosition = Position(0, 0, 0)

@DslMarker annotation class LexerDsl
@LexerDsl
class LexerScope<R>(lexer: Lexer<R>, token: MultishotToken<R>): DelegatingMultishotScope<R>(token), Lexer<R> by lexer

context(_: Raise<LexerError>)
suspend fun <Ret, R> MultishotScope<R>.lexerFromList(l: List<Token>, block: suspend LexerScope<out R>.() -> Ret): Ret {
  data class Data(var index: Int)
  return handleStateful(Data(0), Data::copy) {
    fun <IR: R> StatefulPrompt<Ret, Data, IR, R>.lexer(): LexerScope<IR> = LexerScope(object : Lexer<IR> {
      override suspend fun MultishotScope<IR>.peek(): Token? = l.getOrNull(get().index)
      override suspend fun MultishotScope<IR>.next(): Token =
        l.getOrNull(get().index++) ?: raise(LexerError("Unexpected end of input", dummyPosition))
    }, token)
    block(lexer())
  }
}

inline fun report(block: Raise<LexerError>.() -> Unit) = recover(block) { (msg, pos) ->
  error("LexerError: ${pos.line}:${pos.col} $msg")
}

private val tokenDescriptors = mapOf(
  TokenKind.Number to "^[0-9]+".toRegex(),
  TokenKind.Ident to "^[a-zA-Z]+".toRegex(),
  TokenKind.Punct to "^[=,.()\\[\\]{}:]".toRegex(),
  TokenKind.Space to "^[ \t\n]+".toRegex()
)

context(raise: Raise<LexerError>)
suspend fun <Ret, R> MultishotScope<R>.lexer(input: String, block: suspend LexerScope<out R>.() -> Ret): Ret {
  data class Data(var index: Int, var col: Int, var line: Int) : Stateful<Data> {
    fun toPosition() = Position(line, col, index)
    fun consume(text: String) {
      val lines = text.split("\n")
      val offset = lines.last().length
      line += lines.size - 1
      col = if (lines.size > 1) offset else col + text.length
      index += text.length
    }

    override fun fork() = copy()
  }

  return handleStateful(Data(index = 0, col = 1, line = 1)) {

    fun <IR: R> StatefulPrompt<Ret, Data, IR, R>.lexer(): LexerScope<IR> = LexerScope(object : Lexer<IR> {
      private suspend fun MultishotScope<IR>.eos(): Boolean = get().index >= input.length
      private suspend fun MultishotScope<IR>.tryMatch(regex: Regex, tokenKind: TokenKind): Token? =
        regex.find(input.substring(get().index))?.let {
          Token(tokenKind, it.value, get().toPosition())
        }

      private suspend fun MultishotScope<IR>.tryMatchAll(map: Map<TokenKind, Regex>): Token? =
        map.firstNotNullOfOrNull { (kind, regex) -> tryMatch(regex, kind) }

      override suspend fun MultishotScope<IR>.peek(): Token? = tryMatchAll(tokenDescriptors)
      override suspend fun MultishotScope<IR>.next(): Token {
        val position = get().toPosition()
        if (eos()) raise(LexerError("Unexpected EOS", position))
        val tok = peek() ?: raise(LexerError("Cannot tokenize input", position))
        get().consume(tok.text)
        return tok
      }
    }, token)
    block(lexer())
  }
}

context(lexer: Lexer<R>)
suspend fun <R> MultishotScope<R>.skipSpaces() {
  while (peek()?.kind == TokenKind.Space) next()
}

context(lexer: Lexer<R>)
suspend fun <Ret, R> MultishotScope<R>.skipWhitespace(block: suspend LexerScope<out R>.() -> Ret): Ret = block(LexerScope(object : Lexer<R> {
  override suspend fun MultishotScope<R>.next(): Token = with(lexer) {
    skipSpaces()
    return next()
  }

  override suspend fun MultishotScope<R>.peek(): Token? = with(lexer) {
    skipSpaces()
    return peek()
  }
}, token))