/*
 * Copyright 2016 Sony Mobile Communications, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */
package lumbermill.internal.elasticsearch;

import com.squareup.okhttp.Dispatcher;
import lumbermill.api.Observables;
import lumbermill.internal.MapWrap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

import static java.util.Arrays.asList;


public class ElasticsearchClientFactory {

    private static final Logger LOGGER = LoggerFactory.getLogger(ElasticsearchClientFactory.class);
    private static final int DEFAULT_ATTEMPTS = 20;

    private final Map<String, ElasticSearchOkHttpClientImpl> cachedClients = new HashMap<>();

    public synchronized ElasticSearchOkHttpClientImpl ofParameters(MapWrap config) {
        config.assertExists("url", "type")
                .assertExistsAny("index", "index_prefix");
        return createClient(config);
    }

    private String cacheKey(String url, String index, boolean isPrefix) {
        return new StringBuilder().append(url).append(index).append(isPrefix).toString();
    }

    private ElasticSearchOkHttpClientImpl createClient(MapWrap config) {

        boolean isPrefix;
        String index;
        String url = config.asString("url");
        if (config.exists("index_prefix")) {
            isPrefix = true;
            index = config.asString("index_prefix");
        } else {
            isPrefix = false;
            index = config.asString("index");
        }

        String cacheKey = cacheKey(url, index, isPrefix);
        if (cachedClients.containsKey(cacheKey)) {
            LOGGER.trace("Using cached Elasticsearch client");
            return cachedClients.get(cacheKey);
        }
        LOGGER.trace("Creating new Elasticsearch client");
        final ElasticSearchOkHttpClientImpl es = new ElasticSearchOkHttpClientImpl (
                url,
                index,
                config.asString("type"),
                isPrefix);

        if (config.exists("document_id")) {
            es.withDocumentId(config.asString("document_id"));
        }

        if (config.exists("signer")) {
            es.withSigner(config.getObject("signer"));
        }

        if (config.exists("basic_auth")) {
            if (config.exists("signer")) {
                LOGGER.warn("A client cannot have both signed (AWS) and basic auth. Disabling basic auth");
            } else {
                String auth = config.asString("basic_auth");
                if (!auth.contains(":")) {
                    throw new IllegalArgumentException("Invalid basic_auth value, expected 'user:passwd' but was " + auth);
                }
                if (auth.length() > 1) {
                    String[] split = auth.split(":");
                    es.withBasicAuth(split[0], split[1]);
                }
            }
        }

        if (config.exists("timestamp_field")) {
            es.withTimestampField(config.asString("timestamp_field"));
        }

        if (config.exists("retry")) {
            MapWrap retryConfig = MapWrap.of(config.getObject("retry")).assertExists("policy");
            es.withRetryTimer(Observables.timer(retryConfig), retryConfig.asInt("attempts", DEFAULT_ATTEMPTS));
        }

        if (config.exists("dispatcher")) {
            LOGGER.info("Configuring http dispatcher");
            MapWrap dispatchConfig = MapWrap.of(config.getObject("dispatcher"));
            Dispatcher dispatcher = dispatchConfig.exists("threadpool")
                    ? new Dispatcher(dispatchConfig.getObject("threadpool"))
                    : new Dispatcher();
            dispatcher.setMaxRequests(dispatchConfig.exists("max_concurrent_requests")
                    ? dispatchConfig.asInt("max_concurrent_requests")
                    : dispatcher.getMaxRequests());
            dispatcher.setMaxRequestsPerHost(dispatchConfig.exists("max_concurrent_requests")
                    ? dispatchConfig.asInt("max_concurrent_requests")
                    : dispatcher.getMaxRequestsPerHost());
            es.withDispatcher(dispatcher);
        }

        cachedClients.put(cacheKey, es);

        return es;
    }
}
