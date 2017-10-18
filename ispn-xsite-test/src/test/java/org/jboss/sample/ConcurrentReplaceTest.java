package org.jboss.sample;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.infinispan.Cache;
import org.infinispan.client.hotrod.RemoteCache;
import org.infinispan.client.hotrod.VersionedValue;
import org.infinispan.manager.EmbeddedCacheManager;
import org.infinispan.persistence.manager.PersistenceManager;
import org.infinispan.persistence.remote.RemoteStore;
import org.jboss.logging.Logger;
import org.junit.Assert;
import org.junit.Test;

/**
 * @author <a href="mailto:mposolda@redhat.com">Marek Posolda</a>
 */
public class ConcurrentReplaceTest {

    protected static final Logger logger = Logger.getLogger(ConcurrentReplaceTest.class);

    private static final int ITERATION_PER_WORKER = 10;

    private static RemoteCache<String, Map<String, String>> remoteCache1;
    private static RemoteCache<String, Map<String, String>> remoteCache2;

    private static final AtomicInteger failedReplaceCounter = new AtomicInteger(0);
    private static final AtomicInteger failedReplaceCounter2 = new AtomicInteger(0);

    private static final String CACHE_NAME = "sessions";


    @Test
    public void myTest() throws Exception {
        Cache<String, Map<String, String>> cache1 = createManager(1).getCache(CACHE_NAME);
        Cache<String, Map<String, String>> cache2 = createManager(2).getCache(CACHE_NAME);

        try {
            remoteCache1 = (RemoteCache<String, Map<String, String>>) (RemoteCache) cache1.getAdvancedCache().getComponentRegistry()
                    .getComponent(PersistenceManager.class).getStores(RemoteStore.class)
                    .iterator().next().getRemoteCache();

            remoteCache2 = (RemoteCache<String, Map<String, String>>) (RemoteCache) cache2.getAdvancedCache().getComponentRegistry()
                    .getComponent(PersistenceManager.class).getStores(RemoteStore.class)
                    .iterator().next().getRemoteCache();

            logger.info("Caches created.");

            logger.info("remoteCache1 using localhost:" + remoteCache1.getRemoteCacheManager().getConfiguration().servers().get(0).port());
            logger.info("remoteCache2 using localhost:" + remoteCache2.getRemoteCacheManager().getConfiguration().servers().get(0).port());

            remoteCache1.put("123", new HashMap<>());

            logger.info("After put of initial item");

            // Just to ensure propagating to site2
            Thread.sleep(1000);

            // Create worker threads
            Thread worker1 = createWorker(cache1, 1);
            Thread worker2 = createWorker(cache2, 2);

            long start = System.currentTimeMillis();

            logger.info("Starting workers");

            // Start and join workers
            worker1.start();
            worker2.start();

            worker1.join();
            worker2.join();

            long took = System.currentTimeMillis() - start;

            System.out.println("Finished. Took: " + took + " ms. Notes: " + remoteCache1.get("123").size() +
                    ", failedReplaceCounter: " + failedReplaceCounter.get() + ", failedReplaceCounter2: " + failedReplaceCounter2.get());

            System.out.println("Sleeping before other report");

            Thread.sleep(2000);

            System.out.println("Finished. Took: " + took + " ms. Notes: " + remoteCache1.get("123").size() +
                    ", failedReplaceCounter: " + failedReplaceCounter.get() + ", failedReplaceCounter2: " + failedReplaceCounter2.get());

            Assert.assertEquals(ITERATION_PER_WORKER * 2, remoteCache1.get("123").size());
        } finally {
            // Finish JVM
            cache1.getCacheManager().stop();
            cache2.getCacheManager().stop();
        }
    }


    private static EmbeddedCacheManager createManager(int threadId) {
        return new TestCacheManagerFactory().createManager(threadId, CACHE_NAME);
    }


    private static Thread createWorker(Cache<String, Map<String, String>> cache, int threadId) {

        if (threadId == 1) {
            return new RemoteCacheWorker(remoteCache1, threadId);
        } else {
            return new RemoteCacheWorker(remoteCache2, threadId);
        }
    }



    private static class RemoteCacheWorker extends Thread {

        private final RemoteCache<String, Map<String, String>> remoteCache;

        private final int myThreadId;


        private RemoteCacheWorker(RemoteCache<String, Map<String, String>> remoteCache, int myThreadId) {
            this.remoteCache = remoteCache;
            this.myThreadId = myThreadId;
        }


        @Override
        public void run() {

            for (int i=0 ; i<ITERATION_PER_WORKER ; i++) {

                String noteKey = "n-" + myThreadId + "-" + i;

                boolean replaced = false;

                // If replace failed or throw the exception, we want to retry it. The point is to avoid "lost updates" (write skews)
                while (!replaced) {
                    VersionedValue<Map<String, String>> versioned = remoteCache.getVersioned("123");
                    Map<String, String> map = versioned.getValue();

                    map.put(noteKey, "someVal");
                    replaced = cacheReplace(versioned, map);
                }
            }

        }


        private boolean cacheReplace(VersionedValue<Map<String, String>> oldMap, Map<String, String> newMap) {
            try {
                boolean replaced = remoteCache.replaceWithVersion("123", newMap, oldMap.getVersion());

                if (!replaced) {
                    failedReplaceCounter.incrementAndGet();
                }

                return replaced;
            } catch (Exception e) {
                failedReplaceCounter2.incrementAndGet();
                return false;
            }
        }

    }
}
