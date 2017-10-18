package org.jboss.sample;

import java.io.Serializable;
import java.lang.reflect.Constructor;
import java.util.List;

import org.infinispan.Cache;
import org.infinispan.cache.impl.DecoratedCache;
import org.infinispan.commands.VisitableCommand;
import org.infinispan.configuration.cache.BackupConfiguration;
import org.infinispan.context.Flag;
import org.infinispan.filter.NamedFactory;
import org.infinispan.manager.EmbeddedCacheManager;
import org.infinispan.metadata.Metadata;
import org.infinispan.notifications.cachelistener.filter.CacheEventFilter;
import org.infinispan.notifications.cachelistener.filter.CacheEventFilterFactory;
import org.infinispan.notifications.cachelistener.filter.EventType;
import org.infinispan.xsite.BackupReceiver;
import org.infinispan.xsite.BackupReceiverRepository;
import org.infinispan.xsite.BackupReceiverRepositoryImpl;
import org.infinispan.xsite.BaseBackupReceiver;
import org.infinispan.xsite.statetransfer.XSiteStatePushCommand;
import org.infinispan.xsite.statetransfer.XSiteStateTransferControlCommand;

/**
 * NOTE: We don't need any CacheEventFilterFactory. This is here just to tweak the infinispan BackupReceiver and interceptors.
 *
 * TODO: Find better way to tweak things if possible (or rather fix them in Infinispan :)
 *
 * @author <a href="mailto:mposolda@redhat.com">Marek Posolda</a>
 */
@NamedFactory(name = "basic-filter-factory")
public class BasicCacheFilterFactory implements CacheEventFilterFactory {

    public BasicCacheFilterFactory() {
        try {
            // TODO: is it some JNDI available OOTB without a need to add "jndi-name" to the cache-container element in clustered.xml file?
            EmbeddedCacheManager cacheMgr = (EmbeddedCacheManager) new javax.naming.InitialContext().lookup("java:jboss/infinispan/clustered");

            Cache sessions = cacheMgr.getCache("sessions");

            InterceptorInject.checkInterceptors(sessions);

            BackupReceiverUpdate.updateBackupReceiver(sessions);
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }
    }



    @Override
    public CacheEventFilter<String, Object> getFilter(final Object[] params) {
        return new BasicKeyValueFilter();
    }

    static class BasicKeyValueFilter implements CacheEventFilter<String, Object>, Serializable {

        @Override
        public boolean accept(String key, Object oldValue, Metadata oldMetadata, Object newValue, Metadata newMetadata, EventType eventType) {
            return true;
        }
    }
}
