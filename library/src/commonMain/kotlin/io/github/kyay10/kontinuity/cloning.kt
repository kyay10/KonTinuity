package io.github.kyay10.kontinuity

import io.github.kyay10.kontinuity.Frames.Copied.Companion.resumeWithImpl
import io.github.kyay10.kontinuity.Segmentable.Segment
import io.github.kyay10.kontinuity.Segmentable.Segment.Companion.invalidateAndCollectValues
import kotlin.contracts.ExperimentalExtendedContracts
import kotlin.contracts.contract
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.jvm.JvmField
import kotlin.jvm.JvmInline

internal const val REENTRANT_NOT_SUPPORTED = "Reentrant resumptions are not supported"
internal const val COPYING_NOT_SUPPORTED: String = "Copying continuations is not supported"

@PublishedApi
internal const val SEGMENTABLE_UNLINKED: String = "Segmentable used before being linked into Frames"

private const val RUN_INVALIDATIONS = true
private const val SMALL_DATA_BUFFER_SIZE = 6

internal expect val SUPPORTS_MULTISHOT: Boolean

public expect class StackTraceElement
public expect interface CoroutineStackFrame {
  public val callerFrame: CoroutineStackFrame?
  public fun getStackTraceElement(): StackTraceElement?
}

internal expect val Continuation<*>.completion: Continuation<*>?

internal expect fun <T> Continuation<T>.invokeCopied(
  completion: Continuation<*>,
  context: CoroutineContext,
  result: Result<T>
): Any?

public sealed class SplitSeq<in Start>(@JvmField @PublishedApi internal val realContext: CoroutineContext) :
  Continuation<Start>, CoroutineStackFrame {
  final override fun resumeWith(result: Result<Start>) {
    if (result.exceptionOrNull() !== SuspendedException) realContext.onErrorResume { resume(result) }
  }

  protected abstract fun resume(result: Result<Start>)
}

public sealed interface SplitContOrSegment

public val SplitContOrSegment.isSegment: Boolean
  get() {
    contract {
      returns(true) implies (this@isSegment is Segment<*, *>)
      returns(false) implies (this@isSegment is SplitCont<*>)
    }
    return this is Segment<*, *>
  }

@OptIn(ExperimentalExtendedContracts::class)
public inline fun SplitContOrSegment.ensureNotSegment(message: () -> String): SplitCont<*> {
  contract {
    returns() implies (this@ensureNotSegment is SplitCont<*>)
    (this@ensureNotSegment is Segment<*, *>) holdsIn message
  }
  check(!isSegment, message)
  return this
}

public sealed class SplitCont<in Start>(realContext: CoroutineContext) :
  CoroutineContext by realContext, SplitSeq<Start>(realContext), SplitContOrSegment {
  final override val context: CoroutineContext get() = this
}

@PublishedApi
internal fun <Start> SplitCont<Start>.ensureSegmentable(): Segmentable<Start> =
  this as? Segmentable<Start> ?: error(REENTRANT_NOT_SUPPORTED)

@PublishedApi
internal inline fun <Start, P, R> Frames<Start>.splitAt(
  p: Prompt<P>,
  thisRest: SplitCont<*>,
  block: (Frames<P>, SplitCont<*>, Segment<Start, P>) -> R
): R = block(
  p.rest,
  p.restRest.ensureNotSegment { "$p is not on the stack" },
  Segment(p, this, thisRest.ensureSegmentable())
)

internal class EmptyCont<Start>(@JvmField val underlying: Continuation<Start>, context: CoroutineContext) :
  SplitCont<Start>(context) {
  override val callerFrame: CoroutineStackFrame? = underlying as? CoroutineStackFrame
  override fun getStackTraceElement(): StackTraceElement? = null

  override fun resume(result: Result<Start>) = underlying.resumeWith(result)
}

@PublishedApi
@JvmInline
internal value class Frames<in Start>(val frames: Continuation<Start>) {
  fun copy(rest: Segmentable<*>) = Frames(Copied(frames, rest))

  fun resumeWith(result: Result<Start>) = resumeWithImpl(result)

  @PublishedApi
  internal class Under<RealStart, Start>(
    private val captured: Segment<RealStart, Start>,
    private val frames: Frames<Start>,
    private val rest: SplitCont<*>,
  ) : SplitSeq<RealStart>(rest.realContext) {
    override val context: CoroutineContext get() = rest

    override val callerFrame: CoroutineStackFrame? get() = frames.frames as? CoroutineStackFrame
    override fun getStackTraceElement(): StackTraceElement? = null

    override fun resume(result: Result<RealStart>) = underflow().resumeWith(result)

    fun underflow() = captured.prependTo(false, frames, rest)
    fun underflowCopied(newRest: SplitCont<*>) = captured.prependTo(true, frames, newRest)
  }

  private class Copied<in Start>(
    cont: Continuation<Start>,
    @JvmField val rest: Segmentable<*>,
  ) : SplitSeq<Start>(rest.realContext) {
    private var cont: Continuation<*> = if (cont is Copied) cont.cont else cont
    override val context: CoroutineContext get() = rest

    override val callerFrame: CoroutineStackFrame? get() = cont as? CoroutineStackFrame
    override fun getStackTraceElement(): StackTraceElement? = null

    override fun resume(result: Result<Start>) = Frames(this).resumeWithImpl(result)

    companion object {
      private inline fun <T> Continuation<T>.invokeCopied(
        completion: Continuation<*>,
        context: CoroutineContext,
        result: Result<T>,
        onSuspend: () -> Nothing
      ) = runCatching({ invokeCopied(completion, context, result) }, onSuspend)

      @Suppress("UNCHECKED_CAST")
      tailrec fun <Start> Frames<Start>.resumeWithImpl(result: Result<Start>) {
        val frames = frames
        // This loop unrolls recursion in current.resumeWith(param) to make saner and shorter stack traces on resume
        var current = frames as Continuation<Any?>
        var param: Result<Any?> = result
        if (frames is Copied) {
          val framesRest = frames.rest as Segmentable<Any?>
          current = frames.cont as Continuation<Any?>
          while (true) {
            if (current is Under<Any?, *>)
              return current.underflowCopied(framesRest).resumeWithImpl(param)
            var completion =
              current.completion as? Continuation<Any?> ?: error("Not a compiler generated continuation $current")
            when (completion) {
              is Copied -> completion = completion.cont as Continuation<Any?>
              is EmptyCont -> error("EmptyCont found as parent for copied frames")
              is Segmentable -> {
                // top-level completion reached -- invoke and return
                val outcome = current.invokeCopied(framesRest, framesRest, param) { return }
                return framesRest.underflow().resumeWithImpl(outcome)
              }

              else -> {}
            }
            // Optimized by only setting it upon suspension.
            // This is safe only if no one accesses cont in between
            // That seems to be the case due to trampolining.
            // Note to self: if any weird behavior happens, uncomment this line
            //frames.cont = completion
            val outcome = current.invokeCopied(frames, framesRest, param) {
              frames.cont = completion
              return
            }
            //releaseIntercepted() // this state machine instance is terminating
            // unrolling recursion via loop
            current = completion
            param = outcome
          }
        } else return current.resumeWith(param)
      }
    }
  }
}

context(list: MutableList<in T>)
private fun <T> add(element: T) = list.add(element)

public class Prompt<Start> @PublishedApi internal constructor(
  context: CoroutineContext,
  rest: Continuation<Start>,
  restRest: SplitCont<*>
) : Segmentable<Start>(context, rest, restRest)

public typealias Reader<S> = ReaderT<*, out S>

public class ReaderT<Start, S> @PublishedApi internal constructor(
  context: CoroutineContext,
  private val fork: S.() -> S,
  @JvmField internal var state: S,
  rest: Continuation<Start>,
  restRest: SplitCont<*>,
) : Segmentable<Start>(context, rest, restRest) {
  private class ForkOnFirstRead(state: Any?) {
    @JvmField
    val state: Any? = if (state is ForkOnFirstRead) state.state else state
  }

  @Suppress("UNCHECKED_CAST")
  public val value: S
    get() {
      var state = state
      if (state is ForkOnFirstRead) {
        state = fork(state.state as S)
        this.state = state
      }
      return state
    }

  context(_: MutableList<in Any?>)
  override fun invalidateSingle() {
    super.invalidateSingle()
    add(state)
  }

  @Suppress("UNCHECKED_CAST")
  context(arr: Array<out Any?>)
  override fun revalidateSingle(isFinal: Boolean, index: Int): Int {
    val index = super.revalidateSingle(isFinal, index)
    val state = arr[index] as S
    this@ReaderT.state = if (isFinal) state else ForkOnFirstRead(state) as S
    return index + 1
  }
}

private val SEGMENT_USED = arrayOfNulls<Any?>(0)

@PublishedApi
internal inline val <Start> Segmentable<Start>.rest: Frames<Start>
  get() = Frames(_rest ?: error(SEGMENTABLE_UNLINKED))

@PublishedApi
internal inline val Segmentable<*>.restRest: SplitContOrSegment
  get() = _restRest ?: error(SEGMENTABLE_UNLINKED)

public sealed class Segmentable<Start>(
  context: CoroutineContext,
  @PublishedApi @JvmField internal var _rest: Continuation<Start>?,
  @PublishedApi @JvmField internal var _restRest: SplitContOrSegment?,
) : SplitCont<Start>(context) {
  final override val callerFrame: CoroutineStackFrame? get() = _rest as? CoroutineStackFrame
  final override fun getStackTraceElement(): StackTraceElement? = null

  @PublishedApi
  internal fun underflow(): Frames<Start> = rest.also {
    _rest = null
    _restRest = null
  }

  context(_: MutableList<in Any?>)
  protected open fun invalidateSingle() {
    add(rest.frames)
    add(restRest)
  }

  // returns new index
  @Suppress("UNCHECKED_CAST")
  context(arr: Array<out Any?>)
  protected open fun revalidateSingle(isFinal: Boolean, index: Int): Int {
    val frames = Frames(arr[index] as Continuation<Start>)
    val rest = arr[index + 1] as SplitCont<*>
    if (_restRest !== rest) {
      if (RUN_INVALIDATIONS) invalidateAndCollectValues()
      _restRest = rest
    }
    _rest = (if (isFinal) frames else frames.copy(rest.ensureSegmentable())).frames
    return index + 2
  }

  final override fun resume(result: Result<Start>) {
    underflow().resumeWith(result)
  }

  @PublishedApi
  internal class Segment<Start, End>(
    private val delimiter: Prompt<End>,
    private val start: Frames<Start>,
    private val startRest: Segmentable<*>,
    private var values: Array<Any?>? = null,
  ) : SplitContOrSegment {
    init {
      delimiter._rest = null
      delimiter._restRest = this
    }

    @Suppress("UNCHECKED_CAST")
    fun prependTo(shouldCopy: Boolean, stack: Frames<End>, stackRest: SplitCont<*>): Frames<Start> {
      if (values === SEGMENT_USED) error(
        if (SUPPORTS_MULTISHOT) "Segment was already used once, but is being reused again"
        else COPYING_NOT_SUPPORTED
      )
      val shouldCopy = shouldCopy && SUPPORTS_MULTISHOT

      return (if (shouldCopy) start.copy(startRest.ensureSegmentable()) else start).also {
        if (shouldCopy && values == null) collectValues()
        values?.run { startRest.revalidate(!shouldCopy, delimiter) }
        if (!shouldCopy) values = SEGMENT_USED
        delimiter._rest = stack.frames
        if (RUN_INVALIDATIONS && delimiter._restRest !== this) delimiter.invalidateAndCollectValues()
        delimiter._restRest = stackRest
      }
    }

    context(values: Array<Any?>)
    private tailrec fun Segmentable<*>.revalidate(isFinal: Boolean, delimiter: Prompt<*>, index: Int = 0) {
      if (this === delimiter) return
      val newIndex = revalidateSingle(isFinal, index)
      restRest.ensureNotSegment { "${this@Segment} was likely modified concurrently" }.ensureSegmentable()
        .revalidate(isFinal, delimiter, newIndex)
    }

    private fun collectValues() {
      if (values != null)
        error("values have already been collected, but $this was invalidated anyway")
      if (!SUPPORTS_MULTISHOT) return
      values = ArrayList<Any?>(SMALL_DATA_BUFFER_SIZE).apply {
        startRest.invalidate()
      }.toTypedArray()
    }

    companion object {
      tailrec fun Segmentable<*>.invalidateAndCollectValues() {
        val restRest = _restRest ?: return
        return if (restRest is Segment<*, *>) restRest.collectValues()
        else restRest.ensureNotSegment { REENTRANT_NOT_SUPPORTED }.ensureSegmentable().invalidateAndCollectValues()
      }
    }
  }

  internal companion object {
    fun Segmentable<*>.invalidate(): Array<Any?> {
      val startAt: Segmentable<*>
      val startAtSize: Int
      val buffer = arrayOfNulls<Any?>(SMALL_DATA_BUFFER_SIZE).apply {
        var current: Segmentable<*> = this@invalidate
        var size = 0
        while (true) {
          val restRest = current.restRest
          if (restRest.isSegment) return this
          if (SMALL_DATA_BUFFER_SIZE < size + 3) break
          this[size++] = current.rest.frames
          this[size++] = restRest
          if (current is Reader<*>) this[size++] = current.state
          current = restRest.ensureSegmentable()
        }
        startAt = current
        startAtSize = size
      }
      val size = run {
        var current = startAt
        var size = startAtSize
        do {
          val restRest = current.restRest
          if (restRest.isSegment) break
          size += when (current) {
            is Prompt<*> -> 2
            is Reader<*> -> 3
          }
          current = restRest.ensureSegmentable()
        } while (true)
        size
      }
      return arrayOfNulls<Any?>(size).apply {
        buffer.copyInto(this, 0, 0, startAtSize)
        var index = startAtSize
        var current = startAt
        while (index < size) {
          val restRest = current.restRest
          restRest.ensureNotSegment { "$current was likely modified concurrently" }
          this[index++] = current.rest.frames
          this[index++] = restRest
          if (current is Reader<*>) this[index++] = current.state
          current = restRest.ensureSegmentable()
        }
      }
    }
  }
}

@PublishedApi
internal inline fun <T, R> collectStack(continuation: Continuation<T>, block: (Frames<T>, SplitCont<*>) -> R): R =
  block(Frames(continuation), continuation.context as SplitCont<*>)