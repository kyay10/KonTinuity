package io.github.kyay10.kontinuity.effekt.casestudies

import arrow.core.Either.Left
import arrow.core.Either.Right
import arrow.core.None
import arrow.core.Option
import arrow.core.getOrElse
import arrow.core.raise.SingletonRaise
import arrow.core.recover
import arrow.core.some
import io.github.kyay10.kontinuity.effekt.get
import io.github.kyay10.kontinuity.effekt.handle
import io.github.kyay10.kontinuity.effekt.handleStateful
import io.github.kyay10.kontinuity.effekt.use
import io.github.kyay10.kontinuity.given
import io.github.kyay10.kontinuity.Raise
import io.github.kyay10.kontinuity.raise
import io.github.kyay10.kontinuity.runTestCC
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.text.repeat

class PrettyPrinterTest {
  context(_: Indent, _: DefaultIndent, _: Flow, _: Emit, _: LayoutChoice)
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

  context(_: Indent, _: DefaultIndent, _: Flow, _: Emit, _: LayoutChoice)
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

  context(_: Indent, _: DefaultIndent, _: Flow, _: Emit, _: LayoutChoice)
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

  context(_: Indent, _: DefaultIndent, _: Flow, _: Emit, _: LayoutChoice)
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

  context(_: Indent, _: DefaultIndent, _: Flow, _: Emit, _: LayoutChoice)
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
  suspend fun indent(): Int
}

context(indent: Indent)
suspend fun indent() = indent.indent()

fun interface DefaultIndent {
  suspend fun defaultIndent(): Int
}

context(defaultIndent: DefaultIndent)
suspend fun defaultIndent() = defaultIndent.defaultIndent()

fun interface Flow {
  suspend fun flow(): Direction
}

context(flow: Flow)
suspend fun flow() = flow.flow()

fun interface Emit {
  suspend fun emitText(text: String)
  suspend fun emitNewline() = emitText("\n")
}

context(emit: Emit)
suspend fun text(content: String) = emit.emitText(content)

context(emit: Emit)
suspend fun newline() = emit.emitNewline()

context(_: Emit)
suspend fun space() = text(" ")

context(_: Emit)
suspend fun spaces(n: Int) {
  if (n > 0) {
    text(" ".repeat(n))
  }
}

context(_: Indent, _: Flow, _: Emit)
suspend fun lineOr(replace: String) = when (flow()) {
  Direction.Horizontal -> text(replace)
  Direction.Vertical -> {
    newline()
    spaces(indent())
  }
}

context(_: Indent, _: Flow, _: Emit)
suspend fun line() = lineOr(" ")

context(_: Indent, _: Flow, _: Emit)
suspend fun linebreak() = lineOr("")

// Uses `n` as the indentation in the given document
context(_: Indent)
suspend inline fun <R> withIndent(n: Int, doc: suspend context(Indent) () -> R): R = with(Indent { n }) {
  doc()
}

context(_: Indent)
suspend inline fun <R> nest(
  j: Int, doc: suspend context(Indent) () -> R
): R = withIndent(indent() + j, doc)

context(_: Indent, _: DefaultIndent)
suspend inline fun <R> nested(
  doc: suspend context(Indent) () -> R
): R = nest(defaultIndent(), doc)

context(_: Flow)
suspend inline fun <R> fix(
  direction: Direction, doc: suspend context(Flow) () -> R
): R = with(Flow { direction }) {
  doc(given<Flow>())
}

context(_: Flow)
suspend inline fun <R> horizontal(
  doc: suspend context(Flow) () -> R
): R = fix(Direction.Horizontal, doc)

context(_: Flow)
suspend inline fun <R> vertical(
  doc: suspend context(Flow) () -> R
): R = fix(Direction.Vertical, doc)

fun interface LayoutChoice {
  suspend fun choice(): Direction
}

context(layoutChoice: LayoutChoice)
suspend fun choice() = layoutChoice.choice()

context(_: Flow, _: LayoutChoice)
suspend inline fun group(
  doc: suspend context(Flow) () -> Unit
) = fix(choice(), doc)

context(_: Indent, _: Flow, _: Emit, _: LayoutChoice)
suspend fun softline() = group { line() }

context(_: Indent, _: Flow, _: Emit, _: LayoutChoice)
suspend fun softbreak() = group { linebreak() }

context(_: Indent, _: Flow, _: Emit)
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

context(_:Indent, _: Flow, _: Emit, _: LayoutChoice)
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

suspend fun <R> searchLayout(p: suspend context(SingletonRaise<Unit>, LayoutChoice) () -> R): Option<R> = handle {
  p(SingletonRaise(Raise { None })) {
    use { k ->
      k(Direction.Horizontal).recover { k(Direction.Vertical).bind() }
    }
  }.some()
}

suspend fun writer(p: suspend context(Emit) () -> Unit): String {
  data class Data(var content: String)
  return handleStateful(Data(""), Data::copy) {
    p { text ->
      get().content += text
    }
    get().content
  }
}

context(emit: Emit, layoutChoice: LayoutChoice, _: SingletonRaise<*>)
suspend fun printer(
  width: Int, defaultIndent: Int, block: suspend context(Indent, DefaultIndent, Flow, Emit) () -> Unit
) {
  data class PrinterData(var pos: Int)
  handleStateful(PrinterData(0), PrinterData::copy) {
    block(Indent { 0 }, DefaultIndent { defaultIndent }, Flow(layoutChoice::choice), object : Emit {
      override suspend fun emitText(text: String) = use { k ->
        get().pos += text.length
        if (get().pos > width) {
          raise()
        } else {
          emit.emitText(text)
          k(Unit)
        }
      }

      override suspend fun emitNewline() = use { k ->
        emit.emitNewline()
        get().pos = 0
        k(Unit)
      }
    })
  }
}

suspend fun pretty(
  width: Int, block: suspend context(Indent, DefaultIndent, Flow, Emit, LayoutChoice) () -> Unit
): String = searchLayout {
  writer {
    printer(width, 2) {
      block(given<Indent>(), given<DefaultIndent>(), given<Flow>(), given<Emit>(), given<LayoutChoice>())
    }
  }
}.getOrElse { "Cannot print document, since it would overflow." }

context(_: Emit)
suspend inline fun parens(block: () -> Unit) {
  text("(")
  block()
  text(")")
}


context(_: Emit)
suspend inline fun braces(block: () -> Unit) {
  text("{")
  block()
  text("}")
}


context(_: Indent, _: DefaultIndent, _: Flow, _: Emit, _: LayoutChoice)
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

suspend fun parseAndPrint(text: String, width: Int): String = when (val t = parse(text) { parseExpr() }) {
  is Right -> pretty(width) { t.value.emit() }
  is Left -> t.value
}

suspend fun example4() = parseAndPrint("let x = (let y = 2 in 1) in 42", 10)
