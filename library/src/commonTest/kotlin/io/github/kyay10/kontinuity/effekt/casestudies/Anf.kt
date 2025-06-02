package io.github.kyay10.kontinuity.effekt.casestudies

import arrow.core.Either
import io.github.kyay10.kontinuity.DelegatingMultishotScope
import io.github.kyay10.kontinuity.MultishotScope
import io.github.kyay10.kontinuity.MultishotToken
import io.github.kyay10.kontinuity.ResetDsl
import io.github.kyay10.kontinuity.effekt.*
import io.github.kyay10.kontinuity.runTestCC
import io.kotest.matchers.shouldBe
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

@FreshDsl
fun interface Fresh<in R> {
  suspend fun MultishotScope<R>.fresh(): String
}

context(fresh: Fresh<R>)
suspend fun <R> MultishotScope<R>.fresh(): String = with(fresh) { fresh() }

@DslMarker annotation class FreshDsl
@FreshDsl
class FreshScope<R>(fresh: Fresh<R>, token: MultishotToken<R>) : DelegatingMultishotScope<R>(token), Fresh<R> by fresh

suspend fun <Ret, R> MultishotScope<R>.freshVars(block: suspend FreshScope<out R>.() -> Ret): Ret {
  data class Data(var i: Int)
  return handleStateful(Data(0), Data::copy) {
    block(FreshScope({
      "x${++get().i}"
    }, token))
  }
}

@BindDsl
fun interface Bind<in R> {
  suspend fun MultishotScope<R>.bind(stmt: Stmt): Expr
}

context(bind: Bind<R>)
suspend fun <R> MultishotScope<R>.bind(stmt: Stmt): Expr = with(bind) { bind(stmt) }

context(_: Bind<R>, _: Fresh<R>)
suspend fun <R> MultishotScope<R>.toStmt(tree: Tree): Stmt = when (tree) {
  is Lit -> CRet(CLit(tree.value))
  is Var -> CRet(CVar(tree.name))
  // Here we use bind since other than App, CApp requires an expression
  is App -> CApp(tree.name, bind(toStmt(tree.arg)))
  // here we use the handler `bindHere` to mark positions where bindings
  // should be inserted.
  is Let -> {
    suspend fun <IR: R> BindScope<IR>.f(tree: Tree) = toStmt(tree)
    CLet(tree.name, bindHere { f(tree.binding) }, bindHere { f(tree.body) })
  }
}

@DslMarker annotation class BindDsl
@BindDsl
class BindScope<R>(bind: Bind<R>, token: MultishotToken<R>) : DelegatingMultishotScope<R>(token), Bind<R> by bind

context(_: Fresh<R>)
suspend fun <R> MultishotScope<R>.bindHere(block: suspend BindScope<out R>.() -> Stmt): Stmt = handle {
  suspend fun <IR : R> HandlerPrompt<Stmt, IR, R>.f() = block(BindScope({
    use { resume ->
      val id = fresh()
      CLet(id, it, resume(CVar(id)))
    }
  }, token))
  f()
}

suspend fun MultishotScope<*>.translate(e: Tree): Stmt = freshVars {
  suspend fun <R> FreshScope<R>.f(): Stmt {
    context(_: Fresh<R>)
    suspend fun <IR: R> BindScope<IR>.f() = this.toStmt(e)
    return bindHere { f() }
  }
  f()
}

context(_: Emit<R>)
suspend fun <R> MultishotScope<R>.emit(expr: Expr) = text(
  when (expr) {
    is CLit -> expr.value.toString()
    is CVar -> expr.name
  }
)

context(_: Indent<R>, _: DefaultIndent<R>, _: Flow<R>, _: Emit<R>, _: LayoutChoice<R>)
suspend fun <R> MultishotScope<R>.emit(stmt: Stmt): Unit = when (stmt) {
  is CLet -> {
    text("let"); space(); text(stmt.name); space(); text("=")
    suspend fun <IR: R> FlowScope<IR>.f1() {
      suspend fun <IIR: IR> IndentScope<IIR>.f2() { line(); emit(stmt.binding) }
      nested { f2() }
      line()
      text("in")
    }
    group { f1() }
    suspend fun <IR: R> FlowScope<IR>.f2() {
      suspend fun <IIR: IR> IndentScope<IIR>.f3() { line(); emit(stmt.body) }
      nested { f3() }
    }
    group { f2() }
  }

  is CApp -> {
    text(stmt.name); parens {
      suspend fun <IR: R> FlowScope<IR>.f1() {
        suspend fun <IIR: IR> IndentScope<IIR>.f2() {
          linebreak()
          emit(stmt.arg)
        }
        nested { f2() }
        linebreak()
      }
      group { f1() }
    }
  }

  is CRet -> {
    text("return"); space(); emit(stmt.expr)
  }
}

suspend fun <R> MultishotScope<R>.pretty(s: Stmt): String = pretty(40) {
  suspend fun <IR : R> IDiFELCScope<IR>.f() = emit(s)
  f()
}

suspend fun <R> MultishotScope<R>.pipeline(input: String): String = when (val res = parse(input) {
  suspend fun <IR : R> NondetLexerScope<IR>.f(): Tree = parseExpr()
  f()
}) {
  is Either.Right -> pretty(translate(res.value))
  is Either.Left -> res.value
}