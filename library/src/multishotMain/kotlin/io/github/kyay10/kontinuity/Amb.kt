package io.github.kyay10.kontinuity

import kotlin.jvm.JvmName

public fun interface Amb {
  public suspend fun flip(): Boolean
}

context(amb: Amb)
public suspend fun flip(): Boolean = amb.flip()

@get:JvmName("listAmb")
public val <E> Handler<List<E>>.amb: Amb
  get() = Amb { use { resume -> resume(true) + resume(false) } }

public suspend fun <E> ambList(block: suspend context(Amb) () -> E): List<E> = handle { listOf(block(amb)) }
