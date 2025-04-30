package kotlin.coroutines.jvm.internal;

import io.github.kyay10.kontinuity.ResultConverter;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.CoroutineContext;
import kotlin.coroutines.EmptyCoroutineContext;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class CloningUtils extends BaseContinuationImpl implements ResultConverter {
    public CloningUtils() {
        super(null);
    }

    public static @Nullable Continuation<?> getParentContinuation(@NotNull Continuation<?> cont) {
        if (cont instanceof BaseContinuationImpl) {
            return ((BaseContinuationImpl) cont).getCompletion();
        }
        return null;
    }

    public static @Nullable Object magic(@NotNull Continuation<?> cont) {
        return ((BaseContinuationImpl) cont).invokeSuspend(cont);
    }

    @Override
    protected @Nullable Object invokeSuspend(@NotNull Object o) {
        return null;
    }

    @Override
    public @NotNull CoroutineContext getContext() {
        return EmptyCoroutineContext.INSTANCE;
    }

    public @Nullable Object convert(@Nullable Object result) {
        return result;
    }
}
