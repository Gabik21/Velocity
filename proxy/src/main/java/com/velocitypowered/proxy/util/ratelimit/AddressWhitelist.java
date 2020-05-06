package com.velocitypowered.proxy.util.ratelimit;

import com.google.common.base.Ticker;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import java.net.InetAddress;
import java.time.Duration;

public class AddressWhitelist {

  private static final Object DUMMY = new Object();

  private final Cache<InetAddress, Object> whitelistCache;

  /**
   * Creates an {@link AddressWhitelist} with a given whitelist duration.
   *
   * @param whitelistTime to whitelist until blocking
   */
  public AddressWhitelist(Duration whitelistTime) {
    this.whitelistCache = CacheBuilder.newBuilder()
        .expireAfterWrite(whitelistTime)
        .ticker(Ticker.systemTicker())
        .concurrencyLevel(Runtime.getRuntime().availableProcessors())
        .build();
  }

  public boolean isWhitelisted(InetAddress address) {
    return whitelistCache.getIfPresent(address) != null;
  }

  public void whitelist(InetAddress address) {
    whitelistCache.put(address, DUMMY);
  }

}
