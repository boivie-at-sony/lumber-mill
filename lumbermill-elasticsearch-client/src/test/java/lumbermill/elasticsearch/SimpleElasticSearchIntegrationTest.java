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
package lumbermill.elasticsearch;

import lumbermill.api.JsonEvent;
import lumbermill.internal.MapWrap;
import lumbermill.internal.elasticsearch.ElasticSearchOkHttpClientImpl;
import org.junit.Test;
import org.mockito.Mockito;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static java.time.ZonedDateTime.now;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.when;

public class SimpleElasticSearchIntegrationTest extends AbstractElasticSearchTest {

    @Test
    public void test_index_100_documents_not_timestamped_index() throws InterruptedException {

        String indexName = UUID.randomUUID().toString();
        bulkClient(MapWrap.of("index", indexName, "retry", MapWrap.of("policy", "linear").toMap()))
                .post (simpleEventsOfSize(100, true))
                .doOnNext( elasticSearchBulkResponseEvent-> assertThat(elasticSearchBulkResponseEvent.count()).isEqualTo(100))
                .doOnNext(elasticSearchBulkResponseEvent ->
                        await().atMost(3, TimeUnit.SECONDS).until(hitCountIs(100)))
                .doOnNext(response -> assertThat(response.indexNames()).containsOnly(indexName))
                // sanity, verify that original Json Events are returned properly

                .flatMap(response -> response.arguments())
                .doOnNext(event -> assertThat(event.valueAsString("message")).isEqualTo("Hello mighty mouse"))
                .toBlocking()
                .subscribe();
    }

    @Test
    public void test_index_100_documents_verify_bug_where_incorrect_result_is_returned() throws InterruptedException {

        String indexName = UUID.randomUUID().toString();
        bulkClient(MapWrap.of("index", indexName))
                .post (simpleEventsOfSize(100, false))
                .doOnNext( elasticSearchBulkResponseEvent-> assertThat(elasticSearchBulkResponseEvent.count())
                        // With this bug this will be less then we want
                        .isLessThan(100))
                .toBlocking()
                .subscribe();
    }

    @Test
    public void test_index_100_documents_timestamped() throws InterruptedException {
        String indexName = UUID.randomUUID().toString() + "-";
        bulkClient(MapWrap.of("index_prefix", indexName)).post (simpleEventsOfSize(100, true))
                .doOnNext(elasticSearchBulkResponseEvent ->
                        await().atMost(3, TimeUnit.SECONDS).until(hitCountIs(100)))
                .doOnNext(response -> assertThat(response.indexNames())
                        .containsOnly(indexName + now().format(DateTimeFormatter.ofPattern("yyyy.MM.dd"))))
                .toBlocking()
                .subscribe();
    }

    @Test
    public void test_index_100_documents_twice_with_document_id_becomes_update() throws InterruptedException {

        // We must use same events and clients in both bulk requests
        List<JsonEvent> events = simpleEventsOfSizeAndRandomField(100, "uuid");

        String indexName = UUID.randomUUID().toString() + "-";
        // Use uuid field as _idMapWrap.of("index_prefix", indexName)
        ElasticSearchOkHttpClientImpl bulkClient = bulkClient(MapWrap.of(
                "document_id", "{uuid}",
                "index_prefix", indexName));



        bulkClient.post (events)
                .doOnNext( response ->
                        await().atMost(3, TimeUnit.SECONDS).until(hitCountIs(100)))
                 .doOnNext(responseEvent ->
                        assertThat(responseEvent.types()).containsOnly("index"))
                .doOnNext(responseEvent ->
                        assertThat(responseEvent.versions()).containsOnly("1"))
                .doOnNext(response -> assertThat(response.indexNames())
                        .containsOnly(indexName + now().format(DateTimeFormatter.ofPattern("yyyy.MM.dd"))))
                .toBlocking()
                .subscribe();

        bulkClient.post (events)
                .doOnNext(responseEvent ->
                        assertThat(responseEvent.types()).containsOnly("index"))
                .doOnNext(responseEvent ->
                        assertThat(responseEvent.versions()).containsOnly("2"))
                .toBlocking()
                .subscribe();

        // Since we cannot verify count without sleep above, add 1 to verify we get 101.
        bulkClient.post(simpleEventsOfSizeAndRandomField(1,"uuid")).doOnNext( response ->
                    await().atMost(3, TimeUnit.SECONDS).until(hitCountIs(101)))
                .subscribe();
    }

    @Test
    public void test_dispatcher_config_settings() {
        ExecutorService mock = Mockito.mock(ExecutorService.class);
        when(mock.submit(any(Runnable.class))).thenThrow(IllegalStateException.class);
        ElasticSearchOkHttpClientImpl elasticSearchOkHttpClient = bulkClient(
                MapWrap.of( "index_prefix", "index-",
                        "retry", MapWrap.of("policy", "linear", "delayMs", 100).toMap(),
                        "dispatcher", MapWrap.of("threadpool", mock, "max_concurrent_requests", 1).toMap())
        );
        assertThat(elasticSearchOkHttpClient.client().getDispatcher().getMaxRequests()).isEqualTo(1);
        assertThat(elasticSearchOkHttpClient.client().getDispatcher().getMaxRequestsPerHost()).isEqualTo(1);
        assertThatExceptionOfType(IllegalStateException.class).isThrownBy(() ->
               elasticSearchOkHttpClient.client().getDispatcher().getExecutorService().submit((Runnable) () -> {}));
    }
}
