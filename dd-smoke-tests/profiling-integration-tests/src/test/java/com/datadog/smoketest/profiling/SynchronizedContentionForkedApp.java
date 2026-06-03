package com.datadog.smoketest.profiling;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class SynchronizedContentionForkedApp {
  private static final int REPETITIONS = 5;
  private static final Object BLOCK_LOCK = new Object();

  public static void main(final String[] args) throws Exception {
    SynchronizedContentionForkedApp app = new SynchronizedContentionForkedApp();
    for (int i = 0; i < REPETITIONS; i++) {
      app.runBlockScenario();
      app.runInstanceMethodScenario();
      app.runStaticMethodScenario();
    }
    Thread.sleep(1500);
  }

  private final InstanceLockTarget instanceTarget = new InstanceLockTarget();

  private SynchronizedContentionForkedApp() {}

  private void runBlockScenario() throws Exception {
    CountDownLatch holderIn = new CountDownLatch(1);
    CountDownLatch holderOut = new CountDownLatch(1);
    Thread holder =
        new Thread(
            () -> {
              synchronized (BLOCK_LOCK) {
                holderIn.countDown();
                try {
                  holderOut.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
              }
            },
            "sync-block-holder");
    holder.setDaemon(true);
    holder.start();
    holderIn.await();

    Thread.currentThread().setName("sync-block-contender");
    synchronized (BLOCK_LOCK) {
      // entry-queue wait is the TaskBlock interval
    }
    holderOut.countDown();
    holder.join();
  }

  private void runInstanceMethodScenario() throws Exception {
    CountDownLatch holderIn = new CountDownLatch(1);
    CountDownLatch holderOut = new CountDownLatch(1);
    Thread holder =
        new Thread(() -> instanceTarget.hold(holderIn, holderOut), "sync-instance-holder");
    holder.setDaemon(true);
    holder.start();
    holderIn.await();

    Thread.currentThread().setName("sync-instance-contender");
    instanceTarget.contend();
    holderOut.countDown();
    holder.join();
  }

  private void runStaticMethodScenario() throws Exception {
    CountDownLatch holderIn = new CountDownLatch(1);
    CountDownLatch holderOut = new CountDownLatch(1);
    Thread holder =
        new Thread(() -> StaticLockTarget.hold(holderIn, holderOut), "sync-static-holder");
    holder.setDaemon(true);
    holder.start();
    holderIn.await();

    Thread.currentThread().setName("sync-static-contender");
    StaticLockTarget.contend();
    holderOut.countDown();
    holder.join();
  }

  static final class InstanceLockTarget {
    synchronized void hold(final CountDownLatch in, final CountDownLatch out) {
      in.countDown();
      try {
        out.await(2, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    synchronized void contend() {}
  }

  static final class StaticLockTarget {
    static synchronized void hold(final CountDownLatch in, final CountDownLatch out) {
      in.countDown();
      try {
        out.await(2, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    static synchronized void contend() {}
  }
}
