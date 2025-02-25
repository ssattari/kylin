--
-- Licensed to the Apache Software Foundation (ASF) under one
-- or more contributor license agreements.  See the NOTICE file
-- distributed with this work for additional information
-- regarding copyright ownership.  The ASF licenses this file
-- to you under the Apache License, Version 2.0 (the
-- "License"); you may not use this file except in compliance
-- with the License.  You may obtain a copy of the License at
--
--     http://www.apache.org/licenses/LICENSE-2.0
--
-- Unless required by applicable law or agreed to in writing, software
-- distributed under the License is distributed on an "AS IS" BASIS,
-- WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
-- See the License for the specific language governing permissions and
-- limitations under the License.
--

SELECT
    count(1)
FROM
    (SELECT
         1
     FROM
         (SELECT
              sum(SELLER_ID) as acct_bal_CURR,
              1 AS DT
          FROM
              TEST_KYLIN_FACT) a
             left JOIN
         (
             SELECT
                 sum(ITEM_COUNT) acct_id_CURR,
                 1 AS DT
             FROM
                 TEST_KYLIN_FACT
         ) b
         ON a.DT = b.DT
    )