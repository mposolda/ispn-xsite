Infinispan Xsite
================

Test for the infinispan behaviour / probably bug. If there are 2 infinispan servers in different sites and with caches connected through
the SYNC "backup" to each other, then deadlock happen when there is an attempt to update same cache record (record of same key) concurrently
from both sites.


Setup
-----
1) Unpack jboss-datagrid-7.1.0-server.zip to some directory. Assume $JDG1_HOME

2) Edit $JDG1_HOME/standalone/configuration/clustered.xml

2.a) Add "xsite" channel in the JGroups subsystem:


````
<channels default="cluster">
    <channel name="cluster"/>
    <channel name="xsite" stack="tcp"/>
</channels>
````

2.b)

````
<stack name="udp">
    ...
    <relay site="LON">
        <remote-site name="NYC" channel="xsite"/>
    </relay>
</stack>
````

2.c) Replace MPING in "tcp" stack with TCPPING

````
<stack name="tcp">
    <transport type="TCP" socket-binding="jgroups-tcp"/>
    <protocol type="TCPPING">
        <property name="initial_hosts">
            localhost[8610],localhost[9610]"
        </property>
        <property name="ergonomics">
            false
        </property>
    </protocol>
    ...
````

2.d) Add "sessions" cache under "clustered" cache container in the infinispan subsystem

````
<replicated-cache-configuration name="sessions-cfg" mode="SYNC" start="EAGER" batching="false">
    <backups>
        <backup site="NYC" failure-policy="FAIL" strategy="SYNC" enabled="true"/>
    </backups>
</replicated-cache-configuration>

<replicated-cache name="sessions" configuration="sessions-cfg"/>
````


3) Start the server:

````
cd $JDG1_HOME/bin
./standalone.sh -c clustered.xml -Djava.net.preferIPv4Stack=true \
-Djboss.socket.binding.port-offset=1010 -Djboss.default.multicast.address=234.56.78.99 \
-Djboss.node.name=cache-server
````

4) Copy the server to $JDG2_HOME.

5) Then change the LON and NYC in the RELAY configuration:

````
    <relay site="NYC">
        <remote-site name="LON" channel="xsite"/>
    </relay>
````

and backups configuration

````
    <backups>
        <backup site="LON" failure-policy="FAIL" strategy="SYNC" enabled="true"/>
    </backups>
````

6) Start JDG2_HOME:

````
cd $JDG2_HOME/bin
./standalone.sh -c clustered.xml -Djava.net.preferIPv4Stack=true \
-Djboss.socket.binding.port-offset=2010 -Djboss.default.multicast.address=234.56.78.101 \
-Djboss.node.name=cache-server-2
````

Ensure that JDG1 has something like this in the log:

````
16:51:05,512 INFO  [org.infinispan.server.jgroups] (ViewHandler-8,_cache-server:LON) DGJGRP0101: Received new x-site view: [LON, NYC]
````

5) Run the test. On this project run:

````
mvn clean install -DskipTests=true
cd ispn-xsite-test
mvn test -Dtest=ConcurrentReplaceTest
````

EXPECTED:
- No deadlocks on the JDG side (Priority 1)

- Consistency with no write skews (Priority 2). Which means that Assert is passing

CURRENT RESULT:
- Deadlock because BackupSender transaction on both sites owns the lock for key "123", but BackupReceiver on both sites waiting for the lock for entry "123".
None of the parties in both sites can't continue. Also the Assert failing (consistency is not there).
The test usually takes around 80-100 seconds with 10 ITERATION_PER_WORKER. More info in the JIRA TODO including thread dumps.

- I've tried with non-transactional caches, but same (or similar) results

- In the JDG log, there are exceptions like:

````
17:00:58,574 ERROR [org.infinispan.transaction.impl.TransactionCoordinator] (HotRodServerHandler-8-13) ISPN000097: Error while processing a prepare in a single-phase transaction: The local cache sessions failed to backup data to the remote sites:
NYC: org.infinispan.util.concurrent.TimeoutException: Timed out after 10 seconds waiting for a response from NYC (sync, timeout=10000)

	at org.infinispan.xsite.BackupSenderImpl.processFailedResponses(BackupSenderImpl.java:227)
	at org.infinispan.xsite.BackupSenderImpl.processResponses(BackupSenderImpl.java:132)
	at org.infinispan.interceptors.xsite.BaseBackupInterceptor.lambda$processBackupResponse$0(BaseBackupInterceptor.java:61)
	at org.infinispan.interceptors.xsite.BaseBackupInterceptor$$Lambda$306/1187307446.accept(Unknown Source)
	at org.infinispan.interceptors.BaseAsyncInterceptor.invokeNextThenAccept(BaseAsyncInterceptor.java:108)
	at org.infinispan.interceptors.xsite.BaseBackupInterceptor.processBackupResponse(BaseBackupInterceptor.java:60)

````


Patch the JDG
=============

1) Stop both JDG and update cache-container in clustered.xml on both JDG. Add the `jndi-attribute` to it:

````
<cache-container name="clustered" default-cache="default" statistics="true" jndi-name="infinispan/clustered">
````

2) Run both JDG servers with same parameters as above.

3) Deploy the plugin to both running JDG containers.

````
cd ispn-xsite-plugin/
mvn clean install
cp target/ispn-xsite-plugin-0.1-SNAPSHOT.jar $JDG1_HOME/standalone/deployments/
cp target/ispn-xsite-plugin-0.1-SNAPSHOT.jar $JDG2_HOME/standalone/deployments
````

There should be message like this on both containers:

````
17:47:00,407 ERROR [stderr] (MSC service thread 1-8) Injecting custom interceptors to cache: sessions
17:47:00,412 ERROR [stderr] (MSC service thread 1-8) Decorated backupReceiver for site NYC
````

4) Run the test:

TODO: Results expected



