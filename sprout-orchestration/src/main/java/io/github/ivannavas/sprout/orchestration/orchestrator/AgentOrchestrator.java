package io.github.ivannavas.sprout.orchestration.orchestrator;

import io.github.ivannavas.sprout.executor.AgentExecutor;
import io.github.ivannavas.sprout.model.AgentResult;
import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Fires agent prompts concurrently against a single {@link AgentExecutor} and lets you collect their
 * results — by id, as a live stream, or by blocking until a batch finishes.
 *
 * <p>Each {@link #execute(String) execute} call schedules one run on a worker thread and returns
 * immediately, so several prompts can be in flight at once. Results (and failures) are published to an
 * internal replay stream that any number of consumers may read, including <em>after</em> the run has
 * already completed. A failing run is isolated: it is reported as a failed {@link Execution} and does
 * not affect the other runs or close the stream.
 *
 * <p>Instances are fluent and reusable across many executions. They hold a worker subscription per
 * in-flight run, so call {@link #close()} when done to release resources and terminate the stream for
 * live consumers.
 *
 * <p>Typical use:
 * <pre>{@code
 * try (AgentOrchestrator orchestrator = AgentOrchestrator.of(executor, sessionId)) {
 *     orchestrator.execute("summarise the logs", "summary")
 *                 .execute("list the errors", "errors")
 *                 .waitForExecutions();
 *     AgentResult summary = orchestrator.getResult("summary").block();
 * }
 * }</pre>
 */
public class AgentOrchestrator implements AutoCloseable {

    /**
     * Outcome of a single scheduled run. Exactly one of {@code result} / {@code error} is non-null:
     * {@code result} for a run that produced a final answer, {@code error} for one that failed (after
     * exhausting any configured retries). Use {@link #isSuccess()} to tell them apart.
     */
    public record Execution(String id, AgentResult result, Throwable error) {

        /** A successful execution carrying its {@link AgentResult}. */
        public static Execution success(String id, AgentResult result) {
            return new Execution(id, result, null);
        }

        /** A failed execution carrying the {@link Throwable} that aborted it. */
        public static Execution failure(String id, Throwable error) {
            return new Execution(id, null, error);
        }

        /** Whether this execution completed with a result rather than an error. */
        public boolean isSuccess() {
            return error == null;
        }
    }

    private static final Sinks.EmitFailureHandler RETRY_NON_SERIALIZED =
            (signalType, emitResult) -> emitResult == Sinks.EmitResult.FAIL_NON_SERIALIZED;

    private volatile AgentExecutor executor;
    private volatile String sessionId;
    private volatile Duration executionTimeout;
    private volatile int maxRetries;
    private volatile Semaphore concurrencyLimit;

    private final Sinks.Many<Execution> sink = Sinks.many().replay().all();
    private final Flux<Execution> results = sink.asFlux();
    private final AtomicInteger submitted = new AtomicInteger();
    private final Disposable.Composite inFlight = Disposables.composite();

    private AgentOrchestrator(AgentExecutor executor, String sessionId) {
        this.executor = executor;
        this.sessionId = sessionId;
    }

    /** Creates an orchestrator for the given executor; a session id must be set before executing. */
    public static AgentOrchestrator of(AgentExecutor agentExecutor) {
        return new AgentOrchestrator(agentExecutor, null);
    }

    /** Creates an orchestrator bound to the given executor and conversation/session id. */
    public static AgentOrchestrator of(AgentExecutor agentExecutor, String sessionId) {
        return new AgentOrchestrator(agentExecutor, sessionId);
    }

    /** Sets the executor used by subsequent {@link #execute(String) executions}. */
    public AgentOrchestrator withExecutor(AgentExecutor executor) {
        this.executor = executor;
        return this;
    }

    /** The executor currently backing this orchestrator. */
    public AgentExecutor getExecutor() {
        return executor;
    }

    /** Sets the conversation/session id passed to the executor for subsequent executions. */
    public AgentOrchestrator withSessionId(String sessionId) {
        this.sessionId = sessionId;
        return this;
    }

    /**
     * Limits how many executions may run concurrently; further executions queue on a worker thread
     * until a slot frees. A non-positive value removes the limit. Applies to executions scheduled
     * after this call.
     */
    public AgentOrchestrator withMaxConcurrency(int maxConcurrency) {
        this.concurrencyLimit = maxConcurrency > 0 ? new Semaphore(maxConcurrency) : null;
        return this;
    }

    /**
     * Sets a per-execution timeout; a run that exceeds it fails (and is retried if retries are
     * configured). A null duration removes the timeout. Applies to executions scheduled after this
     * call.
     */
    public AgentOrchestrator withTimeout(Duration timeout) {
        this.executionTimeout = timeout;
        return this;
    }

    /**
     * Sets how many times a failed execution is retried before it is reported as a failure. Applies to
     * executions scheduled after this call.
     */
    public AgentOrchestrator withRetries(int maxRetries) {
        this.maxRetries = Math.max(0, maxRetries);
        return this;
    }

    /** Schedules a run with an auto-generated execution id, on the orchestrator's session. */
    public AgentOrchestrator execute(String prompt) {
        return execute(prompt, UUID.randomUUID().toString());
    }

    /**
     * Schedules a run under {@code executionId} on the orchestrator's
     * {@link #withSessionId(String) session}.
     *
     * @throws IllegalStateException if no session id has been set
     */
    public AgentOrchestrator execute(String prompt, String executionId) {
        return execute(prompt, executionId, this.sessionId);
    }

    /**
     * Schedules a run for {@code prompt} under {@code executionId} against {@code sessionId} on a
     * worker thread and returns immediately. Passing an explicit session per call is how independent
     * runs stay isolated when fanning out — each gets its own conversation without mutating shared
     * state between calls. A null {@code sessionId} falls back to the orchestrator's session. The
     * result (or failure) is published to the stream and retrievable via {@link #getResult(String)}
     * and the {@code waitForExecutions} / {@code getResults} methods.
     *
     * @throws IllegalStateException if neither a per-call nor an orchestrator session id is set
     */
    public AgentOrchestrator execute(String prompt, String executionId, String sessionId) {
        AgentExecutor exec = this.executor;
        String session = sessionId != null ? sessionId : this.sessionId;
        if (session == null) {
            throw new IllegalStateException("Session ID must be set before executing a prompt.");
        }
        String id = executionId != null ? executionId : UUID.randomUUID().toString();

        Duration timeout = this.executionTimeout;
        int retries = this.maxRetries;
        Semaphore limit = this.concurrencyLimit;

        Mono<AgentResult> run = Mono.fromCallable(() -> {
            if (limit != null) {
                limit.acquire();
            }
            try {
                return exec.execute(session, prompt);
            } finally {
                if (limit != null) {
                    limit.release();
                }
            }
        }).subscribeOn(Schedulers.boundedElastic());

        if (timeout != null) {
            run = run.timeout(timeout);
        }
        if (retries > 0) {
            run = run.retry(retries);
        }

        submitted.incrementAndGet();

        Disposable subscription = run.subscribe(
                result -> emit(Execution.success(id, result)),
                error -> emit(Execution.failure(id, error)));
        inFlight.add(subscription);

        return this;
    }

    private void emit(Execution execution) {
        sink.emitNext(execution, RETRY_NON_SERIALIZED);
    }

    /** Blocks until every execution scheduled so far has produced a result or failure. */
    public AgentOrchestrator waitForExecutions() {
        results.take(submitted.get()).blockLast();
        return this;
    }

    /**
     * Blocks until every execution scheduled so far has finished, or the timeout elapses.
     *
     * @param timeout maximum time to wait, in milliseconds
     */
    public AgentOrchestrator waitForExecutions(long timeout) {
        results.take(submitted.get()).blockLast(Duration.ofMillis(timeout));
        return this;
    }

    /** Blocks until each of the given execution ids has finished, or the timeout elapses. */
    public AgentOrchestrator waitForExecutions(List<String> executionIds, long timeout) {
        results.filter(execution -> executionIds.contains(execution.id()))
                .take(executionIds.size())
                .blockLast(Duration.ofMillis(timeout));
        return this;
    }

    /** Blocks until each of the given execution ids has finished. */
    public AgentOrchestrator waitForExecutions(List<String> executionIds) {
        results.filter(execution -> executionIds.contains(execution.id()))
                .take(executionIds.size())
                .blockLast();
        return this;
    }

    /**
     * The result of a single execution, completing once it finishes. The returned {@link Mono} errors
     * with the run's failure if that execution failed, and never completes if the id is never seen.
     */
    public Mono<AgentResult> getResult(String executionId) {
        return results.filter(execution -> Objects.equals(execution.id(), executionId))
                .next()
                .flatMap(execution -> execution.isSuccess()
                        ? Mono.just(execution.result())
                        : Mono.error(execution.error()));
    }

    /**
     * A live stream of successful results as executions finish. Failed executions are omitted; use
     * {@link #getExecutions()} to observe failures too. The stream stays open until {@link #close()}.
     */
    public Flux<AgentResult> getResults() {
        return results.filter(Execution::isSuccess).map(Execution::result);
    }

    /**
     * A live stream of every execution outcome (successes and failures) as runs finish. The stream
     * stays open until {@link #close()}.
     */
    public Flux<Execution> getExecutions() {
        return results;
    }

    /**
     * Invokes {@code consumer} once with all executions scheduled so far, after they have all finished.
     * Non-blocking: the callback runs on the worker thread that completes the batch.
     */
    public AgentOrchestrator then(Consumer<List<Execution>> consumer) {
        Disposable subscription = results.take(submitted.get()).collectList().subscribe(consumer);
        inFlight.add(subscription);
        return this;
    }

    /**
     * Cancels any in-flight executions and terminates the stream for live consumers. Already published
     * results remain available to consumers that subscribed before closing.
     */
    @Override
    public void close() {
        inFlight.dispose();
        sink.emitComplete(RETRY_NON_SERIALIZED);
    }
}
