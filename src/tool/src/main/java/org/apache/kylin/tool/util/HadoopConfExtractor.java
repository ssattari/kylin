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

package org.apache.kylin.tool.util;

import java.lang.reflect.Method;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.yarn.conf.HAUtil;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.apache.hadoop.yarn.util.RMHAUtils;
import org.apache.kylin.common.KylinConfig;
import org.apache.kylin.common.exception.KylinRuntimeException;
import org.apache.kylin.guava30.shaded.common.base.Preconditions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HadoopConfExtractor {
    private static final Logger logger = LoggerFactory.getLogger(HadoopConfExtractor.class);
    public static final Pattern URL_PATTERN = Pattern.compile("(http[s]?://)([^:]*):([^/]*).*");

    private HadoopConfExtractor() {
    }

    public static String extractYarnMasterUrl(Configuration conf) {
        KylinConfig config = KylinConfig.getInstanceFromEnv();
        final String yarnStatusCheckUrl = config.getYarnStatusCheckUrl();
        if (yarnStatusCheckUrl != null) {
            Matcher m = URL_PATTERN.matcher(yarnStatusCheckUrl);
            if (m.matches()) {
                return m.group(1) + m.group(2) + ":" + m.group(3);
            }
        }

        logger.info("kylin.job.yarn-app-rest-check-status-url is not set, read from hadoop configuration");

        String webappConfKey;
        String defaultAddr;
        if (YarnConfiguration.useHttps(conf)) {
            webappConfKey = YarnConfiguration.RM_WEBAPP_HTTPS_ADDRESS;
            defaultAddr = YarnConfiguration.DEFAULT_RM_WEBAPP_HTTPS_ADDRESS;
        } else {
            webappConfKey = YarnConfiguration.RM_WEBAPP_ADDRESS;
            defaultAddr = YarnConfiguration.DEFAULT_RM_WEBAPP_ADDRESS;
        }

        String active;
        String rmWebHost;
        if (HAUtil.isHAEnabled(conf)) {
            YarnConfiguration yarnConf = new YarnConfiguration(conf);
            try {
                active = RMHAUtils.findActiveRMHAId(yarnConf);
            } catch (NoSuchMethodError e) {
                logger.warn("Original findActiveRMHAId(YarnConfiguration) may not exists, try MRS modified method");
                active = tryInvokeMRSFindActiveRMHAId(conf);
            }

            rmWebHost = HAUtil.getConfValueForRMInstance(HAUtil.addSuffix(webappConfKey, active), defaultAddr,
                    yarnConf);
        } else {
            rmWebHost = HAUtil.getConfValueForRMInstance(webappConfKey, defaultAddr, conf);
        }

        if (StringUtils.isEmpty(rmWebHost)) {
            return null;
        }
        if (!rmWebHost.startsWith("http://") && !rmWebHost.startsWith("https://")) {
            rmWebHost = (YarnConfiguration.useHttps(conf) ? "https://" : "http://") + rmWebHost;
        }
        Matcher m = URL_PATTERN.matcher(rmWebHost);
        Preconditions.checkArgument(m.matches(), "Yarn master URL not found.");
        logger.info("yarn master url: {} ", rmWebHost);
        return rmWebHost;
    }

    private static String tryInvokeMRSFindActiveRMHAId(Configuration conf) {
        try {
            Method method = RMHAUtils.class.getMethod("findActiveRMHAId", Configuration.class);
            return (String) method.invoke(null, conf);
        } catch (Exception e) {
            throw new KylinRuntimeException(e);
        }
    }


}
