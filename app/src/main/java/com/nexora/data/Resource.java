package com.nexora.data;

/** Async state wrapper exposed by repositories: LOADING | SUCCESS | ERROR plus data and message. */
public final class Resource<T> {
    public enum State { LOADING, SUCCESS, ERROR }

    public final State state;
    public final T data;
    public final String message;
    /** True when data came from a local cache (Firestore offline cache or Room) rather than the server. */
    public final boolean fromCache;

    private Resource(State state, T data, String message, boolean fromCache) {
        this.state = state; this.data = data; this.message = message; this.fromCache = fromCache;
    }

    public static <T> Resource<T> loading(T data) { return new Resource<>(State.LOADING, data, null, false); }
    public static <T> Resource<T> success(T data) { return new Resource<>(State.SUCCESS, data, null, false); }
    public static <T> Resource<T> success(T data, boolean fromCache) { return new Resource<>(State.SUCCESS, data, null, fromCache); }
    public static <T> Resource<T> error(String message, T data) { return new Resource<>(State.ERROR, data, message, data != null); }

    public boolean isLoading() { return state == State.LOADING; }
    public boolean isSuccess() { return state == State.SUCCESS; }
    public boolean isError() { return state == State.ERROR; }

    /** One-shot result delivered on the main thread. */
    public interface Callback<T> {
        void onResult(Resource<T> result);
    }
}
