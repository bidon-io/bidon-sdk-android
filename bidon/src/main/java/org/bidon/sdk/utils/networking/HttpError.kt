package org.bidon.sdk.utils.networking

public sealed class HttpError : Throwable() {
    abstract override val cause: Throwable
    public abstract val rawResponse: ByteArray?
    public abstract val code: Int

    public object InternalError : HttpError() {
        override val cause: Throwable = Throwable("internal error")
        override val code: Int = 4
        override val rawResponse: ByteArray? = null
    }

    public object RequestError : HttpError() {
        override val cause: Throwable = Throwable("request error")
        override val code: Int = 4
        override val rawResponse: ByteArray? = null
    }

    public object ServerError : HttpError() {
        override val cause: Throwable = Throwable("server error")
        override val code: Int = 4
        override val rawResponse: ByteArray? = null
    }

    public class UncaughtException(override val cause: Throwable) : HttpError() {
        override val rawResponse: ByteArray? = null
        override val code: Int = -1
    }
}