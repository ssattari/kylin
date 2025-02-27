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

package org.apache.kylin.rest.controller;

import static org.apache.kylin.common.constant.HttpConstant.HTTP_VND_APACHE_KYLIN_JSON;
import static org.apache.kylin.common.constant.HttpConstant.HTTP_VND_APACHE_KYLIN_V4_PUBLIC_JSON;

import java.io.IOException;

import org.apache.kylin.common.exception.KylinException;
import org.apache.kylin.common.persistence.transaction.BroadcastEventReadyNotifier;
import org.apache.kylin.rest.config.initialize.BroadcastListener;
import org.apache.kylin.rest.response.EnvelopeResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
@RequestMapping(value = "/api/broadcast", produces = { HTTP_VND_APACHE_KYLIN_JSON,
        HTTP_VND_APACHE_KYLIN_V4_PUBLIC_JSON })
public class BroadcastController extends NBasicController {

    @Autowired
    private BroadcastListener localHandler;

    @PostMapping(value = "")
    @ResponseBody
    public EnvelopeResponse<String> broadcastReceive(@RequestBody BroadcastEventReadyNotifier notifier)
            throws IOException {
        localHandler.handle(notifier);
        return new EnvelopeResponse<>(KylinException.CODE_SUCCESS, "", "");
    }

    @PutMapping(value = "/capacity/refresh_all")
    @ResponseBody
    public EnvelopeResponse<String> innerRefreshAll() {
        return new EnvelopeResponse(KylinException.CODE_SUCCESS, "", "");
    }
}
