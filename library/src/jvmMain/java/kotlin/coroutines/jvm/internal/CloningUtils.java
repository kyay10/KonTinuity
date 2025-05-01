package kotlin.coroutines.jvm.internal;

import kotlin.Result;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.CoroutineContext;
import kotlin.coroutines.EmptyCoroutineContext;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class CloningUtils extends BaseContinuationImpl {
    public CloningUtils() {
        super(null);
    }

    public static @Nullable Continuation<?> getParentContinuation(@NotNull Continuation<?> cont) {
        if (cont instanceof BaseContinuationImpl) {
            return ((BaseContinuationImpl) cont).getCompletion();
        }
        return null;
    }

    public static @Nullable <T> Object invokeSuspend(@NotNull Continuation<T> cont, T value) {
        return ((BaseContinuationImpl) cont).invokeSuspend(value);
    }

    public static @Nullable Object invokeSuspendWithException(@NotNull Continuation<?> cont, Throwable exception) {
        return ((BaseContinuationImpl) cont).invokeSuspend(new Result.Failure(exception));
    }

    @Override
    protected @Nullable Object invokeSuspend(@NotNull Object o) {
        return null;
    }

    @Override
    public @NotNull CoroutineContext getContext() {
        return EmptyCoroutineContext.INSTANCE;
    }
}
