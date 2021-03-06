package org.jboss.sample;

import java.lang.reflect.Constructor;
import java.util.List;

import org.infinispan.Cache;
import org.infinispan.cache.impl.DecoratedCache;
import org.infinispan.commands.VisitableCommand;
import org.infinispan.configuration.cache.BackupConfiguration;
import org.infinispan.context.Flag;
import org.infinispan.xsite.BackupReceiver;
import org.infinispan.xsite.BackupReceiverRepository;
import org.infinispan.xsite.BackupReceiverRepositoryImpl;
import org.infinispan.xsite.BaseBackupReceiver;
import org.infinispan.xsite.statetransfer.XSiteStatePushCommand;
import org.infinispan.xsite.statetransfer.XSiteStateTransferControlCommand;

/**
 * Updates BackupReceiver to use ZERO_LOCK_ACQUISITION_TIMEOUT flag. This means that backup fails immediately if lock is owned by
 * someone else and it won't wait for it (that was the behaviour causing deadlocks)
 *
 *
 * @author <a href="mailto:mposolda@redhat.com">Marek Posolda</a>
 */
class BackupReceiverUpdate {

    static void updateBackupReceiver(Cache cache) {
        try {

            BackupReceiverRepository backupReceiverRepo = cache.getAdvancedCache().getComponentRegistry().getComponent(BackupReceiverRepository.class);

            List<BackupConfiguration> backupSites = cache.getAdvancedCache().getCacheConfiguration().sites().allBackups();

            for (BackupConfiguration backupCfg : backupSites) {
                String siteName = backupCfg.site();
                BackupReceiver origin = backupReceiverRepo.getBackupReceiver(siteName, "sessions");

                // Not using "instanceof" just because of deploy/undeploy (different classloader and hence instanceof would fail)
                if (origin.getClass().getName().endsWith("DecoratedBackupReceiver")) {
                    System.err.println("Skip decorating as it's decorated already");
                } else {
                    decorateBackupReceiver((BackupReceiverRepositoryImpl) backupReceiverRepo, origin, siteName);
                    System.err.println("Decorated backupReceiver for site " + siteName);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }


    private static void decorateBackupReceiver(BackupReceiverRepositoryImpl repo, BackupReceiver origin, String siteName) {
        BackupReceiver newReceiver = new BackupReceiverUpdate.DecoratedBackupReceiver(origin);
        repo.replace(siteName, "sessions", newReceiver);
    }


    static class DecoratedBackupReceiver implements BackupReceiver {

        private final BackupReceiver decorated;
        private volatile BaseBackupReceiver.BackupCacheUpdater decoratedSiteUpdater;

        public DecoratedBackupReceiver(BackupReceiver decorated) {
            this.decorated = decorated;
        }

        @Override
        public Cache getCache() {
            return decorated.getCache();
        }

        @Override
        public Object handleRemoteCommand(VisitableCommand command) throws Throwable {
            if (decoratedSiteUpdater == null) {
                synchronized (this) {
                    if (decoratedSiteUpdater == null) {
                        // We want BaseBackupReceiver to FAIL if it can't acquire lock immediately. This is to avoid deadlock as there can be
                        // an update in progress on local site too
                        Cache decoratedCache = new DecoratedCache(getCache().getAdvancedCache(), Flag.ZERO_LOCK_ACQUISITION_TIMEOUT);

                        Constructor<BaseBackupReceiver.BackupCacheUpdater> ctor = BaseBackupReceiver.BackupCacheUpdater.class.getDeclaredConstructor(Cache.class);
                        ctor.setAccessible(true);
                        decoratedSiteUpdater = ctor.newInstance(decoratedCache);
                    }
                }
            }

            return command.acceptVisitor(null, decoratedSiteUpdater);
        }

        @Override
        public void handleStateTransferControl(XSiteStateTransferControlCommand command) throws Exception {
            decorated.handleStateTransferControl(command);
        }

        @Override
        public void handleStateTransferState(XSiteStatePushCommand cmd) throws Exception {
            decorated.handleStateTransferState(cmd);
        }

    }
}
