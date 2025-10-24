package io.github.kyay10.kontinuity

import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.jvm.JvmField
import kotlin.jvm.JvmInline

public expect class StackTraceElement
public expect interface CoroutineStackFrame {
  public val callerFrame: CoroutineStackFrame?
  public fun getStackTraceElement(): StackTraceElement?
}

@PublishedApi
internal expect val Continuation<*>.completion: Continuation<*>?

@PublishedApi
internal expect fun <T> Continuation<T>.copy(completion: Continuation<*>): Continuation<T>

@PublishedApi
internal expect fun <T> Continuation<T>.invokeSuspend(result: Result<T>): Any?

public sealed class SplitSeq<in Start, out Region>(trampoline: Trampoline) : MultishotScope<Region>(trampoline),
  Continuation<Start>,
  CoroutineStackFrame {
  final override val context: CoroutineContext get() = EmptyCoroutineContext

  final override fun resumeWith(result: Result<Start>) {
    if (result.exceptionOrNull() !== SuspendedException) {
      resumeWithImpl(result)
    }
  }
}

public sealed class SplitCont<in Start, out Region>(trampoline: Trampoline) : SplitSeq<Start, Region>(trampoline)

internal sealed interface HasCopied {
  var copied: Boolean
}

internal tailrec fun <Start> SplitSeq<Start, *>.resumeWithImpl(result: Result<Start>): Unit = when (this) {
  is EmptyCont -> underlying.resumeWith(result)

  is FramesCont<Start, *, *> if !copied -> resumeAndCollectResult(result) { seq, res -> return seq.resumeWithImpl(res) }

  is FramesCont<Start, *, *> -> resumeCopiedAndCollectResult(result) { seq, res -> return seq.resumeWithImpl(res) }

  is PromptCont<Start, *, *> -> rest.resumeWithImpl(result)
  is ReaderCont<*, Start, *, *> -> rest.resumeWithImpl(result)
  is UnderCont<*, Start, *> -> (captured prependTo rest).resumeWithImpl(result)
}

@PublishedApi
internal fun <Start, P, OR> FramesCont<Start, *, *>.splitAt(p: PromptCont<P, *, OR>): Pair<SingleUseSegment<Start, P, OR>, FramesCont<in P, *, OR>> =
  SingleUseSegment(p, this) to p.rest

internal class EmptyCont<Start>(@JvmField val underlying: Continuation<Start>, trampoline: Trampoline) :
  SplitCont<Start, Any?>(trampoline) {
  override val callerFrame: CoroutineStackFrame? = underlying as? CoroutineStackFrame
  override fun getStackTraceElement(): StackTraceElement? = null
}

// frame :: frames ::: rest
@PublishedApi
internal class FramesCont<Start, Last, out Region>(
  @JvmField var cont: Continuation<Start>,
  @Suppress("UNCHECKED_CAST")
  @JvmField val rest: SplitCont<Last, Region>,
  override var copied: Boolean = false,
) : SplitSeq<Start, Region>(rest.trampoline), HasCopied {

  @Suppress("UNCHECKED_CAST")
  inline fun resumeCopiedAndCollectResult(result: Result<Start>, resumer: (SplitSeq<Last, *>, Result<Last>) -> Unit) {
    // This loop unrolls recursion in current.resumeWith(param) to make saner and shorter stack traces on resume
    val newFramesCont = FramesCont(cont, rest, true)
    var current: Continuation<Any?> = cont as Continuation<Any?>
    var param: Result<Any?> = result
    while (true) {
      val completion =
        (current.completion ?: error("Not a compiler generated continuation $current")) as Continuation<Any?>
      if (completion is SplitSeq<Any?, *>) {
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
  inline fun resumeAndCollectResult(result: Result<Start>, resumer: (SplitSeq<Last, *>, Result<Last>) -> Unit) {
    // This loop unrolls recursion in current.resumeWith(param) to make saner and shorter stack traces on resume
    var current: Continuation<Any?> = cont as Continuation<Any?>
    var param: Result<Any?> = result
    while (true) {
      // Use normal resumption when faced with a non-compiler-generated continuation
      val completion = current.completion ?: return current.resumeWith(param)
      completion as Continuation<Any?>
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
      if (completion is SplitSeq<Any?, *>) {
        // top-level completion reached -- invoke and return
        return resumer(completion, outcome as Result<Last>)
      } else {
        // unrolling recursion via loop
        current = completion
        param = outcome
      }
    }
  }

  override val callerFrame: CoroutineStackFrame? get() = cont as? CoroutineStackFrame
  override fun getStackTraceElement(): StackTraceElement? = null
}

@JvmInline
public value class Prompt<Start, in IR, OR> @PublishedApi internal constructor(
  @PublishedApi internal val underlying: PromptCont<Start, *, OR>
)

public class PromptCont<Start, IR : OR, OR> @PublishedApi internal constructor(
  @PublishedApi override var rest: FramesCont<in Start, *, OR>
) : Segmentable<Start, Start, IR, OR>(rest.trampoline)

@JvmInline
public value class Reader<out S> @PublishedApi internal constructor(
  @PublishedApi internal val underlying: ReaderCont<out S, *, *, *>
) {
  public fun ask(): S = underlying.ask()
}

public class ReaderCont<S, Start, IR : OR, OR> @PublishedApi internal constructor(
  override val rest: FramesCont<in Start, *, OR>,
  @PublishedApi @JvmField internal var state: S,
  @PublishedApi @JvmField internal val fork: S.() -> S,
) : Segmentable<Start, Start, IR, OR>(rest.trampoline) {
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

@PublishedApi
internal class UnderCont<Start, RealStart, Region>(
  @JvmField val captured: SingleUseSegment<RealStart, Start, Region>, override val rest: FramesCont<in Start, *, Region>
) : Segmentable<RealStart, Start, Region, Region>(rest.trampoline), HasCopied {
  override var copied: Boolean
    get() = captured.copying
    set(value) {
      captured.copying = value
    }
}

internal infix fun <Start, End, Region> SingleUseSegment<Start, End, Region>.prependTo(stack: FramesCont<in End, *, Region>): SplitSeq<Start, *> {
  repushValues()
  delimiter.rest = stack
  return cont
}

// Expects that cont eventually refers to box
@PublishedApi
internal class SingleUseSegment<Start, End, Region>(
  @JvmField val delimiter: PromptCont<End, *, Region>,
  @JvmField val cont: FramesCont<Start, *, *>,
  @JvmField var values: Array<out Any?>? = null,
  @JvmField var owned: List<HasCopied>? = null,
  @JvmField var copying: Boolean = false
) {
  fun makeReusable(): SingleUseSegment<Start, End, Region> {
    prepareValues()
    return SingleUseSegment(delimiter, cont, values, null, true)
  }

  fun makeCopy(): SingleUseSegment<Start, End, Region> {
    prepareValues()
    return SingleUseSegment(delimiter, cont, null, null, true)
  }

  private fun prepareValues() {
    if (values == null) {
      cont.copied = true
      val owned = mutableListOf<HasCopied>()
      values = cont.rest.collectValues(delimiter, owned)
      this.owned = owned
    }
  }

  private fun prepareValues2() {
    if (values == null) values = ArrayList<Any?>(10).apply {
      cont.copied = true
      val owned = mutableListOf<HasCopied>()
      cont.rest.collectValues(delimiter, this, owned)
      this@SingleUseSegment.owned = owned
    }.toTypedArray()
  }

  fun repushValues() {
    val values = values ?: return
    val copying = copying
    cont.copied = copying
    if (!copying) owned!!.forEach { it.copied = false }
    cont.rest.repushValues(delimiter, values, copying, 0)
  }
}

private tailrec fun <Start, End> SplitSeq<Start, *>.collectValues(
  delimiter: PromptCont<End, *, *>,
  values: MutableList<in Any?>,
  owned: MutableList<in HasCopied>,
): Unit = when (this) {
  is EmptyCont<Start> -> error("Delimiter not found $delimiter in $this")
  is PromptCont<*, *, *> if this === delimiter -> {}
  is FramesCont<*, *, *> -> {
    if (!copied) owned.add(this)
    copied = true
    rest.collectValues(delimiter, values, owned)
  }

  is PromptCont<*, *, *> -> {
    val rest = rest
    values.add(rest)
    rest.collectValues(delimiter, values, owned)
  }

  is ReaderCont<*, *, *, *> -> {
    values.add(state)
    values.add(forkOnFirstRead)
    forkOnFirstRead = true
    rest.collectValues(delimiter, values, owned)
  }

  is UnderCont<*, *, *> -> {
    if (!copied) owned.add(this)
    copied = true
    rest.collectValues(delimiter, values, owned)
  }
}

private tailrec fun <Start, End> SplitSeq<Start, *>.collectValues(
  delimiter: PromptCont<End, *, *>,
  owned: MutableList<in HasCopied>,
  index: Int = 0,
): Array<Any?> = when (this) {
  is EmptyCont<*> -> error("Delimiter not found $delimiter in $this")
  is PromptCont<*, *, *> if this === delimiter -> arrayOfNulls(index)
  is PromptCont<*, *, *> -> {
    val rest = rest
    val arr = rest.collectValues(delimiter, owned, index + 1)
    arr[index] = rest
    arr
  }

  is ReaderCont<*, *, *, *> -> {
    val arr = rest.collectValues(delimiter, owned, index + 2)
    arr[index] = state
    arr[index + 1] = forkOnFirstRead
    forkOnFirstRead = true
    arr
  }

  is FramesCont<*, *, *> -> {
    if (!copied) owned.add(this)
    copied = true
    rest.collectValues(delimiter, owned, index)
  }

  is UnderCont<*, *, *> -> {
    if (!copied) owned.add(this)
    copied = true
    rest.collectValues(delimiter, owned, index)
  }
}

private tailrec fun <Start, End> SplitSeq<Start, *>.repushValues(
  delimiter: PromptCont<End, *, *>,
  values: Array<out Any?>,
  copying: Boolean,
  index: Int
): Unit = when (this) {
  is EmptyCont -> error("Delimiter not found $delimiter in $this")
  is PromptCont<Start, *, *> if this === delimiter -> {}
  is PromptCont<Start, *, *> -> {
    @Suppress("UNCHECKED_CAST")
    val rest = values[index] as FramesCont<Start, *, Nothing>
    this.rest = rest
    rest.repushValues(delimiter, values, copying, index + 1)
  }

  is ReaderCont<*, Start, *, *> -> {
    @Suppress("UNCHECKED_CAST")
    this as ReaderCont<Any?, Start, *, *>
    state = values[index]
    forkOnFirstRead = copying || (values[index + 1] as Boolean)
    rest.repushValues(delimiter, values, copying, index + 2)
  }

  is FramesCont<Start, *, *> -> rest.repushValues(delimiter, values, copying, index)
  is UnderCont<*, Start, *> -> rest.repushValues(delimiter, values, copying, index)
}

public sealed class Segmentable<Start, in Rest, out IR : OR, out OR>(trampoline: Trampoline) :
  SplitCont<Start, IR>(trampoline) {
  internal abstract val rest: FramesCont<in Rest, *, OR>
  final override val callerFrame: CoroutineStackFrame get() = rest
  final override fun getStackTraceElement(): StackTraceElement? = null
}

@PublishedApi
internal fun <R, Region> MultishotScope<Region>.collectStack(continuation: Continuation<R>): FramesCont<R, *, Region> =
  FramesCont(
    continuation, when (this) {
      is SplitCont<*, Region> -> this
      is FramesCont<*, *, Region> -> rest
    }
  )