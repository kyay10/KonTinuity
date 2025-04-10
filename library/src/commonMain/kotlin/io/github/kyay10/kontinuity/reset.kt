package io.github.kyay10.kontinuity

import arrow.core.identity
import arrow.core.nonFatalOrThrow
import kotlinx.coroutines.CancellationException
import kotlin.coroutines.*
import kotlin.coroutines.intrinsics.*
import kotlin.jvm.JvmName

@Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
@DslMarker
public annotation class ResetDsl

public class SubCont<in T, out R> internal constructor(
  private var init: Segment<T, *, R>?,
  private val prompt: Prompt<R>
) {
  public fun clear() {
    init = null
  }

  private fun composedWith(
    k: Continuation<R>, isDelimiting: Boolean, shouldClear: Boolean
  ) = (init!! prependTo collectStack(k).let { if (isDelimiting) it.pushPrompt(prompt) else it }).also {
    if (shouldClear) clear()
  }

  @ResetDsl
  public suspend fun pushSubContWith(
    value: Result<T>,
    isDelimiting: Boolean = false,
    shouldClear: Boolean = false,
  ): R = suspendCoroutineUnintercepted { k ->
    composedWith(k, isDelimiting, shouldClear).resumeWithIntercepted(value)
  }

  @ResetDsl
  public suspend fun pushSubCont(
    isDelimiting: Boolean = false,
    shouldClear: Boolean = false,
    value: suspend () -> T
  ): R = suspendCoroutineUnintercepted { k ->
    value.startCoroutineIntercepted(WrapperCont(composedWith(k, isDelimiting, shouldClear)))
  }

  public fun copy(): SubCont<T, R> = SubCont(init, prompt)
}

@ResetDsl
public suspend fun <R> Prompt<R>.pushPrompt(
  body: suspend () -> R
): R = suspendCoroutineUnintercepted { k ->
  body.startCoroutineHere(WrapperCont(collectStack(k).pushPrompt(this)))
}

context(p: Prompt<R>)
@ResetDsl
@JvmName("pushPromptContext")
public suspend fun <R> pushPrompt(
  body: suspend () -> R
): R = p.pushPrompt(body)

public suspend fun <T, R> Reader<T>.pushReader(value: T, fork: T.() -> T = ::identity, body: suspend () -> R): R =
  suspendCoroutineUnintercepted { k ->
    body.startCoroutineHere(WrapperCont(collectStack(k).pushReader(this, value, fork)))
  }

context(r: Reader<T>)
@JvmName("pushReaderContext")
public suspend fun <T, R> pushReader(value: T, fork: T.() -> T = ::identity, body: suspend () -> R): R =
  r.pushReader(value, fork, body)


@ResetDsl
public suspend fun <R> nonReentrant(
  body: suspend () -> R
): R = runCC(body)

public suspend fun <S> Reader<S>.deleteBinding(): Unit = suspendCoroutineUnintercepted { k ->
  val stack = collectStack(k)
  val toResume: SplitSeq<Unit, *, *> = when (stack) {
    is EmptyCont -> error("Reader not found $this")
    is ReaderCont<*, Unit, *, *> if (this === stack.p) -> stack.rest!!
    is ExpectsSequenceStartingWith<*> -> {
      stack.sequence!!.deleteReader(this, stack)
      stack
    }
  }
  toResume.resumeWithIntercepted(Result.success(Unit))
}

context(r: Reader<S>)
@JvmName("deleteBindingContext")
public suspend fun <S> deleteBinding(): Unit = r.deleteBinding()

private fun <T> SplitSeq<*, *, *>.holeFor(prompt: Prompt<T>, deleteDelimiter: Boolean): SplitSeq<T, *, *> {
  val splitSeq = find(prompt)
  return if (deleteDelimiter) splitSeq else splitSeq.pushPrompt(prompt)
}

@ResetDsl
public suspend fun <T, R> Prompt<R>.takeSubCont(
  deleteDelimiter: Boolean = true, body: suspend (SubCont<T, R>) -> R
): T = suspendCoroutineUnintercepted { k ->
  val stack = collectStack(k)
  val (init, rest) = stack.splitAt(this)
  body.startCoroutineIntercepted(SubCont(init, this), WrapperCont(if (deleteDelimiter) rest else rest.pushPrompt(this)))
}

context(p: Prompt<R>)
@ResetDsl
@JvmName("takeSubContContext")
public suspend fun <T, R> takeSubCont(
  deleteDelimiter: Boolean = true, body: suspend (SubCont<T, R>) -> R
): T = p.takeSubCont(deleteDelimiter, body)

@ResetDsl
public suspend fun <T, R> Prompt<R>.takeSubContOnce(
  deleteDelimiter: Boolean = true, body: suspend (SubCont<T, R>) -> R
): T = suspendCoroutineUnintercepted { k ->
  val stack = collectStack(k)
  val (init, rest) = stack.splitAtOnce(this)
  body.startCoroutineIntercepted(SubCont(init, this), WrapperCont(if (deleteDelimiter) rest else rest.pushPrompt(this)))
}

context(p: Prompt<R>)
@ResetDsl
@JvmName("takeSubContOnceContext")
public suspend fun <T, R> takeSubContOnce(
  deleteDelimiter: Boolean = true, body: suspend (SubCont<T, R>) -> R
): T = p.takeSubContOnce(deleteDelimiter, body)

@ResetDsl
public suspend fun <T, R> Prompt<R>.takeSubContWithFinal(
  deleteDelimiter: Boolean = true, body: suspend (Pair<SubCont<T, R>, SubCont<T, R>>) -> R
): T = suspendCoroutineUnintercepted { k ->
  val stack = collectStack(k)
  val (reusableInit, _) = stack.splitAt(this)
  val (init, rest) = stack.splitAtOnce(this)
  body.startCoroutineIntercepted(
    SubCont(reusableInit, this) to SubCont(init, this),
    WrapperCont(if (deleteDelimiter) rest else rest.pushPrompt(this))
  )
}

context(p: Prompt<R>)
@ResetDsl
@JvmName("takeSubContWithFinalContext")
public suspend fun <T, R> takeSubContWithFinal(
  deleteDelimiter: Boolean = true, body: suspend (Pair<SubCont<T, R>, SubCont<T, R>>) -> R
): T = p.takeSubContWithFinal(deleteDelimiter, body)

// Acts like shift0/control { it(body()) }
// TODO if `body` is multishot, we need to somehow
//  evolve k to be multishot too. This is a more general issue with
//  the `once` functionality. Maybe look to Scheme for ideas?
@ResetDsl
public suspend fun <T, P> Prompt<P>.inHandlingContext(
  deleteDelimiter: Boolean = true, body: suspend () -> T
): T = takeSubContOnce(deleteDelimiter) { k ->
  val res = runCatching { body() }
  // TODO test abortWith here
  res.exceptionOrNull()?.nonFatalOrThrow()
  k.pushSubContWith(res, isDelimiting = deleteDelimiter)
}

context(p: Prompt<P>)
@ResetDsl
@JvmName("inHandlingContextContext")
public suspend fun <T, P> inHandlingContext(
  deleteDelimiter: Boolean = true, body: suspend () -> T
): T = p.inHandlingContext(deleteDelimiter, body)

@Suppress("UNCHECKED_CAST")
internal fun <R> Prompt<R>.abortWith(deleteDelimiter: Boolean, value: Result<R>): Nothing =
  throw AbortWithValueException(this as Prompt<Any?>, value, deleteDelimiter)

@Suppress("UNCHECKED_CAST")
internal suspend fun <R> Prompt<R>.abortWithFast(deleteDelimiter: Boolean, value: Result<R>): Nothing =
  suspendCoroutineUnintercepted { k ->
    collectStack(k).holeFor(this, deleteDelimiter).resumeWithIntercepted(value)
  }

private class AbortWithValueException(
  private val prompt: Prompt<Any?>, private val value: Result<Any?>, private val deleteDelimiter: Boolean
) : SeekingStackException() {
  override fun use(stack: SplitSeq<*, *, *>) =
    stack.holeFor(prompt, deleteDelimiter).resumeWithIntercepted(value)
}

@Suppress("UNCHECKED_CAST")
internal fun <R> Prompt<R>.abortS(deleteDelimiter: Boolean = false, value: suspend () -> R): Nothing =
  throw AbortWithProducerException(this as Prompt<Any?>, value, deleteDelimiter)

private class AbortWithProducerException(
  private val prompt: Prompt<Any?>, private val value: suspend () -> Any?, private val deleteDelimiter: Boolean
) : SeekingStackException() {
  override fun use(stack: SplitSeq<*, *, *>) =
    value.startCoroutineIntercepted(WrapperCont(stack.holeFor(prompt, deleteDelimiter)))
}

public class Prompt<R>
public class Reader<S>

@Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
internal expect abstract class SeekingStackException() : CancellationException {
  abstract fun use(stack: SplitSeq<*, *, *>)
}

public suspend fun <R> runCC(body: suspend () -> R): R = suspendCoroutine {
  body.startCoroutine(WrapperCont(EmptyCont(Continuation(it.context.withTrampoline(), it::resumeWith))))
}

private suspend inline fun <T> suspendCoroutineUnintercepted(
  crossinline block: (Continuation<T>) -> Unit
): T = suspendCoroutineUninterceptedOrReturn {
  block(it)
  COROUTINE_SUSPENDED
}