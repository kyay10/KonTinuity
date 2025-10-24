package io.github.kyay10.kontinuity.effekt.casestudies

import arrow.core.raise.Raise
import arrow.core.raise.recover
import io.github.kyay10.kontinuity.MultishotScope
import io.github.kyay10.kontinuity.effekt.Stateful
import io.github.kyay10.kontinuity.effekt.get
import io.github.kyay10.kontinuity.effekt.handleStateful
import io.github.kyay10.kontinuity.runTestCC
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class LexerTest {
  val exampleTokens = listOf(
    Token(TokenKind.Ident, "foo", Position(1, 1, 0)),
    Token(TokenKind.Punct, "(", Position(1, 4, 3)),
    Token(TokenKind.Punct, ")", Position(1, 5, 4))
  )
  val dummyTokens = exampleTokens.map { it.copy(position = dummyPosition) }

  @Test
  fun example1() = runTestCC {
    report {
      lexerFromList(dummyTokens) {
        for (token in dummyTokens) {
          next() shouldBe token
        }
      }
    }
  }

  @Test
  fun example2() = runTestCC {
    report {
      lexer("foo()") {
        for (token in exampleTokens) {
          next() shouldBe token
        }
      }
    }
  }

  @Test
  fun example3() = runTestCC {
    report {
      lexer("foo (   \n  )") {
        skipWhitespace {
          for (token in listOf(
            Token(TokenKind.Ident, "foo", Position(1, 1, 0)),
            Token(TokenKind.Punct, "(", Position(1, 5, 4)),
            Token(TokenKind.Punct, ")", Position(2, 2, 11))
          )) {
            next() shouldBe token
          }
        }
      }
    }
  }
}

data class Position(val line: Int, val col: Int, val index: Int)

enum class TokenKind {
  Number, Ident, Punct, Space
}

data class Token(val kind: TokenKind, val text: String, val position: Position)

interface Lexer<in Region> {
  context(_: MultishotScope<Region>)
  suspend fun peek(): Token?

  context(_: MultishotScope<Region>)
  suspend fun next(): Token
}

context(lexer: Lexer<Region>, _: MultishotScope<Region>)
suspend fun <Region> next() = lexer.next()

context(lexer: Lexer<Region>, _: MultishotScope<Region>)
suspend fun <Region> peek() = lexer.peek()

data class LexerError(val msg: String, val pos: Position)

val dummyPosition = Position(0, 0, 0)

context(_: MultishotScope<Region>)
suspend fun <R, Region> Raise<LexerError>.lexerFromList(
  l: List<Token>,
  block: suspend context(MultishotScope<Region>) Lexer<Region>.() -> R
): R {
  data class Data(var index: Int)
  return handleStateful(Data(0), Data::copy) {
    object : Lexer<Region> {
      context(_: MultishotScope<Region>)
      override suspend fun peek(): Token? = l.getOrNull(get().index)

      context(_: MultishotScope<Region>)
      override suspend fun next(): Token =
        l.getOrNull(get().index++) ?: raise(LexerError("Unexpected end of input", dummyPosition))
    }.block()
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

context(_: MultishotScope<Region>)
suspend fun <R, Region> Raise<LexerError>.lexer(input: String, block: suspend context(MultishotScope<Region>) Lexer<Region>.() -> R): R {
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
    object : Lexer<Region> {
      private fun eos(): Boolean = get().index >= input.length
      private fun tryMatch(regex: Regex, tokenKind: TokenKind): Token? =
        regex.find(input.substring(get().index))?.let {
          Token(tokenKind, it.value, get().toPosition())
        }

      private fun tryMatchAll(map: Map<TokenKind, Regex>): Token? =
        map.firstNotNullOfOrNull { (kind, regex) -> tryMatch(regex, kind) }

      context(_: MultishotScope<Region>)
      override suspend fun peek(): Token? = tryMatchAll(tokenDescriptors)

      context(_: MultishotScope<Region>)
      override suspend fun next(): Token {
        val position = get().toPosition()
        if (eos()) raise(LexerError("Unexpected EOS", position))
        val tok = peek() ?: raise(LexerError("Cannot tokenize input", position))
        get().consume(tok.text)
        return tok
      }
    }.block()
  }
}

context(_: MultishotScope<Region>)
suspend fun <Region> Lexer<Region>.skipSpaces() {
  while (peek()?.kind == TokenKind.Space) next()
}

inline fun <R, Region> Lexer<Region>.skipWhitespace(block: Lexer<Region>.() -> R): R = object : Lexer<Region> {
  context(_: MultishotScope<Region>)
  override suspend fun next(): Token = with(this@skipWhitespace) {
    skipSpaces()
    return next()
  }

  context(_: MultishotScope<Region>)
  override suspend fun peek(): Token? = with(this@skipWhitespace) {
    skipSpaces()
    return peek()
  }
}.block()