package com.aliware.tianchi;

import org.apache.dubbo.rpc.Invoker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;
import oshi.hardware.GlobalMemory;
import oshi.hardware.HardwareAbstractionLayer;

import java.util.Collection;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * @author Viber
 * @version 1.0
 * @apiNote 可以使用个Map保存不同Invoker对应的ProviderManager, 这里就先使用单独的
 * @since 2021/9/10 14:32
 */
public class ProviderManager {
    private final static Logger logger = LoggerFactory.getLogger(ProviderManager.class);
    private static SystemInfo si = new SystemInfo();
    private static HardwareAbstractionLayer hal = si.getHardware();
    private static ScheduledExecutorService scheduledExecutor;
    private static volatile boolean once = true;

    public static Value weight = new Value(50);
    private static final long timeInterval = TimeUnit.MILLISECONDS.toNanos(100);
    public static Value executeTime = new Value(10);
    public static Value actualWeight = new Value((int) (1.1 * weight.value));
    private static final long windowSize = 5;
    static final long littleMillis = TimeUnit.MILLISECONDS.toNanos(1) / 100;
    static final int levelCount = 100; //能够支持统计tps的请求数
    private static final Counter<SumCounter> counter = new Counter<>(l -> new SumCounter());
    private static final Counter<SumCounter[]> counters = new Counter<>(l -> {
        SumCounter[] sumCounters = new SumCounter[7];
        for (int i = 0; i < sumCounters.length; i++) {
            sumCounters[i] = new SumCounter();
        }
        return sumCounters;
    });
    public static final AtomicLong active = new AtomicLong(0);

    public static void maybeInit(Invoker<?> invoker) {
        if (once) {
            synchronized (ProviderManager.class) {
                if (once) {
                    scheduledExecutor = Executors.newSingleThreadScheduledExecutor();
                    scheduledExecutor.scheduleWithFixedDelay(new CalculateTask(), 1000,
                            100, TimeUnit.MILLISECONDS);
                    once = false;
                }
            }
        }
    }

    private static void resetWeight(int w) {
        int aw = (int) (1.1 * w);
        weight.value = w;
        actualWeight.value = aw;
    }

    private static void resetExecuteTime(int et) {
        executeTime.value = et;
    }

    public static void time(long duration, long concurrent) {
        long offset = offset();
        SumCounter[] sumCounters = counters.get(offset);
        long w = weight.value;
        if (Math.abs(concurrent - w) <= 6) { //说明需要调整到对应的位置上去
            SumCounter sumCounter = sumCounters[(int) (concurrent - w + 6) >> 1];
            sumCounter.getTotal().add(1);
            sumCounter.getDuration().add(duration);
        }
    }

    private static class CalculateTask implements Runnable {
        @Override
        public void run() {
            long high = offset();
            long low = high - windowSize;

            Collection<SumCounter[]> sub = counters.sub(low, high);
            int[] counts = {0, 0, 0, 0, 0, 0, 0};
            long[] durations = {0, 0, 0, 0, 0, 0, 0};
            if (!sub.isEmpty()) {
                sub.forEach(state -> {
                    SumCounter counter;
                    for (int i = 0; i < 7; i++) {
                        counter = state[i];
                        counts[i] += counter.getTotal().sum();
                        durations[i] += counter.getDuration().sum();
                    }
                });
            }
            long toKey = high - (windowSize << 1);
            if (counts[3] > levelCount) {
                int v = weight.value;
                int[] weights = {v - 6, v - 4, v - 2, v, v + 2, v + 4, v + 6};
                long[] tps = new long[7];
                int maxIndex = 0;
                long maxTps = 0;
                int targetTime = executeTime.value;
                for (int i = 0; i < 7; i++) {
                    if (counts[i] > levelCount) {
                        double avgTime = Math.max(1.0, ((int) (((durations[i] / counts[i]) / littleMillis))) / 100.0); //保证1.xx的时间
                        long t = (long) ((1000.0 / avgTime) * weights[i]);//1s时间的tps
                        tps[i] = t;
                        if (maxTps < t) {
                            maxIndex = i;
                            maxTps = t;
                        }
                        if (i == 3) {
                            targetTime = (int) (Math.ceil(1.5 * avgTime));
                        }
                    }
                }
                long curTps = tps[3];
                if (maxIndex > 3) {
                    int total = 0;
                    int most = 0;
                    for (int i = 4; i < 7; i++) {
                        if (tps[i] > 0) {
                            total++;
                            if (tps[i] > curTps) {
                                most++;
                            }
                        }
                    }
                    if (most * 1.0 / total >= 0.5) {
                        resetWeight(v + 1);
                        toKey = offset();
                    }
                } else if (maxIndex < 3) {
                    int total = 0;
                    int most = 0;
                    for (int i = 0; i < 3; i++) {
                        if (tps[i] > 0) {
                            total++;
                            if (tps[i] > curTps) {
                                most++;
                            }
                        }
                    }
                    if (most * 1.0 / total >= 0.5) {
                        resetWeight(v - 1);
                        toKey = offset();
                    }
                }
                //存放和合适的超时时间
                resetExecuteTime((executeTime.value + targetTime) / 2);
            }
            counter.clean(toKey);
        }

    }

    public static long offset() {
        return System.nanoTime() / timeInterval;
    }

    private static double calculateMemory() {
        GlobalMemory memory = hal.getMemory();
        long total = memory.getTotal();
        return (total - memory.getAvailable()) * 1.0 / total;
    }

    private static double calculateCPURatio() {
        CentralProcessor processor = hal.getProcessor();
        long[] ticks = processor.getSystemCpuLoadTicks();
        long idle = ticks[CentralProcessor.TickType.IDLE.getIndex()] + ticks[CentralProcessor.TickType.IOWAIT.getIndex()];
        long total = 0;
        for (long tick : ticks) {
            total += tick;
        }
        return total > 0L && idle >= 0L ? (double) (total - idle) / (double) total : 0.0D;
    }
}
