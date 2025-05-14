package io.github.kyay10.kontinuity.effekt.casestudies

import arrow.core.Either.Left
import arrow.core.Either.Right
import arrow.core.None
import arrow.core.Option
import arrow.core.getOrElse
import arrow.core.raise.SingletonRaise
import arrow.core.recover
import arrow.core.some
import io.github.kyay10.kontinuity.MultishotScope
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
  suspend fun MultishotScope.example4b() {
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
  suspend fun MultishotScope.example3b() {
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
  suspend fun MultishotScope.example6() {
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
  suspend fun MultishotScope.example7() {
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
  suspend fun MultishotScope.helloWorld() {
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
  suspend fun MultishotScope.indent(): Int
}

context(indent: Indent)
suspend fun MultishotScope.indent() = with(indent) { indent() }

fun interface DefaultIndent {
  suspend fun MultishotScope.defaultIndent(): Int
}

context(defaultIndent: DefaultIndent)
suspend fun MultishotScope.defaultIndent() = with(defaultIndent) { defaultIndent() }

fun interface Flow {
  suspend fun MultishotScope.flow(): Direction
}

context(flow: Flow)
suspend fun MultishotScope.flow() = with(flow) { flow() }

fun interface Emit {
  suspend fun MultishotScope.emitText(text: String)
  suspend fun MultishotScope.emitNewline() = emitText("\n")
}

context(emit: Emit)
suspend fun MultishotScope.text(content: String) = with(emit) { emitText(content) }

context(emit: Emit)
suspend fun MultishotScope.newline() = with(emit) { emitNewline() }

context(_: Emit)
suspend fun MultishotScope.space() = text(" ")

context(_: Emit)
suspend fun MultishotScope.spaces(n: Int) {
  if (n > 0) {
    text(" ".repeat(n))
  }
}

context(_: Indent, _: Flow, _: Emit)
suspend fun MultishotScope.lineOr(replace: String) = when (flow()) {
  Direction.Horizontal -> text(replace)
  Direction.Vertical -> {
    newline()
    spaces(indent())
  }
}

context(_: Indent, _: Flow, _: Emit)
suspend fun MultishotScope.line() = lineOr(" ")

context(_: Indent, _: Flow, _: Emit)
suspend fun MultishotScope.linebreak() = lineOr("")

// Uses `n` as the indentation in the given document
context(_: Indent)
suspend inline fun <R> MultishotScope.withIndent(n: Int, doc: suspend context(Indent) MultishotScope.() -> R): R = with(Indent { n }) {
  doc()
}

context(_: Indent)
suspend inline fun <R> MultishotScope.nest(
  j: Int, doc: suspend context(Indent) MultishotScope.() -> R
): R = withIndent(indent() + j, doc)

context(_: Indent, _: DefaultIndent)
suspend inline fun <R> MultishotScope.nested(
  doc: suspend context(Indent) MultishotScope.() -> R
): R = nest(defaultIndent(), doc)

context(_: Flow)
suspend inline fun <R> MultishotScope.fix(
  direction: Direction, doc: suspend context(Flow) MultishotScope.() -> R
): R = with(Flow { direction }) {
  doc()
}

context(_: Flow)
suspend inline fun <R> MultishotScope.horizontal(
  doc: suspend context(Flow) MultishotScope.() -> R
): R = fix(Direction.Horizontal, doc)

context(_: Flow)
suspend inline fun <R> MultishotScope.vertical(
  doc: suspend context(Flow) MultishotScope.() -> R
): R = fix(Direction.Vertical, doc)

fun interface LayoutChoice {
  suspend fun MultishotScope.choice(): Direction
}

context(layoutChoice: LayoutChoice)
suspend fun MultishotScope.choice() = with(layoutChoice) { choice() }

context(_: Flow, _: LayoutChoice)
suspend inline fun MultishotScope.group(
  doc: suspend context(Flow) MultishotScope.() -> Unit
) = fix(choice(), doc)

context(_: Indent, _: Flow, _: Emit, _: LayoutChoice)
suspend fun MultishotScope.softline() = group { line() }

context(_: Indent, _: Flow, _: Emit, _: LayoutChoice)
suspend fun MultishotScope.softbreak() = group { linebreak() }

context(_: Indent, _: Flow, _: Emit)
suspend fun MultishotScope.example1(l: List<Int>) {
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
suspend fun MultishotScope.example2() {
  group {
    text("Hi")
    line()
    text("you")
  }
  text("!!!")
}

context(_: Indent, _: Flow, _: Emit, _: LayoutChoice)
suspend fun MultishotScope.example3() = group {
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

suspend fun <R> MultishotScope.searchLayout(p: suspend context(SingletonRaise<Unit>, LayoutChoice) MultishotScope.() -> R): Option<R> = handle {
  p(SingletonRaise(Raise { None }), {
    use { k ->
      k(Direction.Horizontal).recover { k(Direction.Vertical).bind() }
    }
  }, this).some()
}

suspend fun MultishotScope.writer(p: suspend context(Emit) MultishotScope.() -> Unit): String {
  data class Data(var content: String)
  return handleStateful(Data(""), Data::copy) {
    p({ text ->
      get().content += text
    }, this)
    get().content
  }
}

context(emit: Emit, layoutChoice: LayoutChoice, _: SingletonRaise<*>)
suspend fun MultishotScope.printer(
  width: Int, defaultIndent: Int, block: suspend context(Indent, DefaultIndent, Flow, Emit) MultishotScope.() -> Unit
) {
  data class PrinterData(var pos: Int)
  handleStateful(PrinterData(0), PrinterData::copy) {
    block(Indent { 0 }, DefaultIndent { defaultIndent }, Flow { choice() }, object : Emit {
      override suspend fun MultishotScope.emitText(text: String) {
        get().pos += text.length
        if (get().pos > width) {
          raise()
        } else {
          with(emit) {
            emitText(text)
          }
        }
      }

      override suspend fun MultishotScope.emitNewline() {
        with(emit) {
          emitNewline()
        }
        get().pos = 0
      }
    }, this)
  }
}

suspend fun MultishotScope.pretty(
  width: Int, block: suspend context(Indent, DefaultIndent, Flow, Emit, LayoutChoice) MultishotScope.() -> Unit
): String = searchLayout {
  writer {
    printer(width, 2) {
      block(given<Indent>(), given<DefaultIndent>(), given<Flow>(), given<Emit>(), given<LayoutChoice>(), this)
    }
  }
}.getOrElse { "Cannot print document, since it would overflow." }

context(_: Emit)
suspend inline fun MultishotScope.parens(block: () -> Unit) {
  text("(")
  block()
  text(")")
}


context(_: Emit)
suspend inline fun MultishotScope.braces(block: () -> Unit) {
  text("{")
  block()
  text("}")
}


context(_: Indent, _: DefaultIndent, _: Flow, _: Emit, _: LayoutChoice)
suspend fun MultishotScope.emit(tree: Tree): Unit = when (tree) {
  is Lit -> text(tree.value.toString())
  is Var -> text(tree.name)
  is Let -> {
    text("let"); space(); text(tree.name); space(); text("=")
    group {
      nested { line(); emit(tree.binding) }
      line()
      text("in")
    }
    group { nested { line(); emit(tree.body) } }
  }

  is App -> {
    text(tree.name); parens {
      group {
        nested {
          linebreak()
          emit(tree.arg)
        }
        linebreak()
      }
    }
  }
}

suspend fun MultishotScope.parseAndPrint(text: String, width: Int): String = when (val t = parse(text) { parseExpr() }) {
  is Right -> pretty(width) { emit(t.value) }
  is Left -> t.value
}

suspend fun MultishotScope.example4() = parseAndPrint("let x = (let y = 2 in 1) in 42", 10)
