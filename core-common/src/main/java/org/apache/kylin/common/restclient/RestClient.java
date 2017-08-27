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

package org.apache.kylin.common.restclient;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.io.IOUtils;
import org.apache.http.HttpResponse;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.client.utils.HttpClientUtils;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.params.BasicHttpParams;
import org.apache.http.params.HttpConnectionParams;
import org.apache.http.params.HttpParams;
import org.apache.http.util.EntityUtils;
import org.apache.kylin.common.util.JsonUtil;

import javax.xml.bind.DatatypeConverter;

/**
 * @author yangli9
 */
public class RestClient {

    protected String host;
    protected int port;
    protected String baseUrl;
    protected String userName;
    protected String password;
    protected DefaultHttpClient client;

    protected static Pattern fullRestPattern = Pattern.compile("(?:([^:]+)[:]([^@]+)[@])?([^:]+)(?:[:](\\d+))?");

    private static final int HTTP_CONNECTION_TIMEOUT_MS = 30000;
    private static final int HTTP_SOCKET_TIMEOUT_MS = 120000;

    public static final String SCHEME_HTTP = "http://";

    public static final String KYLIN_API_PATH = "/kylin/api";

    public static boolean matchFullRestPattern(String uri) {
        Matcher m = fullRestPattern.matcher(uri);
        return m.matches();
    }

    /**
     * @param uri
     *            "user:pwd@host:port"
     */
    public RestClient(String uri) {
        Matcher m = fullRestPattern.matcher(uri);
        if (!m.matches())
            throw new IllegalArgumentException("URI: " + uri + " -- does not match pattern " + fullRestPattern);

        String user = m.group(1);
        String pwd = m.group(2);
        String host = m.group(3);
        String portStr = m.group(4);
        int port = Integer.parseInt(portStr == null ? "7070" : portStr);

        init(host, port, user, pwd);
    }

    public RestClient(String host, int port, String userName, String password) {
        init(host, port, userName, password);
    }

    private void init(String host, int port, String userName, String password) {
        this.host = host;
        this.port = port;
        this.userName = userName;
        this.password = password;
        this.baseUrl = SCHEME_HTTP + host + ":" + port + KYLIN_API_PATH;

        final HttpParams httpParams = new BasicHttpParams();
        HttpConnectionParams.setSoTimeout(httpParams, HTTP_SOCKET_TIMEOUT_MS);
        HttpConnectionParams.setConnectionTimeout(httpParams, HTTP_CONNECTION_TIMEOUT_MS);

        client = new DefaultHttpClient(httpParams);

        if (userName != null && password != null) {
            CredentialsProvider provider = new BasicCredentialsProvider();
            UsernamePasswordCredentials credentials = new UsernamePasswordCredentials(userName, password);
            provider.setCredentials(AuthScope.ANY, credentials);
            client.setCredentialsProvider(provider);
        }
    }

    public void wipeCache(String entity, String event, String cacheKey) throws IOException {
        String url = baseUrl + "/cache/" + entity + "/" + cacheKey + "/" + event;
        HttpPut request = new HttpPut(url);

        HttpResponse response = null;
        try {
            response = client.execute(request);

            if (response.getStatusLine().getStatusCode() != 200) {
                String msg = EntityUtils.toString(response.getEntity());
                throw new IOException("Invalid response " + response.getStatusLine().getStatusCode() + " with cache wipe url " + url + "\n" + msg);
            }
        } finally {
            HttpClientUtils.closeQuietly(response);
            request.releaseConnection();
        }
    }

    public String getKylinProperties() throws IOException {
        String url = baseUrl + "/admin/config";
        HttpGet request = new HttpGet(url);

        HttpResponse response = null;
        try {
            response = client.execute(request);
            String msg = EntityUtils.toString(response.getEntity());
            Map<String, String> map = JsonUtil.readValueAsMap(msg);
            msg = map.get("config");

            if (response.getStatusLine().getStatusCode() != 200)
                throw new IOException("Invalid response " + response.getStatusLine().getStatusCode() + " with cache wipe url " + url + "\n" + msg);
            return msg;
        } finally {
            HttpClientUtils.closeQuietly(response);
            request.releaseConnection();
        }
    }

    public HashMap getCube(String cubeName) throws Exception {
        String url = baseUrl + "/cubes/" + cubeName;
        HttpGet get = newGet(url);
        get.setURI(new URI(url));
        HttpResponse response = null;
        try {
            response = client.execute(get);
            return dealResponse(response);
        } finally {
            HttpClientUtils.closeQuietly(response);
            get.releaseConnection();
        }

    }

    protected HashMap dealResponse(HttpResponse response) throws IOException {
        if (response.getStatusLine().getStatusCode() != 200) {
            throw new IOException("Invalid response " + response.getStatusLine().getStatusCode());
        }
        String result = getContent(response);
        HashMap resultMap = new ObjectMapper().readValue(result, HashMap.class);
        return resultMap;
    }

    protected void addHttpHeaders(HttpRequestBase method) {
        method.addHeader("Accept", "application/json, text/plain, */*");
        method.addHeader("Content-Type", "application/json");
        String basicAuth = DatatypeConverter.printBase64Binary((this.userName + ":" + this.password).getBytes());
        method.addHeader("Authorization", "Basic " + basicAuth);
    }

    protected HttpPost newPost(String url) {
        HttpPost post = new HttpPost(url);
        addHttpHeaders(post);
        return post;
    }

    protected HttpPut newPut(String url) {
        HttpPut put = new HttpPut(url);
        addHttpHeaders(put);
        return put;
    }

    protected HttpGet newGet(String url) {
        HttpGet get = new HttpGet();
        addHttpHeaders(get);
        return get;
    }

    protected String getContent(HttpResponse response) throws IOException {
        InputStreamReader reader = null;
        BufferedReader rd = null;
        StringBuffer result = new StringBuffer();
        try {
            reader = new InputStreamReader(response.getEntity().getContent());
            rd = new BufferedReader(reader);
            String line = null;
            while ((line = rd.readLine()) != null) {
                result.append(line);
            }
        } finally {
            IOUtils.closeQuietly(reader);
            IOUtils.closeQuietly(rd);
        }
        return result.toString();
    }

}
