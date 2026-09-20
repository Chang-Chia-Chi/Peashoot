package dev.peashoot.proxy

import dev.peashoot.core.Frame
import java.io.IOException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * One response's frames, readable from the first by a reader that arrives late, while the exchange
 * that owns the response is still appending to it. This is the hand-over resume rests on (#26): a
 * re-issue arrives tens of milliseconds after the drop, so joining an answer in flight is the
 * normal case and not the edge.
 *
 * Why no frame is lost or sent twice. A frame is in the list before any size that counts it is
 * published, and the list only grows, so a reader taking the frames from its own cursor up to a
 * published size takes frames that exist, each once and in order, and then moves its cursor to
 * exactly that size. The size is a `StateFlow`, which conflates but never loses its latest value,
 * and the end is published after the last append, so every reader reaches the final size and then
 * sees the end.
 *
 * What cancels what: nothing here. A reader collects a `StateFlow` and copies out of the list under
 * the lock, then emits outside it, so a slow or departed reader never holds up [append], and a
 * reader that is cancelled ends its own collection and nothing else. The owner never waits for a
 * reader.
 *
 * It is #27's to reuse from this side of the module boundary: serving the Responses surface's
 * `starting_after=N` from the proxy's own buffer is this same read with a filter on it.
 */
internal class FrameLog {
    /** How far the response has got; [failure] only ever comes with [ended]. */
    private data class Progress(
        val size: Int,
        val ended: Boolean = false,
        val failure: Throwable? = null,
    )

    /**
     * The list and the size published for it move together, or a reader could be told of a frame a
     * moment before it is there.
     */
    private val lock = Mutex()
    private val frames = ArrayList<Frame>()
    private val progress = MutableStateFlow(Progress(0))

    /** The response ended short: its upstream failed, or the proxy stopped under it. */
    val failed: Boolean
        get() = progress.value.failure != null

    /** No more frames are coming, however it ended. Asked without blocking, from anywhere. */
    val done: Boolean
        get() = progress.value.ended

    /** Only the owning exchange's drive appends, and never after [end]. */
    suspend fun append(frame: Frame) = lock.withLock {
        frames += frame
        progress.value = Progress(frames.size)
    }

    /**
     * The response is over, whole when [failure] is null. The first call wins, so the owner can
     * call it from its flow's completion and again from the exchange's, which is the only one to
     * run when the drive never started. Never cancelled: it runs where a stream ended, possibly
     * because the proxy is stopping, and a reader left waiting on an end that never came would wait
     * for good.
     */
    suspend fun end(failure: Throwable?) =
        withContext(NonCancellable) {
            lock.withLock {
                if (!progress.value.ended) progress.value = Progress(frames.size, true, failure)
            }
        }

    /**
     * Every frame from the first, then each later one as it is appended, then the end.
     *
     * A reader ends the way the response ended, which is usually not with a failure. An upstream
     * cut short mostly does not raise at all — the socket ends and the body is simply over — so the
     * reader completes normally on the frames that arrived, and the only thing that says the answer
     * was cut is that its surface's terminal frame is missing from them. Judging that is `Resume`'s
     * and not this class's, which knows nothing of surfaces. A response that did raise, or that was
     * cancelled under a stopping proxy, ends its readers with that failure instead. Either way the
     * resumed exchange is cut where the original was, and its client sees a stream with no terminal
     * frame, as it would have from the provider.
     */
    fun frames(): Flow<Frame> = flow {
        var next = 0
        emitAll(
            progress.transformWhile { now ->
                val fresh = lock.withLock { frames.subList(next, now.size).toList() }
                next = now.size
                fresh.forEach { emit(it) }
                now.failure?.let {
                    throw IOException("the exchange being resumed lost its stream", it)
                }
                !now.ended
            }
        )
    }
}
