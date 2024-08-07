package effekt.casestudies

import arrow.core.Either
import effekt.get
import effekt.handle
import effekt.handleStateful
import effekt.use
import io.kotest.matchers.shouldBe
import runTestCC
import kotlin.test.Test

class AnfTest {
  // let x = f(g(42)) in x
  private val exampleTree: Tree = Let("x", App("f", App("g", Lit(42))), Var("x"))

  @Test
  fun example() = runTestCC {
    translate(exampleTree) shouldBe CLet(
      "x", CLet("x1", CRet(CLit(42)), CLet("x2", CApp("g", CVar("x1")), CApp("f", CVar("x2")))), CRet(CVar("x"))
    )

    pretty(translate(exampleTree)) shouldBe """
      |let x = let x1 = return 42 in let x2 =
      |      g(x1)
      |    in f(x2) in return x
    """.trimMargin()

    pipeline("42") shouldBe """
      |return 42
    """.trimMargin()

    pipeline("let x = 4 in 42") shouldBe """
      |let x = return 4 in return 42
    """.trimMargin()

    pipeline("let x = let y = 2 in 1 in 42") shouldBe """
      |let x = let y = return 2 in return 1 in
      |  return 42
    """.trimMargin()

    pipeline("let x = (let y = 2 in 1) in 42") shouldBe """
      |let x = let y = return 2 in return 1 in
      |  return 42
    """.trimMargin()

    pipeline("let x = (let y = f(42) in 1) in 42") shouldBe """
      |let x = let y = let x1 = return 42 in f(
      |        x1
      |      ) in return 1 in return 42
    """.trimMargin()
  }
}

sealed interface Expr
data class CLit(val value: Int) : Expr
data class CVar(val name: String) : Expr

sealed interface Stmt
data class CLet(val name: String, val binding: Stmt, val body: Stmt) : Stmt
data class CApp(val name: String, val arg: Expr) : Stmt
data class CRet(val expr: Expr) : Stmt

fun interface Fresh {
  suspend fun fresh(): String
}

suspend fun <R> freshVars(block: suspend context(Fresh) () -> R): R {
  data class Data(var i: Int)
  return handleStateful(Data(0), Data::copy) {
    block {
      "x${++(get().i)}"
    }
  }
}

fun interface Bind {
  suspend fun Stmt.bind(): Expr
}

context(Bind, Fresh)
suspend fun Tree.toStmt(): Stmt = when (this) {
  is Lit -> CRet(CLit(value))
  is Var -> CRet(CVar(name))
  // Here we use bind since other than App, CApp requires an expression
  is App -> CApp(name, arg.toStmt().bind())
  // here we use the handler `bindHere` to mark positions where bindings
  // should be inserted.
  is Let -> CLet(name, bindHere { binding.toStmt() }, bindHere { body.toStmt() })
}

context(Fresh)
suspend fun bindHere(block: suspend context(Bind) () -> Stmt): Stmt = handle {
  block {
    use { resume ->
      val id = fresh()
      CLet(id, this, resume(CVar(id)))
    }
  }
}

suspend fun translate(e: Tree): Stmt = freshVars { bindHere { e.toStmt() } }

context(Emit)
suspend fun Expr.emit() = text(
  when (this) {
    is CLit -> value.toString()
    is CVar -> name
  }
)

context(Indent, DefaultIndent, Flow, Emit, LayoutChoice)
suspend fun Stmt.emit(): Unit = when (this) {
  is CLet -> {
    text("let"); space(); text(name); space(); text("=")
    group {
      nested { line(); binding.emit() }
      line()
      text("in")
    }
    group { nested { line(); body.emit() } }
  }

  is CApp -> {
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

  is CRet -> {
    text("return"); space(); expr.emit()
  }
}

suspend fun pretty(s: Stmt): String = pretty(40) { s.emit() }

suspend fun pipeline(input: String): String = when (val res = parse(input) { parseExpr() }) {
  is Either.Right -> pretty(translate(res.value))
  is Either.Left -> res.value
}