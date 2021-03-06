/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.bootstrap;

import org.apache.lucene.util.Constants;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.BoundTransportAddress;
import org.elasticsearch.common.transport.TransportAddress;
import org.elasticsearch.discovery.zen.elect.ElectMasterService;
import org.elasticsearch.monitor.jvm.JvmInfo;
import org.elasticsearch.monitor.process.ProcessProbe;
import org.elasticsearch.node.Node;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * We enforce limits once any network host is configured. In this case we assume the node is running in production
 * and all production limit checks must pass. This should be extended as we go to settings like:
 * - discovery.zen.ping.unicast.hosts is set if we use zen disco
 * - ensure we can write in all data directories
 * - fail if vm.max_map_count is under a certain limit (not sure if this works cross platform)
 * - fail if the default cluster.name is used, if this is setup on network a real clustername should be used?
 */
final class BootstrapCheck {

    private BootstrapCheck() {
    }

    /**
     * checks the current limits against the snapshot or release build
     * checks
     *
     * @param settings              the current node settings
     * @param boundTransportAddress the node network bindings
     */
    static void check(final Settings settings, final BoundTransportAddress boundTransportAddress) {
        check(enforceLimits(boundTransportAddress), checks(settings), Node.NODE_NAME_SETTING.get(settings));
    }

    /**
     * executes the provided checks and fails the node if
     * enforceLimits is true, otherwise logs warnings
     *
     * @param enforceLimits true if the checks should be enforced or
     *                      warned
     * @param checks        the checks to execute
     * @param nodeName      the node name to be used as a logging prefix
     */
    // visible for testing
    static void check(final boolean enforceLimits, final List<Check> checks, final String nodeName) {
        final ESLogger logger = Loggers.getLogger(BootstrapCheck.class, nodeName);

        final List<String> errors =
                checks.stream()
                        .filter(BootstrapCheck.Check::check)
                        .map(BootstrapCheck.Check::errorMessage)
                        .collect(Collectors.toList());

        if (!errors.isEmpty()) {
            final List<String> messages = new ArrayList<>(1 + errors.size());
            messages.add("bootstrap checks failed");
            messages.addAll(errors);
            if (enforceLimits) {
                final RuntimeException re = new RuntimeException(String.join("\n", messages));
                errors.stream().map(IllegalStateException::new).forEach(re::addSuppressed);
                throw re;
            } else {
                messages.forEach(message -> logger.warn(message));
            }
        }
    }

    /**
     * Tests if the checks should be enforced
     *
     * @param boundTransportAddress the node network bindings
     * @return true if the checks should be enforced
     */
    // visible for testing
    static boolean enforceLimits(BoundTransportAddress boundTransportAddress) {
        return !(Arrays.stream(boundTransportAddress.boundAddresses()).allMatch(TransportAddress::isLoopbackOrLinkLocalAddress) &&
                boundTransportAddress.publishAddress().isLoopbackOrLinkLocalAddress());
    }

    // the list of checks to execute
    static List<Check> checks(final Settings settings) {
        final List<Check> checks = new ArrayList<>();
        checks.add(new HeapSizeCheck());
        final FileDescriptorCheck fileDescriptorCheck
            = Constants.MAC_OS_X ? new OsXFileDescriptorCheck() : new FileDescriptorCheck();
        checks.add(fileDescriptorCheck);
        checks.add(new MlockallCheck(BootstrapSettings.MLOCKALL_SETTING.get(settings)));
        if (Constants.LINUX) {
            checks.add(new MaxNumberOfThreadsCheck());
        }
        if (Constants.LINUX || Constants.MAC_OS_X) {
            checks.add(new MaxSizeVirtualMemoryCheck());
        }
        checks.add(new MinMasterNodesCheck(ElectMasterService.DISCOVERY_ZEN_MINIMUM_MASTER_NODES_SETTING.exists(settings)));
        return Collections.unmodifiableList(checks);
    }

    /**
     * Encapsulates a limit check
     */
    interface Check {

        /**
         * test if the node fails the check
         *
         * @return true if the node failed the check
         */
        boolean check();

        /**
         * the message for a failed check
         *
         * @return the error message on check failure
         */
        String errorMessage();

    }

    static class HeapSizeCheck implements BootstrapCheck.Check {

        @Override
        public boolean check() {
            final long initialHeapSize = getInitialHeapSize();
            final long maxHeapSize = getMaxHeapSize();
            return initialHeapSize != 0 && maxHeapSize != 0 && initialHeapSize != maxHeapSize;
        }

        @Override
        public String errorMessage() {
            return String.format(
                    Locale.ROOT,
                    "initial heap size [%d] not equal to maximum heap size [%d]; " +
                            "this can cause resize pauses and prevents mlockall from locking the entire heap",
                    getInitialHeapSize(),
                    getMaxHeapSize()
            );
        }

        // visible for testing
        long getInitialHeapSize() {
            return JvmInfo.jvmInfo().getConfiguredInitialHeapSize();
        }

        // visible for testing
        long getMaxHeapSize() {
            return JvmInfo.jvmInfo().getConfiguredMaxHeapSize();
        }

    }

    static class OsXFileDescriptorCheck extends FileDescriptorCheck {

        public OsXFileDescriptorCheck() {
            // see constant OPEN_MAX defined in
            // /usr/include/sys/syslimits.h on OS X and its use in JVM
            // initialization in int os:init_2(void) defined in the JVM
            // code for BSD (contains OS X)
            super(10240);
        }

    }

    // visible for testing
    static class FileDescriptorCheck implements Check {

        private final int limit;

        FileDescriptorCheck() {
            this(1 << 16);
        }

        protected FileDescriptorCheck(final int limit) {
            if (limit <= 0) {
                throw new IllegalArgumentException("limit must be positive but was [" + limit + "]");
            }
            this.limit = limit;
        }

        public final boolean check() {
            final long maxFileDescriptorCount = getMaxFileDescriptorCount();
            return maxFileDescriptorCount != -1 && maxFileDescriptorCount < limit;
        }

        @Override
        public final String errorMessage() {
            return String.format(
                Locale.ROOT,
                "max file descriptors [%d] for elasticsearch process likely too low, increase to at least [%d]",
                getMaxFileDescriptorCount(),
                limit
            );
        }

        // visible for testing
        long getMaxFileDescriptorCount() {
            return ProcessProbe.getInstance().getMaxFileDescriptorCount();
        }

    }

    // visible for testing
    static class MlockallCheck implements Check {

        private final boolean mlockallSet;

        public MlockallCheck(final boolean mlockAllSet) {
            this.mlockallSet = mlockAllSet;
        }

        @Override
        public boolean check() {
            return mlockallSet && !isMemoryLocked();
        }

        @Override
        public String errorMessage() {
            return "memory locking requested for elasticsearch process but memory is not locked";
        }

        // visible for testing
        boolean isMemoryLocked() {
            return Natives.isMemoryLocked();
        }

    }

    static class MinMasterNodesCheck implements Check {

        final boolean minMasterNodesIsSet;

        MinMasterNodesCheck(boolean minMasterNodesIsSet) {
            this.minMasterNodesIsSet = minMasterNodesIsSet;
        }

        @Override
        public boolean check() {
            return minMasterNodesIsSet == false;
        }

        @Override
        public String errorMessage() {
            return "please set [" + ElectMasterService.DISCOVERY_ZEN_MINIMUM_MASTER_NODES_SETTING.getKey() +
                "] to a majority of the number of master eligible nodes in your cluster.";
        }
    }

    static class MaxNumberOfThreadsCheck implements Check {

        private final long maxNumberOfThreadsThreshold = 1 << 11;

        @Override
        public boolean check() {
            return getMaxNumberOfThreads() != -1 && getMaxNumberOfThreads() < maxNumberOfThreadsThreshold;
        }

        @Override
        public String errorMessage() {
            return String.format(
                Locale.ROOT,
                "max number of threads [%d] for user [%s] likely too low, increase to at least [%d]",
                getMaxNumberOfThreads(),
                BootstrapInfo.getSystemProperties().get("user.name"),
                maxNumberOfThreadsThreshold);
        }

        // visible for testing
        long getMaxNumberOfThreads() {
            return JNANatives.MAX_NUMBER_OF_THREADS;
        }

    }

    static class MaxSizeVirtualMemoryCheck implements Check {

        @Override
        public boolean check() {
            return getMaxSizeVirtualMemory() != Long.MIN_VALUE && getMaxSizeVirtualMemory() != getRlimInfinity();
        }

        @Override
        public String errorMessage() {
            return String.format(
                Locale.ROOT,
                "max size virtual memory [%d] for user [%s] likely too low, increase to [unlimited]",
                getMaxSizeVirtualMemory(),
                BootstrapInfo.getSystemProperties().get("user.name"));
        }

        // visible for testing
        long getRlimInfinity() {
            return JNACLibrary.RLIM_INFINITY;
        }

        // visible for testing
        long getMaxSizeVirtualMemory() {
            return JNANatives.MAX_SIZE_VIRTUAL_MEMORY;
        }

    }

}
