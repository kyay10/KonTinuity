package io.github.kyay10.kontinuity

import kotlin.jvm.JvmName

public fun interface Cont<in T, out R> {
  public suspend fun resumeWith(value: Result<T>): R
  public suspend operator fun invoke(value: T): R = resumeWith(Result.success(value))
  public suspend fun resumeWithException(exception: Throwable): R = resumeWith(Result.failure(exception))
}

@ResetDsl
public suspend fun <R> Prompt<R>.reset(body: suspend () -> R): R = pushPrompt(body = body)

context(p: Prompt<R>)
@ResetDsl
@JvmName("resetContext")
public suspend fun <R> reset(body: suspend () -> R): R = p.reset(body)

@ResetDsl
public suspend fun <R> newReset(body: suspend Prompt<R>.() -> R): R = with(Prompt<R>()) { reset { body() } }

public suspend fun <R> topReset(body: suspend Prompt<R>.() -> R): R = runCC { newReset(body) }

@ResetDsl
public suspend inline fun <T, R> Prompt<R>.shift(crossinline block: suspend (Cont<T, R>) -> R): T =
  takeSubCont(deleteDelimiter = false) { sk -> block { sk.pushSubContWith(it, isDelimiting = true) } }

context(p: Prompt<R>)
@ResetDsl
@JvmName("shiftContext")
public suspend inline fun <T, R> shift(crossinline block: suspend (Cont<T, R>) -> R): T = p.shift(block)

@ResetDsl
public suspend inline fun <T, R> Prompt<R>.shiftOnce(crossinline block: suspend (Cont<T, R>) -> R): T =
  takeSubContOnce(deleteDelimiter = false) { sk -> block { sk.pushSubContWith(it, isDelimiting = true) } }

context(p: Prompt<R>)
@ResetDsl
@JvmName("shiftOnceContext")
public suspend inline fun <T, R> shiftOnce(crossinline block: suspend (Cont<T, R>) -> R): T = p.shiftOnce(block)

@ResetDsl
public suspend inline fun <T, R> Prompt<R>.control(crossinline block: suspend (Cont<T, R>) -> R): T =
  takeSubCont(deleteDelimiter = false) { sk -> block { sk.pushSubContWith(it) } }

context(p: Prompt<R>)
@ResetDsl
@JvmName("controlContext")
public suspend inline fun <T, R> control(crossinline block: suspend (Cont<T, R>) -> R): T = p.control(block)

@ResetDsl
public suspend inline fun <T, R> Prompt<R>.shift0(crossinline block: suspend (Cont<T, R>) -> R): T =
  takeSubCont { sk -> block { sk.pushSubContWith(it, isDelimiting = true) } }

context(p: Prompt<R>)
@ResetDsl
@JvmName("shift0Context")
public suspend inline fun <T, R> shift0(crossinline block: suspend (Cont<T, R>) -> R): T = p.shift0(block)

@ResetDsl
public suspend inline fun <T, R> Prompt<R>.control0(crossinline block: suspend (Cont<T, R>) -> R): T =
  takeSubCont { sk -> block { sk.pushSubContWith(it) } }

context(p: Prompt<R>)
@ResetDsl
@JvmName("control0Context")
public suspend inline fun <T, R> control0(crossinline block: suspend (Cont<T, R>) -> R): T = p.control0(block)

@ResetDsl
public fun <R> Prompt<R>.abortWith(value: Result<R>): Nothing = abortWith(deleteDelimiter = false, value)

context(p: Prompt<R>)
@ResetDsl
@JvmName("abortWithContext")
public fun <R> abortWith(value: Result<R>): Nothing = p.abortWith(value)

@ResetDsl
public fun <R> Prompt<R>.abortWith0(value: Result<R>): Nothing = abortWith(deleteDelimiter = true, value)

context(p: Prompt<R>)
@ResetDsl
@JvmName("abortWith0Context")
public fun <R> abortWith0(value: Result<R>): Nothing = p.abortWith0(value)

@ResetDsl
public fun <R> Prompt<R>.abort(value: R): Nothing = abortWith(Result.success(value))

context(p: Prompt<R>)
@ResetDsl
@JvmName("abortContext")
public fun <R> abort(value: R): Nothing = p.abort(value)
@ResetDsl
public fun <R> Prompt<R>.abort0(value: R): Nothing = abortWith0(Result.success(value))

context(p: Prompt<R>)
@ResetDsl
@JvmName("abort0Context")
public fun <R> abort0(value: R): Nothing = p.abort0(value)

@ResetDsl
public fun <R> Prompt<R>.abortS(value: suspend () -> R): Nothing = abortS(deleteDelimiter = false, value)

context(p: Prompt<R>)
@ResetDsl
@JvmName("abortSContext")
public fun <R> abortS(value: suspend () -> R): Nothing = p.abortS(value)

@ResetDsl
public fun <R> Prompt<R>.abortS0(value: suspend () -> R): Nothing = abortS(deleteDelimiter = true, value)

context(p: Prompt<R>)
@ResetDsl
@JvmName("abortS0Context")
public fun <R> abortS0(value: suspend () -> R): Nothing = p.abortS0(value)