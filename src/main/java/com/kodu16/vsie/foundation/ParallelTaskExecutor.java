package com.kodu16.vsie.foundation;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 并行任务执行器，用于将控制座外设更新等独立计算卸载到公共 ForkJoinPool。
 * 设计原则：
 * - 仅用于「无副作用、仅读取世界状态、结果最后聚合」的计算
 * - 所有写入世界/方块实体/网络的操作仍在主线程串行完成
 * - 使用 try-catch 兜底，单任务失败不影响其它任务
 */
public final class ParallelTaskExecutor {
    private static final Executor EXECUTOR = ForkJoinPool.commonPool();
    private static final int MAX_CONCURRENT_TASKS = 8;

    private ParallelTaskExecutor() {
    }

    /**
     * 并行执行多个独立任务，等待全部完成，收集异常。
     * @param tasks 任务列表，每个任务返回结果或抛出异常
     * @param <T> 结果类型
     * @return 所有成功任务的结果列表（失败的任务会被跳过并记录日志）
     */
    public static <T> List<T> invokeAll(List<Supplier<T>> tasks) {
        if (tasks.isEmpty()) {
            return List.of();
        }
        List<CompletableFuture<T>> futures = new ArrayList<>(tasks.size());
        for (Supplier<T> task : tasks) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                try {
                    return task.get();
                } catch (Exception e) {
                    // 记录但不抛出，避免阻塞其它任务
                    com.mojang.logging.LogUtils.getLogger().error("[VSIE-Parallel] Task failed", e);
                    return null;
                }
            }, EXECUTOR));
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        List<T> results = new ArrayList<>(futures.size());
        for (CompletableFuture<T> f : futures) {
            T r = f.getNow(null);
            if (r != null) {
                results.add(r);
            }
        }
        return results;
    }

    /**
     * 并行执行多个无返回值任务（仅副作用计算，结果通过外部 mutable 容器收集）。
     */
    public static void runAll(List<Runnable> tasks) {
        if (tasks.isEmpty()) {
            return;
        }
        List<CompletableFuture<Void>> futures = new ArrayList<>(tasks.size());
        for (Runnable task : tasks) {
            futures.add(CompletableFuture.runAsync(() -> {
                try {
                    task.run();
                } catch (Exception e) {
                    com.mojang.logging.LogUtils.getLogger().error("[VSIE-Parallel] Runnable task failed", e);
                }
            }, EXECUTOR));
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
    }

    /**
     * 分批并行执行，避免一次性提交过多任务导致公共池饱和。
     * @param items 数据项
     * @param processor 处理函数（线程安全）
     * @param <T> 数据类型
     */
    public static <T> void forEachBatch(List<T> items, Consumer<T> processor) {
        if (items.isEmpty()) {
            return;
        }
        int batchSize = Math.max(1, items.size() / MAX_CONCURRENT_TASKS);
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (int i = 0; i < items.size(); i += batchSize) {
            int end = Math.min(i + batchSize, items.size());
            List<T> batch = items.subList(i, end);
            futures.add(CompletableFuture.runAsync(() -> {
                for (T item : batch) {
                    try {
                        processor.accept(item);
                    } catch (Exception e) {
                        com.mojang.logging.LogUtils.getLogger().error("[VSIE-Parallel] Batch item failed", e);
                    }
                }
            }, EXECUTOR));
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
    }
}