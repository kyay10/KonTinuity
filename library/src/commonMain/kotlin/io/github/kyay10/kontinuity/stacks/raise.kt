package io.github.kyay10.kontinuity.stacks

import arrow.core.raise.merge as arrowMerge
import io.github.kyay10.regional.Regional

public fun interface Raise<in Error, in local> {
  context(_: Locality<local>)
  public suspend fun raise(e: Error): Nothing
}

context(_: Locality<local>, raise: Raise<Error, local>)
public suspend fun <Error, local> raise(e: Error): Nothing = raise.raise(e)

@Regional
public fun interface MergeFun<out R, local> {
  context(_: Locality<local2>, _: Raise<R, local2>)
  public suspend operator fun <local2 : local> invoke(): R = _impl()
}

context(_: Locality<local>)
public suspend fun <R, local> merge(block: MergeFun<R, local>): R = arrowMerge {
  context(Raise<R, local> { raise(it) }) { block() }
}
