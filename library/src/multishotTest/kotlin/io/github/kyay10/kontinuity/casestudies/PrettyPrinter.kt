package io.github.kyay10.kontinuity.casestudies

import arrow.core.Either.Left
import arrow.core.Either.Right
import arrow.core.getOrElse
import arrow.core.raise.context.ensure
import io.github.kyay10.kontinuity.*
import kotlin.test.Test

class PrettyPrinterTest {
  context(_: Indent, _: DefaultIndent, _: Flow, _: Emit, _: LayoutChoice)
  suspend fun example4b() {
    text("def")
    space()
    text("foo")
    parens {
      group {
        nest(2) {
          linebreak()
          group {
            text("x")
            text(":")
            space()
            text("Int")
            text(",")
          }
          line()
          group {
            text("y")
            text(":")
            space()
            text("String")
          }
        }
        linebreak()
      }
    }
  }

  context(_: Indent, _: DefaultIndent, _: Flow, _: Emit, _: LayoutChoice)
  suspend fun example3b() {
    example4b()
    space()
    braces {
      group {
        nest(2) {
          line()
          text("var")
          space()
          text("z")
          space()
          text("=")
          space()
          text("42")
          text(";")
        }
        line()
      }
    }
  }

  context(_: Indent, _: DefaultIndent, _: Flow, _: Emit, _: LayoutChoice)
  suspend fun example6() {
    group {
      text("this")
      nest(9) {
        line()
        group {
          text("takes")
          line()
          text("many")
          line()
          text("f")
        }
      }
      line()
      text("l")
    }
  }

  context(_: Indent, _: DefaultIndent, _: Flow, _: Emit, _: LayoutChoice)
  suspend fun example7() {
    group {
      text("this")
      line()
      text("will")
      nest(9) {
        line()
        group {
          text("take")
          line()
          text("many")
        }
      }
      line()
      text("lines")
    }
  }

  @Test
  fun example() = runTestCC {
    pretty(5) { example1(listOf(1, 2, 3, 4)) } shouldEq
      """
      |[1,
      |2, 3,
      |4, ]
      """
        .trimMargin()

    pretty(10) { example1(listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 1, 2, 3, 4)) } shouldEq
      """
      |[1, 2, 3,
      |4, 5, 6,
      |7, 8, 9,
      |1, 2, 3,
      |4, ]
      """
        .trimMargin()

    example4() shouldEq
      """
      |let x =
      |  let y =
      |    2
      |  in 1
      |in 42
      """
        .trimMargin()

    pretty(30) { example4b() } shouldEq """def foo(x: Int, y: String)"""
    pretty(20) { example4b() } shouldEq
      """
      |def foo(
      |  x: Int,
      |  y: String
      |)
      """
        .trimMargin()

    pretty(50) { example3b() } shouldEq """def foo(x: Int, y: String) { var z = 42; }"""
    pretty(15) { example3b() } shouldEq
      """
      |def foo(
      |  x: Int,
      |  y: String
      |) {
      |  var z = 42;
      |}
      """
        .trimMargin()

    pretty(6) { example2() } shouldEq
      """
      |Hi
      |you!!!
      """
        .trimMargin()

    pretty(15) { example3() } shouldEq
      """
      |this
      |         takes
      |         four
      |lines
      """
        .trimMargin()

    pretty(14) { example6() } shouldEq
      """
      |this takes
      |         many
      |         f l
      """
        .trimMargin()

    pretty(14) { example7() } shouldEq
      """
      |this
      |will
      |         take
      |         many
      |lines
      """
        .trimMargin()
  }
}

enum class Direction {
  Horizontal,
  Vertical,
}

data class Indent(val indent: Int)

context(indent: Indent)
val indent
  get() = indent.indent

data class DefaultIndent(val defaultIndent: Int)

context(defaultIndent: DefaultIndent)
val defaultIndent
  get() = defaultIndent.defaultIndent

fun interface Flow {
  suspend fun flow(): Direction
}

context(flow: Flow)
suspend fun flow() = flow.flow()

fun interface Emit {
  fun emitText(text: String)
}

context(emit: Emit)
fun text(content: String) = emit.emitText(content)

context(emit: Emit)
fun newline() = text("\n")

context(_: Emit)
fun space() = text(" ")

context(_: Emit)
fun spaces(n: Int) {
  if (n > 0) {
    text(" ".repeat(n))
  }
}

context(_: Indent, _: Flow, _: Emit)
suspend fun lineOr(replace: String) =
  when (flow()) {
    Direction.Horizontal -> text(replace)
    Direction.Vertical -> {
      newline()
      spaces(indent)
    }
  }

context(_: Indent, _: Flow, _: Emit)
suspend fun line() = lineOr(" ")

context(_: Indent, _: Flow, _: Emit)
suspend fun linebreak() = lineOr("")

// Uses `n` as the indentation in the given document
context(_: Indent)
suspend inline fun <R> withIndent(
  n: Int,
  doc: suspend context(Indent) () -> R,
): R = doc(Indent(n))

context(_: Indent)
suspend inline fun <R> nest(
  j: Int,
  doc: suspend context(Indent) () -> R,
): R = withIndent(indent + j, doc)

context(_: Indent, _: DefaultIndent)
suspend inline fun <R> nested(doc: suspend context(Indent) () -> R): R = nest(defaultIndent, doc)

context(_: Flow)
suspend inline fun <R> fix(
  direction: Direction,
  doc: suspend context(Flow) () -> R,
): R = doc(Flow { direction })

fun interface LayoutChoice {
  suspend fun choice(): Direction
}

context(layoutChoice: LayoutChoice)
suspend fun choice() = layoutChoice.choice()

context(_: Flow, _: LayoutChoice)
suspend inline fun group(doc: suspend context(Flow) () -> Unit) = fix(choice(), doc)

context(_: Indent, _: Flow, _: Emit)
suspend fun example1(l: List<Int>) {
  text("[")
  l.forEachIteratorless {
    text(it.toString())
    text(",")
    line()
  }
  text("]")
}

context(_: Indent, _: Flow, _: Emit, _: LayoutChoice)
suspend fun example2() {
  group {
    text("Hi")
    line()
    text("you")
  }
  text("!!!")
}

context(_: Indent, _: Flow, _: Emit, _: LayoutChoice)
suspend fun example3() = group {
  text("this")
  nest(9) {
    line()
    group {
      text("takes")
      line()
      text("four")
    }
  }
  line()
  text("lines")
}

suspend fun <R> searchLayout(p: suspend context(LayoutChoice, Exc) () -> R) = backtrack {
  context(LayoutChoice { if (flip()) Direction.Horizontal else Direction.Vertical }) { p() }
}

suspend fun writer(p: suspend context(Emit) () -> Unit) =
  runState("") {
    p { text -> value += text }
    value
  }

context(_: Emit, _: LayoutChoice, _: Exc)
suspend fun printer(
  width: Int,
  defaultIndent: Int,
  block: suspend context(Indent, DefaultIndent, Flow, Emit) () -> Unit,
) =
  runState(0) {
    block(
      Indent(0),
      DefaultIndent(defaultIndent),
      Flow { choice() },
      Emit {
        it.lines().forEachIndexed { i, string ->
          if (i > 0) value = 0
          value += string.length
          ensure(value <= width) {}
        }
        text(it)
      },
    )
  }

suspend fun pretty(
  width: Int,
  block: suspend context(Indent, DefaultIndent, Flow, Emit, LayoutChoice) () -> Unit,
): String =
  searchLayout { writer { printer(width, 2) { block() } } }
    .getOrElse { "Cannot print document, since it would overflow." }

context(_: Emit)
inline fun parens(block: () -> Unit) {
  text("(")
  block()
  text(")")
}

context(_: Emit)
inline fun braces(block: () -> Unit) {
  text("{")
  block()
  text("}")
}

context(_: Indent, _: DefaultIndent, _: Flow, _: Emit, _: LayoutChoice)
suspend fun Tree.emit(): Unit =
  when (this) {
    is Lit -> text(value.toString())
    is Var -> text(name)
    is Let -> {
      text("let")
      space()
      text(name)
      space()
      text("=")
      group {
        nested {
          line()
          binding.emit()
        }
        line()
        text("in")
      }
      group {
        nested {
          line()
          body.emit()
        }
      }
    }

    is App -> {
      text(name)
      parens {
        group {
          nested {
            linebreak()
            arg.emit()
          }
          linebreak()
        }
      }
    }
  }

suspend fun parseAndPrint(text: String, width: Int): String =
  when (val t = parse(text) { parseExpr() }) {
    is Right -> pretty(width) { t.value.emit() }
    is Left -> t.value
  }

suspend fun example4() = parseAndPrint("let x = (let y = 2 in 1) in 42", 10)
