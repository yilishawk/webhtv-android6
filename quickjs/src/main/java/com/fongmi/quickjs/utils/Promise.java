package com.fongmi.quickjs.utils;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * {@link java.util.concurrent.CompletableFuture} 的最小替身。
 *
 * <p>为什么需要它：CompletableFuture 是 <b>API 24</b> 才加入的平台类
 * （android-36 的 api-versions.xml 实测 {@code since="24"}），而本分支 minSdk=23。
 * 它<b>没有</b>被 desugar 兜住 —— 依据两条独立证据：
 * <ol>
 *   <li>APK 的 DEX 里只有 {@code Ljava/util/concurrent/CompletableFuture;} 的<b>引用</b>，
 *       没有对应类定义（对照：desugar 处理的 {@code j$/util/concurrent/ConcurrentHashMap}
 *       有 55 个类定义）；</li>
 *   <li>desugar_jdk_libs_configuration_nio 2.1.5 的
 *       {@code META-INF/desugar/d8/desugar.json} 里完全不含 CompletableFuture。</li>
 * </ol>
 * 所以在 API 23 真机上会 NoClassDefFoundError。
 *
 * <p>实现为 {@link Future}：调用方（{@code Spider.call()}）只用 {@code get()}，
 * 因此替换返回类型不需要改动调用点。
 *
 * <p>边界：只实现本工程用到的"一次性完成"语义。不支持链式回调、超时组合子等
 * CompletableFuture 的高级能力 —— 需要时应改用别的设计，而不是往这里堆。
 */
public final class Promise implements Future<Object> {

    private final CountDownLatch latch = new CountDownLatch(1);
    private volatile Object value;
    private volatile Throwable error;

    /** 正常完成。重复调用返回 false（只认第一次，与 CompletableFuture 一致）。 */
    public boolean complete(Object result) {
        synchronized (this) {
            if (latch.getCount() == 0) return false;
            value = result;
        }
        latch.countDown();
        return true;
    }

    /** 异常完成。重复调用返回 false。 */
    public boolean completeExceptionally(Throwable throwable) {
        synchronized (this) {
            if (latch.getCount() == 0) return false;
            error = throwable;
        }
        latch.countDown();
        return true;
    }

    @Override
    public Object get() throws ExecutionException, InterruptedException {
        latch.await();
        return result();
    }

    @Override
    public Object get(long timeout, TimeUnit unit) throws ExecutionException, InterruptedException, TimeoutException {
        if (!latch.await(timeout, unit)) throw new TimeoutException();
        return result();
    }

    private Object result() throws ExecutionException {
        Throwable cause = error;
        if (cause != null) throw new ExecutionException(cause);
        return value;
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        return completeExceptionally(new InterruptedException("cancelled"));
    }

    @Override
    public boolean isCancelled() {
        return latch.getCount() == 0 && error instanceof InterruptedException;
    }

    @Override
    public boolean isDone() {
        return latch.getCount() == 0;
    }
}
