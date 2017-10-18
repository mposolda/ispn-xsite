package org.jboss.sample;

import org.infinispan.configuration.cache.Configuration;
import org.infinispan.configuration.cache.ConfigurationBuilder;
import org.infinispan.configuration.global.GlobalConfigurationBuilder;
import org.infinispan.manager.DefaultCacheManager;
import org.infinispan.manager.EmbeddedCacheManager;
import org.infinispan.persistence.remote.configuration.ExhaustedAction;
import org.infinispan.persistence.remote.configuration.RemoteStoreConfigurationBuilder;

/**
 * @author <a href="mailto:mposolda@redhat.com">Marek Posolda</a>
 */
class TestCacheManagerFactory {


    // We need only underlying remoteCache, which we retrieve from the embedded cache. We are not interested at all about the "embedded" cache
    EmbeddedCacheManager createManager(int threadId, String cacheName) {
        System.setProperty("java.net.preferIPv4Stack", "true");
        System.setProperty("jgroups.tcp.port", "53715");
        GlobalConfigurationBuilder gcb = new GlobalConfigurationBuilder();

        boolean allowDuplicateJMXDomains = true;

        gcb.globalJmxStatistics().allowDuplicateDomains(allowDuplicateJMXDomains);

        EmbeddedCacheManager cacheManager = new DefaultCacheManager(gcb.build());

        Configuration cacheConfig = getCacheBackedByRemoteStore(threadId, cacheName);

        cacheManager.defineConfiguration(cacheName, cacheConfig);
        return cacheManager;

    }


    private Configuration getCacheBackedByRemoteStore(int threadId, String cacheName) {
        ConfigurationBuilder cacheConfigBuilder = new ConfigurationBuilder();

        String host = "localhost";
        int port = threadId==1 ? 12232 : 13232;
        //int port = 11222;

        return cacheConfigBuilder.persistence().addStore(RemoteStoreConfigurationBuilder.class)
                .fetchPersistentState(false)
                .ignoreModifications(false)
                .purgeOnStartup(false)
                .preload(false)
                .shared(true)
                .remoteCacheName(cacheName)
                .rawValues(true)
                .forceReturnValues(false)
                .addServer()
                .host(host)
                .port(port)
                .connectionPool()
                    .maxActive(20)
                    .exhaustedAction(ExhaustedAction.CREATE_NEW)
                .async()
                    .enabled(false).build();
    }
}
