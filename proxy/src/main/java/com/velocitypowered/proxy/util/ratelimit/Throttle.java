package com.velocitypowered.proxy.util.ratelimit;

import java.time.Duration;
import java.util.LinkedList;
import java.util.concurrent.TimeUnit;

public class Throttle {

  private final LimitedQueue<Long> timeStamps;
  private final Duration duration;

  public Throttle(int range, Duration duration) {
    this.timeStamps = new LimitedQueue<>(range);
    this.duration = duration;
  }

  private synchronized boolean isThrottled() {
    Long firstEntry = timeStamps.peek();
    return timeStamps.isFull() && firstEntry != null
        && !hasPassed(firstEntry, TimeUnit.MILLISECONDS, duration.toMillis());
  }

  /**
   * Checks if throttling is applicable based on the rate this method is called at.
   *
   * @return true if action should be cancelled
   */
  public synchronized boolean throttle() {
    boolean throttled = isThrottled();
    if (!throttled) {
      timeStamps.add(System.currentTimeMillis());
    }
    return throttled;
  }

  public static boolean hasPassed(long past, TimeUnit unit, long amount) {
    return hasPassed(past, System.currentTimeMillis(), unit, amount);
  }

  public static boolean hasPassed(long past, long current, TimeUnit unit, long amount) {
    return current - past >= unit.toMillis(amount);
  }

  public static class LimitedQueue<E> extends LinkedList<E> {

    private static final long serialVersionUID = -1757382252035908923L;
    private int limit;

    public LimitedQueue(int limit) {
      this.limit = limit;
    }

    public boolean isFull() {
      return size() >= limit;
    }

    @Override
    public boolean add(E o) {
      while (size() >= limit) {
        super.remove();
      }
      return super.add(o);
    }
  }


}
