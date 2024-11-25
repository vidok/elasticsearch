/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.inference.external.http;

import org.apache.http.config.Registry;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.impl.nio.conn.PoolingNHttpClientConnectionManager;
import org.apache.http.impl.nio.reactor.DefaultConnectingIOReactor;
import org.apache.http.impl.nio.reactor.IOReactorConfig;
import org.apache.http.nio.conn.NoopIOSessionStrategy;
import org.apache.http.nio.conn.SchemeIOSessionStrategy;
import org.apache.http.nio.conn.ssl.SSLIOSessionStrategy;
import org.apache.http.nio.reactor.ConnectingIOReactor;
import org.apache.http.nio.reactor.IOReactorException;
import org.apache.http.pool.PoolStats;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.ssl.SslConfiguration;
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.xpack.core.XPackPlugin;
import org.elasticsearch.xpack.core.ssl.SSLConfigurationSettings;
import org.elasticsearch.xpack.core.ssl.SSLService;
import org.elasticsearch.xpack.inference.logging.ThrottlerManager;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.elasticsearch.core.Strings.format;

public class ElasticInferenceServiceHttpClientManager implements Closeable {
    public static final String SSL_CONFIGURATION_PREFIX = "xpack.inference.elastic.http.ssl.";
    private static final Logger logger = LogManager.getLogger(HttpClientManager.class);
    /**
     * The maximum number of total connections the connection pool can lease to all routes.
     * From googling around the connection pools maxTotal value should be close to the number of available threads.
     *
     * https://stackoverflow.com/questions/30989637/how-to-decide-optimal-settings-for-setmaxtotal-and-setdefaultmaxperroute
     */
    public static final Setting<Integer> MAX_TOTAL_CONNECTIONS = Setting.intSetting(
        "xpack.inference.elastic.http.max_total_connections",
        50, // default
        1, // min
        Setting.Property.NodeScope,
        Setting.Property.Dynamic
    );

    /**
     * The max number of connections a single route can lease.
     */
    public static final Setting<Integer> MAX_ROUTE_CONNECTIONS = Setting.intSetting(
        "xpack.inference.elastic.http.max_route_connections",
        20, // default
        1, // min
        Setting.Property.NodeScope,
        Setting.Property.Dynamic
    );

    private static final TimeValue DEFAULT_CONNECTION_EVICTION_THREAD_INTERVAL_TIME = TimeValue.timeValueMinutes(1);
    public static final Setting<TimeValue> CONNECTION_EVICTION_THREAD_INTERVAL_SETTING = Setting.timeSetting(
        "xpack.inference.elastic.http.connection_eviction_interval",
        DEFAULT_CONNECTION_EVICTION_THREAD_INTERVAL_TIME,
        Setting.Property.NodeScope,
        Setting.Property.Dynamic
    );

    private static final TimeValue DEFAULT_CONNECTION_MAX_IDLE_TIME_SETTING = DEFAULT_CONNECTION_EVICTION_THREAD_INTERVAL_TIME;
    /**
     * The max duration of time for a connection to be marked as idle and ready to be closed. This defines the amount of time
     * a connection can be unused in the connection pool before being closed the next time the eviction thread runs.
     * It also defines the keep-alive value for the connection if one is not specified by the 3rd party service's server.
     *
     * For more info see the answer here:
     * https://stackoverflow.com/questions/64676200/understanding-the-lifecycle-of-a-connection-managed-by-poolinghttpclientconnecti
     */
    public static final Setting<TimeValue> CONNECTION_MAX_IDLE_TIME_SETTING = Setting.timeSetting(
        "xpack.inference.elastic.http.connection_eviction_max_idle_time",
        DEFAULT_CONNECTION_MAX_IDLE_TIME_SETTING,
        Setting.Property.NodeScope,
        Setting.Property.Dynamic
    );

    public static final SSLConfigurationSettings SSL_CONFIGURATION_SETTINGS = SSLConfigurationSettings.withPrefix(
        SSL_CONFIGURATION_PREFIX,
        false
    );

    public static final Setting<Boolean> EIS_SSL_ENABLED = Setting.boolSetting(
        SSL_CONFIGURATION_PREFIX + "enabled",
        true,
        Setting.Property.NodeScope
    );

    private final ThreadPool threadPool;
    private final PoolingNHttpClientConnectionManager connectionManager;
    private IdleConnectionEvictor connectionEvictor;
    private final HttpClient httpClient;

    private volatile TimeValue evictionInterval;
    private volatile TimeValue connectionMaxIdle;

    public static ElasticInferenceServiceHttpClientManager create(
        Settings settings,
        ThreadPool threadPool,
        ClusterService clusterService,
        ThrottlerManager throttlerManager
    ) {

        SSLService sslService = XPackPlugin.getSharedSslService();
        SSLIOSessionStrategy sslStrategy;
        sslStrategy = sslService.sslIOSessionStrategy(sslService.getSSLConfiguration(SSL_CONFIGURATION_PREFIX));

        PoolingNHttpClientConnectionManager connectionManager = createConnectionManager(sslStrategy);

        return new ElasticInferenceServiceHttpClientManager(settings,
            connectionManager,
            threadPool,
            clusterService,
            throttlerManager);
    }

    // Default for testing
    ElasticInferenceServiceHttpClientManager(
        Settings settings,
        PoolingNHttpClientConnectionManager connectionManager,
        ThreadPool threadPool,
        ClusterService clusterService,
        ThrottlerManager throttlerManager
    ) {
        this.threadPool = threadPool;

        this.connectionManager = connectionManager;
        setMaxConnections(MAX_TOTAL_CONNECTIONS.get(settings));
        setMaxRouteConnections(MAX_ROUTE_CONNECTIONS.get(settings));

        this.httpClient = HttpClient.create(new HttpSettings(settings, clusterService),
            threadPool,
            connectionManager,
            throttlerManager);

        this.evictionInterval = CONNECTION_EVICTION_THREAD_INTERVAL_SETTING.get(settings);
        this.connectionMaxIdle = CONNECTION_MAX_IDLE_TIME_SETTING.get(settings);
        connectionEvictor = createConnectionEvictor();

        this.addSettingsUpdateConsumers(clusterService);
    }

    private static PoolingNHttpClientConnectionManager createConnectionManager(SSLIOSessionStrategy sslStrategy) {
        ConnectingIOReactor ioReactor;
        try {
            var configBuilder = IOReactorConfig.custom().setSoKeepAlive(true);
            ioReactor = new DefaultConnectingIOReactor(configBuilder.build());
        } catch (IOReactorException e) {
            var message = "Failed to initialize the inference http client manager";
            logger.error(message, e);
            throw new ElasticsearchException(message, e);
        }

        Registry<SchemeIOSessionStrategy> registry = RegistryBuilder.<SchemeIOSessionStrategy>create()
            .register("http", NoopIOSessionStrategy.INSTANCE)
            .register("https", sslStrategy)
            .build();

        /*
          The max time to live for open connections in the pool will not be set because we don't specify a ttl in the constructor.
          This meaning that there should not be a limit.
          We can control the TTL dynamically using the IdleConnectionEvictor and keep-alive strategy.
          The max idle time cluster setting will dictate how much time an open connection can be unused for before it can be closed.
         */

        return new PoolingNHttpClientConnectionManager(ioReactor, null, registry);
    }

    private void addSettingsUpdateConsumers(ClusterService clusterService) {
        clusterService.getClusterSettings().addSettingsUpdateConsumer(MAX_TOTAL_CONNECTIONS, this::setMaxConnections);
        clusterService.getClusterSettings().addSettingsUpdateConsumer(MAX_ROUTE_CONNECTIONS, this::setMaxRouteConnections);
        clusterService.getClusterSettings()
            .addSettingsUpdateConsumer(CONNECTION_EVICTION_THREAD_INTERVAL_SETTING, this::setEvictionInterval);
        clusterService.getClusterSettings().addSettingsUpdateConsumer(CONNECTION_MAX_IDLE_TIME_SETTING, this::setConnectionMaxIdle);
    }

    private IdleConnectionEvictor createConnectionEvictor() {
        return new IdleConnectionEvictor(threadPool, connectionManager, evictionInterval, connectionMaxIdle);
    }

    public static List<Setting<?>> getSettingsDefinitions() {
        ArrayList<Setting<?>> settings = new ArrayList<>();
        settings.add(MAX_TOTAL_CONNECTIONS);
        settings.add(MAX_ROUTE_CONNECTIONS);
        settings.add(CONNECTION_EVICTION_THREAD_INTERVAL_SETTING);
        settings.add(CONNECTION_MAX_IDLE_TIME_SETTING);
        settings.add(EIS_SSL_ENABLED);
        settings.addAll(SSL_CONFIGURATION_SETTINGS.getEnabledSettings());

        return settings;
    }

    public void start() {
        httpClient.start();
        connectionEvictor.start();
    }

    public HttpClient getHttpClient() {
        return httpClient;
    }

    public PoolStats getPoolStats() {
        return connectionManager.getTotalStats();
    }

    @Override
    public void close() throws IOException {
        httpClient.close();
        connectionEvictor.close();
    }

    private void setMaxConnections(int maxConnections) {
        connectionManager.setMaxTotal(maxConnections);
    }

    private void setMaxRouteConnections(int maxConnections) {
        connectionManager.setDefaultMaxPerRoute(maxConnections);
    }

    // This is only used for testing
    boolean isEvictionThreadRunning() {
        return connectionEvictor.isRunning();
    }

    // default for testing
    void setEvictionInterval(TimeValue evictionInterval) {
        logger.debug(() -> format("Eviction thread's interval time updated to [%s]", evictionInterval));
        this.evictionInterval = evictionInterval;

        connectionEvictor.close();
        connectionEvictor = createConnectionEvictor();
        connectionEvictor.start();
    }

    void setConnectionMaxIdle(TimeValue connectionMaxIdle) {
        logger.debug(() -> format("Eviction thread's max idle time updated to [%s]", connectionMaxIdle));
        this.connectionMaxIdle = connectionMaxIdle;
        connectionEvictor.setMaxIdleTime(connectionMaxIdle);
    }
}

