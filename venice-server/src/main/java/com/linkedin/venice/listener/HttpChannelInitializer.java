package com.linkedin.venice.listener;

import com.linkedin.ddsstorage.router.lnkd.netty4.SSLInitializer;
import com.linkedin.security.ssl.access.control.SSLEngineComponentFactory;
import com.linkedin.venice.offsets.OffsetManager;
import com.linkedin.venice.server.StoreRepository;
import com.linkedin.venice.stats.AggServerHttpRequestStats;
import com.linkedin.venice.utils.DaemonThreadFactory;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.timeout.IdleStateHandler;
import io.tehuti.metrics.MetricsRepository;

import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class HttpChannelInitializer extends ChannelInitializer<SocketChannel> {

  private final ExecutorService executor;
  protected final StorageExecutionHandler storageExecutionHandler;
  private final AggServerHttpRequestStats stats;
  private final Optional<SSLEngineComponentFactory> sslFactory;
  private final VerifySslHandler verifySsl = new VerifySslHandler();

  //TODO make this configurable
  private static final int numRestServiceStorageThreads = 8;
  private static int NETTY_IDLE_TIME_IN_SECONDS = 300; // 5 mins

  public HttpChannelInitializer(StoreRepository storeRepository, OffsetManager offsetManager, MetricsRepository metricsRepository, Optional<SSLEngineComponentFactory> sslFactory) {
    this.executor = Executors.newFixedThreadPool(
        numRestServiceStorageThreads,
        new DaemonThreadFactory("StorageExecutionThread"));

    stats = new AggServerHttpRequestStats(metricsRepository);

    storageExecutionHandler = new StorageExecutionHandler(executor,
        storeRepository,
        offsetManager,
        stats);

    this.sslFactory = sslFactory;
  }

  @Override
  public void initChannel(SocketChannel ch) throws Exception {

    if (sslFactory.isPresent()){
      ch.pipeline()
          .addLast(new SSLInitializer(sslFactory.get()));
    }

    StatsHandler statsHandler = new StatsHandler(stats);
    ch.pipeline().addLast(statsHandler)
        .addLast(new HttpServerCodec())
        .addLast(new OutboundHttpWrapperHandler(statsHandler))
        .addLast(new IdleStateHandler(0, 0, NETTY_IDLE_TIME_IN_SECONDS));

    if (sslFactory.isPresent()){
      ch.pipeline()
          .addLast(verifySsl);
    }
    ch.pipeline()
        .addLast(new GetRequestHttpHandler(statsHandler))
        .addLast("storageExecutionHandler", storageExecutionHandler)
        .addLast(new ErrorCatchingHandler());
  }

}
