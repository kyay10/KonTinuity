package effekt.casestudies

import arrow.core.raise.Raise
import arrow.core.raise.recover
import effekt.casestudies.TokenKind.*
import effekt.handleStateful
import effekt.useStateful
import io.kotest.matchers.shouldBe
import runTestCC
import kotlin.test.Test

class LexerTest {
  val exampleTokens = listOf(
    Token(Ident, "foo", Position(1, 1, 0)), Token(Punct, "(", Position(1, 4, 3)), Token(Punct, ")", Position(1, 5, 4))
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
            Token(Ident, "foo", Position(1, 1, 0)),
            Token(Punct, "(", Position(1, 5, 4)),
            Token(Punct, ")", Position(2, 2, 11))
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

interface Lexer {
  suspend fun peek(): Token?
  suspend fun next(): Token
}

data class LexerError(val msg: String, val pos: Position)

val dummyPosition = Position(0, 0, 0)

suspend fun <R> Raise<LexerError>.lexerFromList(l: List<Token>, block: suspend Lexer.() -> R): R = handleStateful(0) {
  object : Lexer {
    override suspend fun peek(): Token? = l.getOrNull(get())
    override suspend fun next(): Token = useStateful { k, i ->
      val token = l.getOrNull(i) ?: raise(LexerError("Unexpected end of input", dummyPosition))
      k(token, i + 1)
    }
  }.block()
}

inline fun report(block: Raise<LexerError>.() -> Unit) = recover(block) { (msg, pos) ->
  error("LexerError: ${pos.line}:${pos.col} $msg")
}

private val tokenDescriptors = mapOf(
  Number to "^[0-9]+".toRegex(),
  Ident to "^[a-zA-Z]+".toRegex(),
  Punct to "^[=,.()\\[\\]{}:]".toRegex(),
  Space to "^[ \t\n]+".toRegex()
)

fun Position.consume(text: String): Position {
  val lines = text.split("\n")
  val offset = lines.last().length
  return copy(
    line = line + lines.size - 1, col = if (lines.size > 1) offset else col + text.length, index = index + text.length
  )
}

suspend fun <R> Raise<LexerError>.lexer(input: String, block: suspend Lexer.() -> R): R =
  handleStateful(Position(index = 0, col = 1, line = 1)) {
    object : Lexer {
      private suspend fun eos(): Boolean = get().index >= input.length
      private suspend fun tryMatch(regex: Regex, tokenKind: TokenKind): Token? =
        regex.find(input.substring(get().index))?.let {
          Token(tokenKind, it.value, get())
        }

      private suspend fun tryMatchAll(map: Map<TokenKind, Regex>): Token? =
        map.firstNotNullOfOrNull { (kind, regex) -> tryMatch(regex, kind) }

      override suspend fun peek(): Token? = tryMatchAll(tokenDescriptors)
      override suspend fun next(): Token {
        val position = get()
        return if (eos()) raise(LexerError("Unexpected EOS", position))
        else {
          val tok = peek() ?: raise(LexerError("Cannot tokenize input", position))
          set(position.consume(tok.text))
          tok
        }
      }
    }.block()
  }

suspend fun Lexer.skipSpaces() {
  while (peek()?.kind == Space) next()
}

suspend fun <R> Lexer.skipWhitespace(block: suspend Lexer.() -> R): R = object : Lexer {
  override suspend fun next(): Token = with(this@skipWhitespace) {
    skipSpaces()
    return next()
  }

  override suspend fun peek(): Token? = with(this@skipWhitespace) {
    skipSpaces()
    return peek()
  }
}.block()