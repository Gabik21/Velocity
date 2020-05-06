package com.velocitypowered.proxy.network;

import static com.velocitypowered.proxy.network.Connections.FRAME_DECODER;
import static com.velocitypowered.proxy.network.Connections.FRAME_ENCODER;
import static com.velocitypowered.proxy.network.Connections.LEGACY_PING_DECODER;
import static com.velocitypowered.proxy.network.Connections.LEGACY_PING_ENCODER;
import static com.velocitypowered.proxy.network.Connections.MINECRAFT_DECODER;
import static com.velocitypowered.proxy.network.Connections.MINECRAFT_ENCODER;
import static com.velocitypowered.proxy.network.Connections.READ_TIMEOUT;

import com.velocitypowered.proxy.VelocityServer;
import com.velocitypowered.proxy.connection.MinecraftConnection;
import com.velocitypowered.proxy.connection.client.HandshakeSessionHandler;
import com.velocitypowered.proxy.protocol.ProtocolUtils;
import com.velocitypowered.proxy.protocol.netty.LegacyPingDecoder;
import com.velocitypowered.proxy.protocol.netty.LegacyPingEncoder;
import com.velocitypowered.proxy.protocol.netty.MinecraftDecoder;
import com.velocitypowered.proxy.protocol.netty.MinecraftEncoder;
import com.velocitypowered.proxy.protocol.netty.MinecraftVarintFrameDecoder;
import com.velocitypowered.proxy.protocol.netty.MinecraftVarintLengthEncoder;
import com.velocitypowered.proxy.util.ratelimit.Ratelimiter;
import com.velocitypowered.proxy.util.ratelimit.Ratelimiters;
import com.velocitypowered.proxy.util.ratelimit.Throttle;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.handler.codec.haproxy.HAProxyMessageDecoder;
import io.netty.handler.timeout.ReadTimeoutHandler;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

@SuppressWarnings("WeakerAccess")
public class ServerChannelInitializer extends ChannelInitializer<Channel> {

  private final VelocityServer server;
  private final Ratelimiter ratelimiter = Ratelimiters.createWithMilliseconds(100);
  private final Throttle globalThrottle = new Throttle(500, Duration.ofSeconds(20));

  public ServerChannelInitializer(final VelocityServer server) {
    this.server = server;
  }

  @Override
  protected void initChannel(final Channel ch) {
    InetAddress address = ((InetSocketAddress) ch.remoteAddress()).getAddress();

    if (!ratelimiter.attempt(address)) {
      ch.close();
      return;
    }

    if (!server.getAddressWhitelist().isWhitelisted(address) && globalThrottle.throttle()) {
      ch.close();
      return;
    }

    ch.pipeline()
        .addLast(READ_TIMEOUT,
            new ReadTimeoutHandler(this.server.getConfiguration().getReadTimeout(),
                TimeUnit.MILLISECONDS))
        .addLast(LEGACY_PING_DECODER, new LegacyPingDecoder())
        .addLast(FRAME_DECODER, new MinecraftVarintFrameDecoder())
        .addLast(LEGACY_PING_ENCODER, LegacyPingEncoder.INSTANCE)
        .addLast(FRAME_ENCODER, MinecraftVarintLengthEncoder.INSTANCE)
        .addLast(MINECRAFT_DECODER, new MinecraftDecoder(ProtocolUtils.Direction.SERVERBOUND))
        .addLast(MINECRAFT_ENCODER, new MinecraftEncoder(ProtocolUtils.Direction.CLIENTBOUND));

    final MinecraftConnection connection = new MinecraftConnection(ch, this.server);
    connection.setSessionHandler(new HandshakeSessionHandler(connection, this.server));
    ch.pipeline().addLast(Connections.HANDLER, connection);

    if (this.server.getConfiguration().isProxyProtocol()) {
      ch.pipeline().addFirst(new HAProxyMessageDecoder());
    }
  }
}
