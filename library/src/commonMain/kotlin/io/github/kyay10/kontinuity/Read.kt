package io.github.kyay10.kontinuity

public fun interface Read<out T> {
  public suspend fun read(): T
}

public typealias Input = Read<Char>

context(read: Read<T>)
public suspend fun <T> read(): T = read.read()

context(_: Exc, _: Read<T>)
public suspend inline fun <T> accept(p: (T) -> Boolean): T = read().also { ensure(p(it)) }
