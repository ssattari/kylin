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
package org.apache.kylin.rest.response;

import java.io.Serializable;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.apache.kylin.common.exception.KylinException;
import org.apache.kylin.job.exception.JobSubmissionException;
import org.apache.kylin.metadata.cube.model.NDataSegment;
import org.apache.kylin.metadata.cube.model.NDataflow;
import org.apache.kylin.metadata.model.Segments;

import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Data;

@Data
public class JobInfoResponseWithFailure extends JobInfoResponse {

    @JsonProperty("failed_segments")
    List<FailedSegmentJobWithReason> failedSegments = new LinkedList<>();

    public void addFailedSeg(NDataflow dataflow, JobSubmissionException jobSubmissionException) {
        Segments<NDataSegment> segments = dataflow.getSegments();
        for (Map.Entry<String, KylinException> entry : jobSubmissionException.getSegmentFailInfos().entrySet()) {
            String segId = entry.getKey();
            KylinException kylinException = entry.getValue();

            FailedSegmentJobWithReason failedSeg = new FailedSegmentJobWithReason(dataflow, segments,
                    dataflow.getSegment(segId));
            String code = getErrorCode(kylinException);
            Error errorInfo = new Error(code, kylinException.getMessage());
            failedSeg.setError(errorInfo);

            failedSegments.add(failedSeg);
        }
    }

    private String getErrorCode(KylinException kylinException) {
        String code = null;
        if (null != kylinException.getErrorCode()) {
            code = kylinException.getErrorCode().getCodeString();
        } else if (null != kylinException.getErrorCodeProducer()) {
            code = kylinException.getErrorCodeProducer().getErrorCode().getCode();
        }
        return code;
    }

    @Data
    public static class FailedSegmentJobWithReason extends NDataSegmentResponse {

        public FailedSegmentJobWithReason(NDataflow dataflow, Segments<NDataSegment> segments, NDataSegment segment) {
            super(dataflow, segments, segment);
        }

        @JsonProperty("error")
        private Error error;

    }

    @Data
    public static class Error implements Serializable {

        public Error(String code, String msg) {
            this.code = code;
            this.msg = msg;
        }

        @JsonProperty("code")
        private String code;

        @JsonProperty("msg")
        private String msg;

    }

}
