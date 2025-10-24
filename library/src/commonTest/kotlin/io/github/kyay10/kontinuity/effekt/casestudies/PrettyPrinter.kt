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
  context(_: Indent<Region>, _: DefaultIndent<Region>, _: Flow<Region>, _: Emit<Region>, _: LayoutChoice<Region>, _: MultishotScope<Region>)
  suspend fun <Region> example4b() {
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

  context(_: Indent<Region>, _: DefaultIndent<Region>, _: Flow<Region>, _: Emit<Region>, _: LayoutChoice<Region>, _: MultishotScope<Region>)
  suspend fun <Region> example3b() {
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

  context(_: Indent<Region>, _: DefaultIndent<Region>, _: Flow<Region>, _: Emit<Region>, _: LayoutChoice<Region>, _: MultishotScope<Region>)
  suspend fun <Region> example6() {
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

  context(_: Indent<Region>, _: DefaultIndent<Region>, _: Flow<Region>, _: Emit<Region>, _: LayoutChoice<Region>, _: MultishotScope<Region>)
  suspend fun <Region> example7() {
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

  context(_: Indent<Region>, _: DefaultIndent<Region>, _: Flow<Region>, _: Emit<Region>, _: LayoutChoice<Region>, _: MultishotScope<Region>)
  suspend fun <Region> helloWorld() {
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

fun interface Indent<in Region> {
  context(_: MultishotScope<Region>)
  suspend fun indent(): Int
}

context(indent: Indent<Region>, _: MultishotScope<Region>)
suspend fun <Region> indent() = indent.indent()

fun interface DefaultIndent<in Region> {
  context(_: MultishotScope<Region>)
  suspend fun defaultIndent(): Int
}

context(defaultIndent: DefaultIndent<Region>, _: MultishotScope<Region>)
suspend fun <Region> defaultIndent() = defaultIndent.defaultIndent()

fun interface Flow<in Region> {
  context(_: MultishotScope<Region>)
  suspend fun flow(): Direction
}

context(flow: Flow<Region>, _: MultishotScope<Region>)
suspend fun <Region> flow() = flow.flow()

fun interface Emit<in Region> {
  context(_: MultishotScope<Region>)
  suspend fun emitText(text: String)

  context(_: MultishotScope<Region>)
  suspend fun emitNewline() = emitText("\n")
}

context(emit: Emit<Region>, _: MultishotScope<Region>)
suspend fun <Region> text(content: String) = emit.emitText(content)

context(emit: Emit<Region>, _: MultishotScope<Region>)
suspend fun <Region> newline() = emit.emitNewline()

context(_: Emit<Region>, _: MultishotScope<Region>)
suspend fun <Region> space() = text(" ")

context(_: Emit<Region>, _: MultishotScope<Region>)
suspend fun <Region> spaces(n: Int) {
  if (n > 0) {
    text(" ".repeat(n))
  }
}

context(_: Indent<Region>, _: Flow<Region>, _: Emit<Region>, _: MultishotScope<Region>)
suspend fun <Region> lineOr(replace: String) = when (flow()) {
  Direction.Horizontal -> text(replace)
  Direction.Vertical -> {
    newline()
    spaces(indent())
  }
}

context(_: Indent<Region>, _: Flow<Region>, _: Emit<Region>, _: MultishotScope<Region>)
suspend fun <Region> line() = lineOr(" ")

context(_: Indent<Region>, _: Flow<Region>, _: Emit<Region>, _: MultishotScope<Region>)
suspend fun <Region> linebreak() = lineOr("")

// Uses `n` as the indentation in the given document
context(_: Indent<Region>, _: MultishotScope<Region>)
suspend inline fun <R, Region> withIndent(
  n: Int,
  doc: suspend context(Indent<Region>, MultishotScope<Region>) () -> R
): R =
  context(Indent<Region> { n }) {
    doc()
  }

context(_: Indent<Region>, _: MultishotScope<Region>)
suspend inline fun <R, Region> nest(
  j: Int, doc: suspend context(Indent<Region>, MultishotScope<Region>) () -> R
): R = withIndent(indent() + j, doc)

context(_: Indent<Region>, _: DefaultIndent<Region>, _: MultishotScope<Region>)
suspend inline fun <R, Region> nested(
  doc: suspend context(Indent<Region>, MultishotScope<Region>) () -> R
): R = nest(defaultIndent(), doc)

context(_: Flow<Region>, _: MultishotScope<Region>)
suspend inline fun <R, Region> fix(
  direction: Direction, doc: suspend context(Flow<Region>, MultishotScope<Region>) () -> R
): R = context(Flow<Region> { direction }) {
  doc()
}

context(_: Flow<Region>, _: MultishotScope<Region>)
suspend inline fun <R, Region> horizontal(
  doc: suspend context(Flow<Region>, MultishotScope<Region>) () -> R
): R = fix(Direction.Horizontal, doc)

context(_: Flow<Region>, _: MultishotScope<Region>)
suspend inline fun <R, Region> vertical(
  doc: suspend context(Flow<Region>, MultishotScope<Region>) () -> R
): R = fix(Direction.Vertical, doc)

fun interface LayoutChoice<in Region> {
  context(_: MultishotScope<Region>)
  suspend fun choice(): Direction
}

context(layoutChoice: LayoutChoice<Region>, _: MultishotScope<Region>)
suspend fun <Region> choice() = layoutChoice.choice()

context(_: Flow<Region>, _: LayoutChoice<Region>, _: MultishotScope<Region>)
suspend inline fun <Region> group(
  doc: suspend context(Flow<Region>, MultishotScope<Region>) () -> Unit
) = fix(choice(), doc)

context(_: Indent<Region>, _: Flow<Region>, _: Emit<Region>, _: LayoutChoice<Region>, _: MultishotScope<Region>)
suspend fun <Region> softline() = group { line() }

context(_: Indent<Region>, _: Flow<Region>, _: Emit<Region>, _: LayoutChoice<Region>, _: MultishotScope<Region>)
suspend fun <Region> softbreak() = group { linebreak() }

context(_: Indent<Region>, _: Flow<Region>, _: Emit<Region>, _: MultishotScope<Region>)
suspend fun <Region> example1(l: List<Int>) {
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

context(_: Indent<Region>, _: Flow<Region>, _: Emit<Region>, _: LayoutChoice<Region>, _: MultishotScope<Region>)
suspend fun <Region> example2() {
  group {
    text("Hi")
    line()
    text("you")
  }
  text("!!!")
}

context(_: Indent<Region>, _: Flow<Region>, _: Emit<Region>, _: LayoutChoice<Region>, _: MultishotScope<Region>)
suspend fun <Region> example3() = group {
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

context(_: MultishotScope<Region>)
suspend fun <R, Region> searchLayout(p: suspend context(SingletonRaise<Unit>, LayoutChoice<NewRegion>, NewScope<Region>) () -> R): Option<R> =
  handle {
    context(SingletonRaise<Unit>(Raise { None }), LayoutChoice {
      use { k ->
        k(Direction.Horizontal).recover { k(Direction.Vertical).bind() }
      }
    }) { p() }.some()
  }

context(_: MultishotScope<Region>)
suspend fun <Region> writer(p: suspend context(Emit<Region>, MultishotScope<Region>) () -> Unit): String {
  data class Data(var content: String)
  return handleStateful(Data(""), Data::copy) {
    context(Emit<Region> { text ->
      get().content += text
    }) {
      p()
    }
    get().content
  }
}

context(emit: Emit<Region>, layoutChoice: LayoutChoice<Region>, _: SingletonRaise<*>, _: MultishotScope<Region>)
suspend fun <Region> printer(
  width: Int,
  defaultIndent: Int,
  block: suspend context(Indent<Region>, DefaultIndent<Region>, Flow<Region>, Emit<Region>, MultishotScope<Region>) () -> Unit
) {
  data class PrinterData(var pos: Int)
  handleStateful(PrinterData(0), PrinterData::copy) {
    context(
      Indent<Region> { 0 },
      DefaultIndent<Region> { defaultIndent },
      Flow { layoutChoice.choice() },
      object : Emit<Region> {
        context(_: MultishotScope<Region>)
        override suspend fun emitText(text: String) {
          get().pos += text.length
          if (get().pos > width) {
            raise()
          } else {
            emit.emitText(text)
          }
        }

        context(_: MultishotScope<Region>)
        override suspend fun emitNewline() {
          emit.emitNewline()
          get().pos = 0
        }
      }) {
      block()
    }
  }
}

context(_: MultishotScope<Region>)
suspend fun <Region> pretty(
  width: Int,
  block: suspend context(Indent<NewRegion>, DefaultIndent<NewRegion>, Flow<NewRegion>, Emit<NewRegion>, LayoutChoice<NewRegion>, NewScope<Region>) () -> Unit
): String = searchLayout {
  writer {
    printer(width, 2) {
      block()
    }
  }
}.getOrElse { "Cannot print document, since it would overflow." }

context(_: Emit<Region>, _: MultishotScope<Region>)
suspend inline fun <Region> parens(block: () -> Unit) {
  text("(")
  block()
  text(")")
}


context(_: Emit<Region>, _: MultishotScope<Region>)
suspend inline fun <Region> braces(block: () -> Unit) {
  text("{")
  block()
  text("}")
}


context(_: Indent<Region>, _: DefaultIndent<Region>, _: Flow<Region>, _: Emit<Region>, _: LayoutChoice<Region>, _: MultishotScope<Region>)
suspend fun <Region> Tree.emit(): Unit = when (this) {
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

context(_: MultishotScope<Any?>)
suspend fun parseAndPrint(text: String, width: Int): String = when (val t = parse(text) { parseExpr() }) {
  is Right -> pretty(width) { t.value.emit() }
  is Left -> t.value
}

context(_: MultishotScope<Any?>)
suspend fun example4() = parseAndPrint("let x = (let y = 2 in 1) in 42", 10)
