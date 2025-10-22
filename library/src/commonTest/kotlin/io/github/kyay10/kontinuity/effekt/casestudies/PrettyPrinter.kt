package io.github.kyay10.kontinuity.effekt.casestudies

import arrow.core.*
import arrow.core.Either.Left
import arrow.core.Either.Right
import arrow.core.raise.SingletonRaise
import io.github.kyay10.kontinuity.*
import io.github.kyay10.kontinuity.effekt.get
import io.github.kyay10.kontinuity.effekt.handle
import io.github.kyay10.kontinuity.effekt.handleStateful
import io.github.kyay10.kontinuity.effekt.use
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class PrettyPrinterTest {
  context(_: Indent, _: DefaultIndent, _: Flow, _: Emit, _: LayoutChoice, _: MultishotScope)
  suspend fun example4b() {
    text("def"); space(); text("foo"); parens {
      group {
        nest(2) {
          linebreak()
          group { text("x"); text(":"); space(); text("Int"); text(",") }
          line()
          group { text("y"); text(":"); space(); text("String") }
        }
        linebreak()
      }
    }
  }

  context(_: Indent, _: DefaultIndent, _: Flow, _: Emit, _: LayoutChoice, _: MultishotScope)
  suspend fun example3b() {
    example4b()
    space()
    braces {
      group {
        nest(2) {
          line()
          text("var"); space(); text("z"); space(); text("="); space(); text("42"); text(";")
        }
        line()
      }
    }
  }

  context(_: Indent, _: DefaultIndent, _: Flow, _: Emit, _: LayoutChoice, _: MultishotScope)
  suspend fun example6() {
    group {
      text("this")
      nest(9) {
        line()
        group { text("takes"); line(); text("many"); line(); text("f") }
      }
      line()
      text("l")
    }
  }

  context(_: Indent, _: DefaultIndent, _: Flow, _: Emit, _: LayoutChoice, _: MultishotScope)
  suspend fun example7() {
    group {
      text("this")
      line()
      text("will")
      nest(9) {
        line()
        group { text("take"); line(); text("many") }
      }
      line()
      text("lines")
    }
  }

  context(_: Indent, _: DefaultIndent, _: Flow, _: Emit, _: LayoutChoice, _: MultishotScope)
  suspend fun helloWorld() {
    text("hello")
    line()
    text("world")
  }

  @Test
  fun example() = runTestCC {
    pretty(5) { example1(listOf(1, 2, 3, 4)) } shouldBe """
      |[1,
      |2, 3,
      |4, ]
    """.trimMargin()

    pretty(10) { example1(listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 1, 2, 3, 4)) } shouldBe """
      |[1, 2, 3,
      |4, 5, 6,
      |7, 8, 9,
      |1, 2, 3,
      |4, ]
    """.trimMargin()

    example4() shouldBe """
      |let x =
      |  let y =
      |    2
      |  in 1
      |in 42
    """.trimMargin()

    pretty(30) { example4b() } shouldBe """def foo(x: Int, y: String)"""
    pretty(20) { example4b() } shouldBe """
      |def foo(
      |  x: Int,
      |  y: String
      |)
    """.trimMargin()

    pretty(50) { example3b() } shouldBe """def foo(x: Int, y: String) { var z = 42; }"""
    pretty(15) { example3b() } shouldBe """
      |def foo(
      |  x: Int,
      |  y: String
      |) {
      |  var z = 42;
      |}
    """.trimMargin()

    pretty(6) { example2() } shouldBe """
      |Hi
      |you!!!
    """.trimMargin()

    pretty(15) { example3() } shouldBe """
      |this
      |         takes
      |         four
      |lines
    """.trimMargin()

    pretty(14) { example6() } shouldBe """
      |this takes
      |         many
      |         f l
    """.trimMargin()

    pretty(14) { example7() } shouldBe """
      |this
      |will
      |         take
      |         many
      |lines
    """.trimMargin()
  }
}

enum class Direction {
  Horizontal, Vertical
}

fun interface Indent {
  context(_: MultishotScope)
  suspend fun indent(): Int
}

context(indent: Indent, _: MultishotScope)
suspend fun indent() = indent.indent()

fun interface DefaultIndent {
  context(_: MultishotScope)
  suspend fun defaultIndent(): Int
}

context(defaultIndent: DefaultIndent, _: MultishotScope)
suspend fun defaultIndent() = defaultIndent.defaultIndent()

fun interface Flow {
  context(_: MultishotScope)
  suspend fun flow(): Direction
}

context(flow: Flow, _: MultishotScope)
suspend fun flow() = flow.flow()

fun interface Emit {
  context(_: MultishotScope)
  suspend fun emitText(text: String)
  context(_: MultishotScope)
  suspend fun emitNewline() = emitText("\n")
}

context(emit: Emit, _: MultishotScope)
suspend fun text(content: String) = emit.emitText(content)

context(emit: Emit, _: MultishotScope)
suspend fun newline() = emit.emitNewline()

context(_: Emit, _: MultishotScope)
suspend fun space() = text(" ")

context(_: Emit, _: MultishotScope)
suspend fun spaces(n: Int) {
  if (n > 0) {
    text(" ".repeat(n))
  }
}

context(_: Indent, _: Flow, _: Emit, _: MultishotScope)
suspend fun lineOr(replace: String) = when (flow()) {
  Direction.Horizontal -> text(replace)
  Direction.Vertical -> {
    newline()
    spaces(indent())
  }
}

context(_: Indent, _: Flow, _: Emit, _: MultishotScope)
suspend fun line() = lineOr(" ")

context(_: Indent, _: Flow, _: Emit, _: MultishotScope)
suspend fun linebreak() = lineOr("")

// Uses `n` as the indentation in the given document
context(_: Indent, _: MultishotScope)
suspend inline fun <R> withIndent(n: Int, doc: suspend context(Indent, MultishotScope) () -> R): R =
  context(Indent { n }) {
    doc()
  }

context(_: Indent, _: MultishotScope)
suspend inline fun <R> nest(
  j: Int, doc: suspend context(Indent, MultishotScope) () -> R
): R = withIndent(indent() + j, doc)

context(_: Indent, _: DefaultIndent, _: MultishotScope)
suspend inline fun <R> nested(
  doc: suspend context(Indent, MultishotScope) () -> R
): R = nest(defaultIndent(), doc)

context(_: Flow, _: MultishotScope)
suspend inline fun <R> fix(
  direction: Direction, doc: suspend context(Flow, MultishotScope) () -> R
): R = context(Flow { direction }) {
  doc()
}

context(_: Flow, _: MultishotScope)
suspend inline fun <R> horizontal(
  doc: suspend context(Flow, MultishotScope) () -> R
): R = fix(Direction.Horizontal, doc)

context(_: Flow, _: MultishotScope)
suspend inline fun <R> vertical(
  doc: suspend context(Flow, MultishotScope) () -> R
): R = fix(Direction.Vertical, doc)

fun interface LayoutChoice {
  context(_: MultishotScope)
  suspend fun choice(): Direction
}

context(layoutChoice: LayoutChoice, _: MultishotScope)
suspend fun choice() = layoutChoice.choice()

context(_: Flow, _: LayoutChoice, _: MultishotScope)
suspend inline fun group(
  doc: suspend context(Flow, MultishotScope) () -> Unit
) = fix(choice(), doc)

context(_: Indent, _: Flow, _: Emit, _: LayoutChoice, _: MultishotScope)
suspend fun softline() = group { line() }

context(_: Indent, _: Flow, _: Emit, _: LayoutChoice, _: MultishotScope)
suspend fun softbreak() = group { linebreak() }

context(_: Indent, _: Flow, _: Emit, _: MultishotScope)
suspend fun example1(l: List<Int>) {
  text("[")
  var n = 0
  while (n < l.size) {
    text(l[n].toString())
    text(",")
    line()
    n++
  }
  text("]")
}

context(_: Indent, _: Flow, _: Emit, _: LayoutChoice, _: MultishotScope)
suspend fun example2() {
  group {
    text("Hi")
    line()
    text("you")
  }
  text("!!!")
}

context(_: Indent, _: Flow, _: Emit, _: LayoutChoice, _: MultishotScope)
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

context(_: MultishotScope)
suspend fun <R> searchLayout(p: suspend context(SingletonRaise<Unit>, LayoutChoice, MultishotScope) () -> R): Option<R> =
  handle {
    context(SingletonRaise<Unit>(Raise { None }), LayoutChoice {
      use { k ->
        k(Direction.Horizontal).recover { k(Direction.Vertical).bind() }
      }
    }) { p() }.some()
  }

context(_: MultishotScope)
suspend fun writer(p: suspend context(Emit, MultishotScope) () -> Unit): String {
  data class Data(var content: String)
  return handleStateful(Data(""), Data::copy) {
    context(Emit { text ->
      get().content += text
    }) {
      p()
    }
    get().content
  }
}

context(emit: Emit, layoutChoice: LayoutChoice, _: SingletonRaise<*>, _: MultishotScope)
suspend fun printer(
  width: Int, defaultIndent: Int, block: suspend context(Indent, DefaultIndent, Flow, Emit, MultishotScope) () -> Unit
) {
  data class PrinterData(var pos: Int)
  handleStateful(PrinterData(0), PrinterData::copy) {
    context(Indent { 0 }, DefaultIndent { defaultIndent }, Flow { layoutChoice.choice() }, object : Emit {
      context(_: MultishotScope)
      override suspend fun emitText(text: String) {
        get().pos += text.length
        if (get().pos > width) {
          raise()
        } else {
          emit.emitText(text)
        }
      }

      context(_: MultishotScope)
      override suspend fun emitNewline() {
        emit.emitNewline()
        get().pos = 0
      }
    }) {
      block()
    }
  }
}

context(_: MultishotScope)
suspend fun pretty(
  width: Int, block: suspend context(Indent, DefaultIndent, Flow, Emit, LayoutChoice, MultishotScope) () -> Unit
): String = searchLayout {
  writer {
    printer(width, 2) {
      block()
    }
  }
}.getOrElse { "Cannot print document, since it would overflow." }

context(_: Emit, _: MultishotScope)
suspend inline fun parens(block: () -> Unit) {
  text("(")
  block()
  text(")")
}


context(_: Emit, _: MultishotScope)
suspend inline fun braces(block: () -> Unit) {
  text("{")
  block()
  text("}")
}


context(_: Indent, _: DefaultIndent, _: Flow, _: Emit, _: LayoutChoice, _: MultishotScope)
suspend fun Tree.emit(): Unit = when (this) {
  is Lit -> text(value.toString())
  is Var -> text(name)
  is Let -> {
    text("let"); space(); text(name); space(); text("=")
    group {
      nested { line(); binding.emit() }
      line()
      text("in")
    }
    group { nested { line(); body.emit() } }
  }

  is App -> {
    text(name); parens {
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

context(_: MultishotScope)
suspend fun parseAndPrint(text: String, width: Int): String = when (val t = parse(text) { parseExpr() }) {
  is Right -> pretty(width) { t.value.emit() }
  is Left -> t.value
}

context(_: MultishotScope)
suspend fun example4() = parseAndPrint("let x = (let y = 2 in 1) in 42", 10)
