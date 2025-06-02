package io.github.kyay10.kontinuity.effekt.casestudies

import arrow.core.*
import arrow.core.Either.Left
import arrow.core.Either.Right
import arrow.core.raise.SingletonRaise
import io.github.kyay10.kontinuity.*
import io.github.kyay10.kontinuity.effekt.*
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class PrettyPrinterTest {
  context(_: Indent<R>, _: DefaultIndent<R>, _: Flow<R>, _: Emit<R>, _: LayoutChoice<R>)
  suspend fun <R> MultishotScope<R>.example4b() {
    text("def"); space(); text("foo"); parens {
      suspend fun <IR : R> FlowScope<IR>.f() {
        suspend fun <IIR : IR> IndentScope<IIR>.f() {
          linebreak()
          group { text("x"); text(":"); space(); text("Int"); text(",") }
          line()
          group { text("y"); text(":"); space(); text("String") }
        }
        nest(2) { f() }
        linebreak()
      }
      group { f() }
    }
  }

  context(_: Indent<R>, _: DefaultIndent<R>, _: Flow<R>, _: Emit<R>, _: LayoutChoice<R>)
  suspend fun <R> MultishotScope<R>.example3b() {
    example4b()
    space()
    braces {
      suspend fun <IR : R> FlowScope<IR>.f() {
        suspend fun <IIR : IR> IndentScope<IIR>.f() {
          line()
          text("var"); space(); text("z"); space(); text("="); space(); text("42"); text(";")
        }
        nest(2) { f() }
        line()
      }
      group { f() }
    }
  }

  context(_: Indent<R>, _: DefaultIndent<R>, _: Flow<R>, _: Emit<R>, _: LayoutChoice<R>)
  suspend fun <R> MultishotScope<R>.example6() {
    suspend fun <IR : R> FlowScope<IR>.f() {
      text("this")
      suspend fun <IIR : IR> IndentScope<IIR>.f() {
        line()
        suspend fun <IIIR : IIR> FlowScope<IIIR>.f() { text("takes"); line(); text("many"); line(); text("f") }
        group { f() }
      }
      nest(9) { f() }
      line()
      text("l")
    }
    group { f() }
  }

  context(_: Indent<R>, _: DefaultIndent<R>, _: Flow<R>, _: Emit<R>, _: LayoutChoice<R>)
  suspend fun <R> MultishotScope<R>.example7() {
    suspend fun <IR : R> FlowScope<IR>.f() {
      text("this")
      line()
      text("will")
      suspend fun <IIR : IR> IndentScope<IIR>.f() {
        line()
        suspend fun <IIIR : IIR> FlowScope<IIIR>.f() { text("take"); line(); text("many") }
        group { f() }
      }
      nest(9) { f() }
      line()
      text("lines")
    }
    group { f() }
  }

  context(_: Indent<R>, _: DefaultIndent<R>, _: Flow<R>, _: Emit<R>, _: LayoutChoice<R>)
  suspend fun <R> MultishotScope<R>.helloWorld() {
    text("hello")
    line()
    text("world")
  }

  @Test
  fun example() = runTestCC {
    suspend fun <R> IDiFELCScope<R>.ex11() = example1(listOf(1, 2, 3, 4))
    pretty(5) { ex11() } shouldBe """
      |[1,
      |2, 3,
      |4, ]
    """.trimMargin()

    suspend fun <R> IDiFELCScope<R>.ex12() = example1(listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 1, 2, 3, 4))
    pretty(10) { ex12() } shouldBe """
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

    suspend fun <R> IDiFELCScope<R>.ex4b() = example4b()
    pretty(30) { ex4b() } shouldBe """def foo(x: Int, y: String)"""
    pretty(20) { ex4b() } shouldBe """
      |def foo(
      |  x: Int,
      |  y: String
      |)
    """.trimMargin()

    suspend fun <R> IDiFELCScope<R>.ex3b() = example3b()
    pretty(50) { ex3b() } shouldBe """def foo(x: Int, y: String) { var z = 42; }"""
    pretty(15) { ex3b() } shouldBe """
      |def foo(
      |  x: Int,
      |  y: String
      |) {
      |  var z = 42;
      |}
    """.trimMargin()

    suspend fun <R> IDiFELCScope<R>.ex2() = example2()

    pretty(6) { ex2() } shouldBe """
      |Hi
      |you!!!
    """.trimMargin()

    suspend fun <R> IDiFELCScope<R>.ex3() = example3()
    pretty(15) { ex3() } shouldBe """
      |this
      |         takes
      |         four
      |lines
    """.trimMargin()

    suspend fun <R> IDiFELCScope<R>.ex6() = example6()
    pretty(14) { ex6() } shouldBe """
      |this takes
      |         many
      |         f l
    """.trimMargin()

    suspend fun <R> IDiFELCScope<R>.ex7() = example7()
    pretty(14) { ex7() } shouldBe """
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

@IndentDsl
fun interface Indent<in R> {
  suspend fun MultishotScope<R>.indent(): Int
}

context(indent: Indent<R>)
suspend fun <R> MultishotScope<R>.indent() = with(indent) { indent() }

@DslMarker
annotation class DefaultIndentDsl

@DefaultIndentDsl
fun interface DefaultIndent<in R> {
  suspend fun MultishotScope<R>.defaultIndent(): Int
}

context(defaultIndent: DefaultIndent<R>)
suspend fun <R> MultishotScope<R>.defaultIndent() = with(defaultIndent) { defaultIndent() }

@FlowDsl
fun interface Flow<in R> {
  suspend fun MultishotScope<R>.flow(): Direction
}

context(flow: Flow<R>)
suspend fun <R> MultishotScope<R>.flow() = with(flow) { flow() }

fun interface Emit<in R> {
  suspend fun MultishotScope<R>.emitText(text: String)
  suspend fun MultishotScope<R>.emitNewline() = emitText("\n")
}

context(emit: Emit<R>)
suspend fun <R> MultishotScope<R>.text(content: String) = with(emit) { emitText(content) }

context(emit: Emit<R>)
suspend fun <R> MultishotScope<R>.newline() = with(emit) { emitNewline() }

context(_: Emit<R>)
suspend fun <R> MultishotScope<R>.space() = text(" ")

context(_: Emit<R>)
suspend fun <R> MultishotScope<R>.spaces(n: Int) {
  if (n > 0) {
    text(" ".repeat(n))
  }
}

context(_: Indent<R>, _: Flow<R>, _: Emit<R>)
suspend fun <R> MultishotScope<R>.lineOr(replace: String) = when (flow()) {
  Direction.Horizontal -> text(replace)
  Direction.Vertical -> {
    newline()
    spaces(indent())
  }
}

context(_: Indent<R>, _: Flow<R>, _: Emit<R>)
suspend fun <R> MultishotScope<R>.line() = lineOr(" ")

context(_: Indent<R>, _: Flow<R>, _: Emit<R>)
suspend fun <R> MultishotScope<R>.linebreak() = lineOr("")

@DslMarker
annotation class IndentDsl

@IndentDsl
class IndentScope<R>(indent: Indent<R>, token: MultishotToken<R>) : DelegatingMultishotScope<R>(token),
  Indent<R> by indent

// Uses `n` as the indentation in the given document
context(_: Indent<R>)
suspend inline fun <Ret, R> MultishotScope<R>.withIndent(n: Int, doc: suspend IndentScope<out R>.() -> Ret): Ret =
  doc(IndentScope({ n }, token))

context(_: Indent<R>)
suspend inline fun <Ret, R> MultishotScope<R>.nest(
  j: Int, doc: suspend IndentScope<out R>.() -> Ret
): Ret = withIndent(indent() + j, doc)

context(_: Indent<R>, _: DefaultIndent<R>)
suspend inline fun <Ret, R> MultishotScope<R>.nested(
  doc: suspend IndentScope<out R>.() -> Ret
): Ret = nest(defaultIndent(), doc)

@DslMarker
annotation class FlowDsl

@FlowDsl
class FlowScope<R>(flow: Flow<R>, token: MultishotToken<R>) : DelegatingMultishotScope<R>(token), Flow<R> by flow

context(_: Flow<R>)
suspend inline fun <Ret, R> MultishotScope<R>.fix(
  direction: Direction, doc: suspend FlowScope<out R>.() -> Ret
): Ret = doc(FlowScope({ direction }, token))

context(_: Flow<R>)
suspend inline fun <Ret, R> MultishotScope<R>.horizontal(
  doc: suspend FlowScope<out R>.() -> Ret
): Ret = fix(Direction.Horizontal, doc)

context(_: Flow<R>)
suspend inline fun <Ret, R> MultishotScope<R>.vertical(
  doc: suspend FlowScope<out R>.() -> Ret
): Ret = fix(Direction.Vertical, doc)

@LayoutChoiceDsl
fun interface LayoutChoice<in R> {
  suspend fun MultishotScope<R>.choice(): Direction
}

context(layoutChoice: LayoutChoice<R>)
suspend fun <R> MultishotScope<R>.choice() = with(layoutChoice) { choice() }

context(_: Flow<R>, _: LayoutChoice<R>)
suspend inline fun <R> MultishotScope<R>.group(
  doc: suspend FlowScope<out R>.() -> Unit
) = fix(choice(), doc)

context(_: Indent<R>, _: Flow<R>, _: Emit<R>, _: LayoutChoice<R>)
suspend fun <R> MultishotScope<R>.softline() = group {
  suspend fun <IR : R> FlowScope<IR>.f() = line()
  f()
}

context(_: Indent<R>, _: Flow<R>, _: Emit<R>, _: LayoutChoice<R>)
suspend fun <R> MultishotScope<R>.softbreak() = group {
  suspend fun <IR : R> FlowScope<IR>.f() = linebreak()
  f()
}

context(_: Indent<R>, _: Flow<R>, _: Emit<R>)
suspend fun <R> MultishotScope<R>.example1(l: List<Int>) {
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

context(_: Indent<R>, _: Flow<R>, _: Emit<R>, _: LayoutChoice<R>)
suspend fun <R> MultishotScope<R>.example2() {
  suspend fun <IR : R> FlowScope<IR>.f() {
    text("Hi")
    line()
    text("you")
  }
  group { f() }
  text("!!!")
}

context(_: Indent<R>, _: Flow<R>, _: Emit<R>, _: LayoutChoice<R>)
suspend fun <R> MultishotScope<R>.example3() = group {
  suspend fun <IR : R> FlowScope<IR>.f() {
    text("this")
    suspend fun <IIR : IR> IndentScope<IIR>.f() {
      line()
      suspend fun <IIIR : IIR> FlowScope<IIIR>.f() {
        text("takes")
        line()
        text("four")
      }
      group { f() }
    }
    nest(9) { f() }
    line()
    text("lines")
  }
  f()
}

@DslMarker
annotation class LayoutChoiceDsl

@LayoutChoiceDsl
class LayoutChoiceScope<R>(
  layoutChoice: LayoutChoice<R>, token: MultishotToken<R>
) : DelegatingMultishotScope<R>(token), LayoutChoice<R> by layoutChoice

private val <IR : OR, OR, Ret> HandlerPrompt<Option<Ret>, IR, OR>.layoutChoiceScope: LayoutChoiceScope<IR>
  get() = LayoutChoiceScope(
    {
      use { k ->
        k(Direction.Horizontal).recover { k(Direction.Vertical).bind() }
      }
    },
    token
  )

suspend fun <Ret, R> MultishotScope<R>.searchLayout(p: suspend context(SingletonRaise<Unit>) LayoutChoiceScope<out R>.() -> Ret): Option<Ret> =
  handle {
    p(SingletonRaise(Raise { None }), layoutChoiceScope).some()
}

suspend fun <R> MultishotScope<R>.writer(p: suspend context(Emit<R>) MultishotScope<R>.() -> Unit): String {
  data class Data(var content: String)
  return runReader(Data(""), Data::copy) {
    p({ text ->
      ask().content += text
    }, this)
    ask().content
  }
}

class IDiFEScope<R>(
  indent: Indent<R>, defaultIndent: DefaultIndent<R>, flow: Flow<R>, emit: Emit<R>,
  token: MultishotToken<R>
) : DelegatingMultishotScope<R>(token),
  Indent<R> by indent, DefaultIndent<R> by defaultIndent, Flow<R> by flow, Emit<R> by emit

context(emit: Emit<R>, layoutChoice: LayoutChoice<R>, _: SingletonRaise<*>)
suspend fun <R> MultishotScope<R>.printer(
  width: Int, defaultIndent: Int, block: suspend IDiFEScope<out R>.() -> Unit
) {
  data class PrinterData(var pos: Int)

  fun <IR : R> StatefulPrompt<Unit, PrinterData, IR, R>.iDiFEScope(): IDiFEScope<IR> =
    IDiFEScope({ 0 }, { defaultIndent }, { choice() }, object : Emit<IR> {
      override suspend fun MultishotScope<IR>.emitText(text: String) {
        get().pos += text.length
        if (get().pos > width) {
          raise()
        } else {
          with(emit) {
            emitText(text)
          }
        }
      }

      override suspend fun MultishotScope<IR>.emitNewline() {
        with(emit) {
          emitNewline()
        }
        get().pos = 0
      }
    }, token)
  handleStateful(PrinterData(0), PrinterData::copy) {
    block(iDiFEScope())
  }
}

class IDiFELCScope<R>(
  indent: Indent<R>, defaultIndent: DefaultIndent<R>, flow: Flow<R>, emit: Emit<R>,
  layoutChoice: LayoutChoice<R>, token: MultishotToken<R>
) : DelegatingMultishotScope<R>(token),
  Indent<R> by indent, DefaultIndent<R> by defaultIndent, Flow<R> by flow, Emit<R> by emit,
  LayoutChoice<R> by layoutChoice

context(indent: Indent<R>, defaultIndent: DefaultIndent<R>, flow: Flow<R>, emit: Emit<R>,
  layoutChoice: LayoutChoice<R>)
val <R> MultishotScope<R>.idiFELC: IDiFELCScope<R>
  get() = IDiFELCScope(indent, defaultIndent, flow, emit, layoutChoice, token)

suspend fun <R> MultishotScope<R>.pretty(
  width: Int, block: suspend IDiFELCScope<out R>.() -> Unit
): String = searchLayout {
  suspend fun <IR : R> LayoutChoiceScope<IR>.function() = writer {
    printer(width, 2) {
      suspend fun <IIR : IR> IDiFEScope<IIR>.function(): Unit = block(idiFELC)
      function()
    }
  }
  function()
}.getOrElse { "Cannot print document, since it would overflow." }

context(_: Emit<R>)
suspend inline fun <R> MultishotScope<R>.parens(block: () -> Unit) {
  text("(")
  block()
  text(")")
}


context(_: Emit<R>)
suspend inline fun <R> MultishotScope<R>.braces(block: () -> Unit) {
  text("{")
  block()
  text("}")
}

context(_: Indent<R>, _: DefaultIndent<R>, _: Flow<R>, _: Emit<R>, _: LayoutChoice<R>)
suspend fun <R> MultishotScope<R>.emit(tree: Tree): Unit = when (tree) {
  is Lit -> text(tree.value.toString())
  is Var -> text(tree.name)
  is Let -> {
    text("let"); space(); text(tree.name); space(); text("=")
    suspend fun <IR : R> FlowScope<IR>.f1() {
      suspend fun <IIR : IR> IndentScope<IIR>.f2() {
        line(); emit(tree.binding)
      }
      nested { f2() }
      line()
      text("in")
    }
    group { f1() }
    suspend fun <IR : R> FlowScope<IR>.f2() {
      suspend fun <IIR : IR> IndentScope<IIR>.f3() {
        line(); emit(tree.body)
      }
      nested { f3() }
    }
    group { f2() }
  }

  is App -> {
    suspend fun <IR : R> FlowScope<IR>.f1() {
      suspend fun <IIR : IR> IndentScope<IIR>.f2() {
        linebreak()
        emit(tree.arg)
      }
      nested { f2() }
      linebreak()
    }
    text(tree.name); parens {
      group { f1() }
    }
  }
}

suspend fun <R> MultishotScope<R>.parseAndPrint(text: String, width: Int): String = when (val t = parse(text) {
  suspend fun <IR : R> NondetLexerScope<IR>.pe() = parseExpr()
  pe()
}) {
  is Right -> pretty(width) {
    suspend fun <IR : R> IDiFELCScope<IR>.e() = emit(t.value)
    e()
  }
  is Left -> t.value
}

suspend fun <R> MultishotScope<R>.example4() = parseAndPrint("let x = (let y = 2 in 1) in 42", 10)
