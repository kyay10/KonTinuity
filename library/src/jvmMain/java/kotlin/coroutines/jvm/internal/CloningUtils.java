package kotlin.coroutines.jvm.internal;

import kotlin.Result;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.CoroutineContext;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;

public class CloningUtils extends ContinuationImpl {
    private CloningUtils() {
        super(null);
    }

    @Override
    protected @Nullable Object invokeSuspend(@NotNull Object o) {
        return null;
    }

    public static @Nullable Continuation<?> getParentContinuation(@NotNull Continuation<?> cont) {
        if (cont instanceof BaseContinuationImpl) {
            return ((BaseContinuationImpl) cont).getCompletion();
        }
        return null;
    }

    public static Object createFailure(@NotNull Throwable exception) {
        return new Result.Failure(exception);
    }

    public static @Nullable <T> Object invokeSuspend(@NotNull Continuation<T> cont, Object value) {
        return ((BaseContinuationImpl) cont).invokeSuspend(value);
    }

    public static boolean isContinuationBaseClass(@NotNull Class<?> clazz) {
        return clazz == BaseContinuationImpl.class || clazz == ContinuationImpl.class;
    }

    public static void initialize(@NotNull Continuation<?> cont, @Nullable Continuation<?> completion, @NotNull CoroutineContext context) throws IllegalAccessException {
        completionField.set(cont, completion);
        if (cont instanceof ContinuationImpl) contextField.set(cont, context);
    }

    private static final Field completionField;

    static {
        try {
            completionField = BaseContinuationImpl.class.getDeclaredField("completion");
            completionField.setAccessible(true);
        } catch (NoSuchFieldException e) {
            throw new RuntimeException(e);
        }
    }

    private static final Field contextField;

    static {
        try {
            contextField = ContinuationImpl.class.getDeclaredField("_context");
            contextField.setAccessible(true);
        } catch (NoSuchFieldException e) {
            throw new RuntimeException(e);
        }
    }
}
