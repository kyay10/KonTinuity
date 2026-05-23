package io.github.kyay10.kontinuity.stacks

public interface Raise<in Error, in local> {
  context(_: Locality<local>)
  public suspend fun raise(e: Error): Nothing
}

context(_: Locality<local>, raise: Raise<Error, local>)
public suspend fun <Error, local> raise(e: Error): Nothing = raise.raise(e)

public interface MergeFun<out R, local> {
  context(_: Locality<local2>, _: Raise<R, local2>)
  public suspend operator fun <local2 : local> invoke(): R
}

context(_: Locality<local>)
public suspend fun <R, local> merge(block: MergeFun<R, local>): R =
  arrow.core.raise.merge {
    object : Raise<R, local> {
        context(_: Locality<local>)
        override suspend fun raise(e: R): Nothing = this@merge.raise(e)
      }
      .run { block() }
  }
