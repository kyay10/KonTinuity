package io.github.kyay10.kontinuity

import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.jvm.JvmField

public expect class StackTraceElement
public expect interface CoroutineStackFrame {
  public val callerFrame: CoroutineStackFrame?
  public fun getStackTraceElement(): StackTraceElement?
}

internal expect val Continuation<*>.completion: Continuation<*>?
internal expect fun <T> Continuation<T>.copy(completion: Continuation<*>): Continuation<T>
internal expect fun <T> Continuation<T>.invokeSuspend(result: Result<T>): Any?

public sealed class SplitSeq<in Start>(@JvmField @PublishedApi internal val trampoline: MultishotScope) : CoroutineStackFrame, Continuation<Start> {
  final override val context: CoroutineContext get() = EmptyCoroutineContext
  final override fun resumeWith(result: Result<Start>) {
    if (result.exceptionOrNull() !== SuspendedException) {
      resumeWithImpl(result)
    }
  }
}

internal tailrec fun <Start> SplitSeq<Start>.resumeWithImpl(result: Result<Start>): Unit = when (this) {
  is EmptyCont -> underlying.resumeWith(result)

  is FramesCont<Start, *> if !copied -> resumeAndCollectResult(result) { seq, res -> return seq.resumeWithImpl(res) }

  is FramesCont<Start, *> -> resumeCopiedAndCollectResult(result) { seq, res -> return seq.resumeWithImpl(res) }

  is Prompt<Start> -> rest.resumeWithImpl(result)
  is ReaderT<*, Start> -> rest.resumeWithImpl(result)
  is UnderCont<*, Start> -> (captured prependTo rest).resumeWithImpl(result)
}

@PublishedApi
internal fun <Start, P> SplitSeq<Start>.splitAt(p: Prompt<P>): Pair<SingleUseSegment<Start, P>, SplitSeq<P>> =
  SingleUseSegment(p, this) to p.rest

internal class EmptyCont<Start>(@JvmField val underlying: Continuation<Start>, trampoline: MultishotScope) : SplitSeq<Start>(trampoline) {
  override val callerFrame: CoroutineStackFrame? = underlying as? CoroutineStackFrame
  override fun getStackTraceElement(): StackTraceElement? = null
}

// frame :: frames ::: rest
internal class FramesCont<Start, Last>(
  @JvmField var cont: Continuation<Start>,
  @Suppress("UNCHECKED_CAST")
  override val rest: SplitSeq<Last>,
  @JvmField var copied: Boolean = false,
) : Segmentable<Start, Last>(rest.trampoline) {

  @Suppress("UNCHECKED_CAST")
  inline fun resumeCopiedAndCollectResult(result: Result<Start>, resumer: (SplitSeq<Last>, Result<Last>) -> Unit) {
    // This loop unrolls recursion in current.resumeWith(param) to make saner and shorter stack traces on resume
    val rest = rest
    val newFramesCont = FramesCont(cont, rest, true)
    var current: Continuation<Any?> = cont as Continuation<Any?>
    var param: Result<Any?> = result
    while (true) {
      val completion = (current.completion ?: error("Not a compiler generated continuation $current")) as Continuation<Any?>
      if (completion === rest) {
        trampoline.rest = completion
        // top-level completion reached -- invoke and return
        val outcome = try {
          val outcome = current.copy(completion).invokeSuspend(param)
          if (outcome === COROUTINE_SUSPENDED) return
          Result.success(outcome)
        } catch (exception: Throwable) {
          if (exception === SuspendedException) return
          Result.failure(exception)
        }
        return resumer(completion, outcome as Result<Last>)
      }
      // Optimized by only setting it upon suspension.
      // This is safe only if no one accesses cont in between
      // That seems to be the case due to trampolining.
      // Note to self: if any weird behavior happens, uncomment this line
      //newFramesCont.cont = completion
      trampoline.rest = newFramesCont
      val outcome: Result<Any?> =
        try {
          val outcome = current.copy(newFramesCont).invokeSuspend(param)
          if (outcome === COROUTINE_SUSPENDED) {
            newFramesCont.cont = completion
            return
          }
          Result.success(outcome)
        } catch (exception: Throwable) {
          if (exception === SuspendedException) {
            newFramesCont.cont = completion
            return
          }
          Result.failure(exception)
        }
      //releaseIntercepted() // this state machine instance is terminating
      // unrolling recursion via loop
      current = completion
      param = outcome
    }
  }

  @Suppress("UNCHECKED_CAST")
  inline fun resumeAndCollectResult(result: Result<Start>, resumer: (SplitSeq<Last>, Result<Last>) -> Unit) {
    // This loop unrolls recursion in current.resumeWith(param) to make saner and shorter stack traces on resume
    val rest = rest
    trampoline.rest = rest
    var current: Continuation<Any?> = cont as Continuation<Any?>
    var param: Result<Any?> = result
    while (true) {
      // Use normal resumption when faced with a non-compiler-generated continuation
      val completion = current.completion ?: return current.resumeWith(param)
      val outcome: Result<Any?> =
        try {
          val outcome = current.invokeSuspend(param)
          if (outcome === COROUTINE_SUSPENDED) return
          Result.success(outcome)
        } catch (exception: Throwable) {
          if (exception === SuspendedException) return
          Result.failure(exception)
        }
      //releaseIntercepted() // this state machine instance is terminating
      if (completion === rest) {
        // top-level completion reached -- invoke and return
        return resumer(completion, outcome as Result<Last>)
      } else {
        // unrolling recursion via loop
        current = completion as Continuation<Any?>
        param = outcome
      }
    }
  }

  override val callerFrame: CoroutineStackFrame? get() = cont as? CoroutineStackFrame
}

public class Prompt<Start> @PublishedApi internal constructor(
  @PublishedApi override var rest: SplitSeq<Start>
) : Segmentable<Start, Start>(rest.trampoline)

public typealias Reader<S> = ReaderT<S, *>

public class ReaderT<S, Start> @PublishedApi internal constructor(
  override val rest: SplitSeq<Start>,
  @PublishedApi @JvmField internal var state: S,
  @PublishedApi @JvmField internal val fork: S.() -> S,
) : Segmentable<Start, Start>(rest.trampoline) {
  @PublishedApi
  @JvmField
  internal var forkOnFirstRead: Boolean = false

  public fun ask(): S {
    if (forkOnFirstRead) {
      forkOnFirstRead = false
      state = fork(state)
    }
    return state
  }
}

context(r: Reader<S>)
public fun <S> ask(): S = r.ask()

@PublishedApi
internal class UnderCont<Start, RealStart>(
  @JvmField val captured: SingleUseSegment<RealStart, Start>, override val rest: SplitSeq<Start>
) : Segmentable<RealStart, Start>(rest.trampoline) {
  var copied: Boolean
    get() = captured.copying
    set(value) {
      captured.copying = value
    }
}

internal infix fun <Start, End> SingleUseSegment<Start, End>.prependTo(stack: SplitSeq<End>): SplitSeq<Start> {
  repushValues()
  delimiter.rest = stack
  return cont
}

// Expects that cont eventually refers to box
@PublishedApi
internal class SingleUseSegment<Start, End>(
  @JvmField val delimiter: Prompt<End>,
  @JvmField val cont: SplitSeq<Start>,
  @JvmField var values: Array<out Any?>? = null,
  @JvmField var copying: Boolean = false
) {
  fun makeReusable(): SingleUseSegment<Start, End> {
    if (values == null) values = ArrayList<Any?>(10).apply {
      cont.collectValues(delimiter, this)
    }.toTypedArray()
    return SingleUseSegment(delimiter, cont, values, true)
  }

  fun makeCopy(): SingleUseSegment<Start, End> {
    if (values == null) values = ArrayList<Any?>(10).apply {
      cont.collectValues(delimiter, this)
    }.toTypedArray()
    return SingleUseSegment(delimiter, cont, null, true)
  }

  fun repushValues() {
    cont.repushValues(delimiter, values ?: return, copying, 0)
  }
}

private tailrec fun <Start, End> SplitSeq<Start>.collectValues(
  delimiter: Prompt<End>,
  values: MutableList<in Any?>
): Unit = when (this) {
  is EmptyCont<*> -> error("Delimiter not found $delimiter in $this")
  is Prompt<*> if this === delimiter -> {}
  is FramesCont<*, *> -> {
    values.add(copied)
    copied = true
    rest.collectValues(delimiter, values)
  }
  is Prompt<*> -> {
    val rest = rest
    values.add(rest)
    rest.collectValues(delimiter, values)
  }
  is ReaderT<*, *> -> {
    values.add(state)
    values.add(forkOnFirstRead)
    forkOnFirstRead = true
    rest.collectValues(delimiter, values)
  }
  is UnderCont<*, *> -> {
    values.add(copied)
    copied = true
    rest.collectValues(delimiter, values)
  }
}

private tailrec fun <Start, End> SplitSeq<Start>.repushValues(
  delimiter: Prompt<End>,
  values: Array<out Any?>,
  copying: Boolean,
  index: Int
): Unit = when (this) {
  is EmptyCont -> error("Delimiter not found $delimiter in $this")
  is Prompt if this === delimiter -> {}
  is FramesCont<Start, *> -> {
    @Suppress("UNCHECKED_CAST")
    val copied = values[index] as Boolean
    this.copied = copied || copying
    rest.repushValues(delimiter, values, copying, index + 1)
  }
  is Prompt -> {
    @Suppress("UNCHECKED_CAST")
    val value = values[index] as SplitSeq<Start>
    this.rest = value
    value.repushValues(delimiter, values, copying, index + 1)
  }
  is ReaderT<*, Start> -> {
    @Suppress("UNCHECKED_CAST")
    this as ReaderT<Any?, Start>
    val value = values[index]
    val forkOnFirstRead = values[index + 1] as Boolean
    this.state = value
    this.forkOnFirstRead = forkOnFirstRead || copying
    rest.repushValues(delimiter, values, copying, index + 2)
  }
  is UnderCont<*, Start> -> {
    val copied = values[index] as Boolean
    this.copied = copied || copying
    rest.repushValues(delimiter, values, copying, index + 1)
  }
}

public sealed class Segmentable<Start, Rest>(trampoline: MultishotScope) : SplitSeq<Start>(trampoline) {
  internal abstract val rest: SplitSeq<Rest>
  override val callerFrame: CoroutineStackFrame? get() = rest
  override fun getStackTraceElement(): StackTraceElement? = null
}

@PublishedApi
internal fun <R> MultishotScope.collectStack(continuation: Continuation<R>): FramesCont<R, *> = FramesCont(continuation, rest!!)