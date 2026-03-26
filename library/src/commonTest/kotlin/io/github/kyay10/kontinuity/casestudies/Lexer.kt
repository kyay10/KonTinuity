package io.github.kyay10.kontinuity.casestudies

import arrow.core.raise.Raise
import arrow.core.raise.context.ensureNotNull
import arrow.core.raise.ensure
import arrow.core.raise.recover
import io.github.kyay10.kontinuity.*
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
          read() shouldEq token
        }
      }
    }
  }

  @Test
  fun example2() = runTestCC {
    report {
      lexer("foo()") {
        for (token in exampleTokens) {
          read() shouldEq token
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
            read() shouldEq token
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

interface Lexer : Read<Token> {
  suspend fun peek(): Token?
}

context(lexer: Lexer)
suspend fun peek() = lexer.peek()

data class LexerError(val msg: String, val pos: Position)

val dummyPosition = Position(0, 0, 0)

suspend fun <R> Raise<LexerError>.lexerFromList(l: List<Token>, block: suspend context(Lexer) () -> R) = runState(0) {
  handle {
    block(object : Lexer {
      override suspend fun peek(): Token? = l.getOrNull(value)
      override suspend fun read(): Token =
        l.getOrNull(value++) ?: raise(LexerError("Unexpected end of input", dummyPosition))
    })
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

suspend fun <R> Raise<LexerError>.lexer(input: String, block: suspend context(Lexer) () -> R): R {
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

  return runReader(Data(index = 0, col = 1, line = 1)) {
    handle {
      block(object : Lexer {
        override suspend fun peek(): Token? = tokenDescriptors.firstNotNullOfOrNull { (kind, regex) ->
          regex.find(input.substring(value.index))?.let {
            Token(kind, it.value, value.toPosition())
          }
        }

        override suspend fun read(): Token {
          val position = value.toPosition()
          ensure(value.index < input.length) { LexerError("Unexpected EOS", position) }
          val tok = ensureNotNull(peek()) { LexerError("Cannot tokenize input", position) }
          value.consume(tok.text)
          return tok
        }
      })
    }
  }
}

context(_: Lexer)
suspend fun skipSpaces() {
  while (peek()?.kind == TokenKind.Space) {
    val _ = read()
  }
}

context(outer: Lexer)
suspend fun <R> skipWhitespace(block: suspend context(Lexer) () -> R): R = block(object : Lexer {
  override suspend fun read(): Token = with(outer) {
    skipSpaces()
    read()
  }

  override suspend fun peek(): Token? = with(outer) {
    skipSpaces()
    peek()
  }
})