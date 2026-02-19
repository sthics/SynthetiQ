package dev.synthetiq.config;

import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Async execution configuration.
 *
 * <p>TRADEOFF: Virtual threads vs. bounded thread pools.
 * We use virtual threads (Java 21) because agent calls are I/O-bound
 * (waiting on Ollama HTTP, Bedrock API, GitHub API). Virtual threads
 * give us near-unlimited concurrency without pool sizing headaches.
 *
 * <p>However, we wrap them with MDC propagation so correlation IDs
 * (set in the webhook filter) survive across async boundaries.
 * Without this, distributed tracing breaks — every agent call
 * would lose its review context in logs.
 */
@Configuration
public class AsyncConfig {

    /**
     * Task executor for Spring @Async agent execution.
     * Virtual threads + MDC propagation.
     */
    @Bean(name = "agentExecutor")
    public TaskExecutor agentExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        // Virtual thread factory — each agent call gets its own virtual thread
        executor.setTaskDecorator(new MdcPropagatingTaskDecorator());
        executor.setThreadFactory(Thread.ofVirtual().name("agent-", 0).factory());
        executor.setCorePoolSize(0); // Virtual threads don't need a pool
        executor.setMaxPoolSize(Integer.MAX_VALUE);
        executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("agent-");
        executor.initialize();
        return executor;
    }

    /**
     * ExecutorService for CompletableFuture.supplyAsync fan-out in the orchestrator.
     * Uses virtual threads with MDC propagation for log correlation.
     */
    @Bean(name = "agentExecutorService")
    public ExecutorService agentExecutorService() {
        ExecutorService base = Executors.newVirtualThreadPerTaskExecutor();
        MdcPropagatingTaskDecorator decorator = new MdcPropagatingTaskDecorator();
        return new DelegatingExecutorService(base, decorator);
    }

    /**
     * Propagates MDC context (correlationId, reviewId) from the calling thread
     * to the async agent thread. Critical for log correlation.
     */
    static class MdcPropagatingTaskDecorator implements TaskDecorator {
        @Override
        public Runnable decorate(Runnable runnable) {
            Map<String, String> contextMap = MDC.getCopyOfContextMap();
            return () -> {
                try {
                    if (contextMap != null) {
                        MDC.setContextMap(contextMap);
                    }
                    runnable.run();
                } finally {
                    MDC.clear();
                }
            };
        }
    }

    /**
     * Wraps an ExecutorService to apply MDC propagation to all submitted tasks.
     */
    static class DelegatingExecutorService extends java.util.concurrent.AbstractExecutorService {
        private final ExecutorService delegate;
        private final MdcPropagatingTaskDecorator decorator;

        DelegatingExecutorService(ExecutorService delegate, MdcPropagatingTaskDecorator decorator) {
            this.delegate = delegate;
            this.decorator = decorator;
        }

        @Override
        public void execute(Runnable command) {
            delegate.execute(decorator.decorate(command));
        }

        @Override public void shutdown() { delegate.shutdown(); }
        @Override public java.util.List<Runnable> shutdownNow() { return delegate.shutdownNow(); }
        @Override public boolean isShutdown() { return delegate.isShutdown(); }
        @Override public boolean isTerminated() { return delegate.isTerminated(); }
        @Override public boolean awaitTermination(long timeout, java.util.concurrent.TimeUnit unit)
                throws InterruptedException { return delegate.awaitTermination(timeout, unit); }
    }
}
