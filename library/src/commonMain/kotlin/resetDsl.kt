public typealias Cont<T, R> = suspend (T) -> R

@ResetDsl
public suspend fun <R> Prompt<R>.reset(body: suspend Prompt<R>.() -> R): R = pushPrompt { body() }

public suspend fun <R> topReset(body: suspend Prompt<R>.() -> R): R =
  multishotBoundary { Prompt<R>().reset(body) }

@ResetDsl
public suspend inline fun <T, R> Prompt<R>.shift(block: (Cont<T, R>) -> R): T =
  takeSubCont(deleteDelimiter = false) { sk -> block { sk.pushDelimSubCont { it } } }

@ResetDsl
public suspend inline fun <T, R> Prompt<R>.shiftStackSafe(crossinline block: suspend (Cont<T, R>) -> R): T =
  takeSubContStackSafe(deleteDelimiter = false) { sk -> block { sk.pushDelimSubCont { it } } }

@ResetDsl
public suspend inline fun <T, R> Prompt<R>.control(block: (Cont<T, R>) -> R): T =
  takeSubCont(deleteDelimiter = false) { sk -> block { sk.pushSubCont { it } } }

@ResetDsl
public suspend inline fun <T, R> Prompt<R>.shift0(block: (Cont<T, R>) -> R): T =
  takeSubCont { sk -> block { sk.pushDelimSubCont { it } } }

@ResetDsl
public suspend inline fun <T, R> Prompt<R>.control0(block: (Cont<T, R>) -> R): T =
  takeSubCont { sk -> block { sk.pushSubCont { it } } }