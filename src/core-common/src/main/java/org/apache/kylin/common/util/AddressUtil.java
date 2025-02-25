/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.kylin.common.util;

import java.net.InetAddress;
import java.net.UnknownHostException;

import org.apache.commons.lang3.StringUtils;
import org.apache.kylin.common.KylinConfig;
import org.springframework.cloud.commons.util.InetUtils;
import org.springframework.cloud.commons.util.InetUtilsProperties;

import lombok.Setter;
import lombok.val;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class AddressUtil {

    public static String MAINTAIN_MODE_MOCK_PORT = "0000";
    private static String localIpAddressCache;
    @Setter
    private static HostInfoFetcher hostInfoFetcher = new DefaultHostInfoFetcher();

    public static String getLocalInstance() {
        String serverIp = getLocalHostExactAddress();
        return serverIp + ":" + KylinConfig.getInstanceFromEnv().getServerPort();
    }

    /**
     * refer org.springframework.cloud.zookeeper.discovery.ZookeeperDiscoveryProperties#getInstanceHost()
     */
    public static String getZkLocalInstance() {
        String hostname = hostInfoFetcher.getHostname();
        return hostname + ":" + KylinConfig.getInstanceFromEnv().getServerPort();
    }

    public static String convertHost(String serverHost) {
        String hostArr;
        val hostAndPort = serverHost.split(":");
        String host = hostAndPort[0];
        String port = hostAndPort[1];
        try {
            hostArr = InetAddress.getByName(host).getHostAddress() + ":" + port;
        } catch (UnknownHostException e) {
            hostArr = "127.0.0.1:" + port;
        }
        return hostArr;
    }

    public static String getMockPortAddress() {
        return getLocalInstance().split(":")[0] + ":" + MAINTAIN_MODE_MOCK_PORT;
    }

    public static String getHostName() {
        String hostName = "localhost";
        try {
            hostName = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            log.warn("use the InetAddress get host name failed!", e);
        }
        return hostName;
    }

    public static String getLocalServerInfo() {
        String hostName = getHostName();
        String host = hostName + "_" + KylinConfig.getInstanceFromEnv().getServerPort();
        return host.replaceAll("[^(_a-zA-Z0-9)]", "");
    }

    public static String getServerInfo(String hostName, String port) {
        String host = hostName + "_" + port;
        return host.replaceAll("[^(_a-zA-Z0-9)]", "");
    }

    public static String getLocalHostExactAddress() {
        if (StringUtils.isEmpty(localIpAddressCache)) {
            val localIpAddress = KylinConfig.getInstanceFromEnv().getServerIpAddress();
            if (StringUtils.isNotBlank(localIpAddress)) {
                localIpAddressCache = localIpAddress;
            } else {
                try (InetUtils inetUtils = new InetUtils(new InetUtilsProperties())) {
                    localIpAddressCache = inetUtils.findFirstNonLoopbackHostInfo().getIpAddress();
                }
            }
        }
        return localIpAddressCache;
    }

    public static boolean isSameHost(String driverHost) {
        try {
            val serverIp = AddressUtil.getLocalInstance().split(":")[0];
            val driverHostAddr = InetAddress.getByName(driverHost).getHostAddress();
            val keHost = AddressUtil.getZkLocalInstance().split(":")[0];
            return StringUtils.equals(driverHostAddr, serverIp) || driverHost.equals(keHost);
        } catch (UnknownHostException e) {
            log.warn("use the InetAddress get host name failed! ", e);
            return false;
        }
    }

    public static String concatInstanceName() {
        return AddressUtil.getLocalHostExactAddress() + ":" + KylinConfig.getInstanceFromEnv().getServerPort();
    }

    public static void clearLocalIpAddressCache() {
        localIpAddressCache = null;
    }

    public static void validateHost(String host) {
        if (StringUtils.isNotBlank(host) && !StringHelper.validateHost(host)) {
            throw new IllegalArgumentException("Url contains disallowed chars, host: " + host);
        }
    }
}
