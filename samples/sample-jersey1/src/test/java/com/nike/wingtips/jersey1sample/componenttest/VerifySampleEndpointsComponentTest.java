package com.nike.wingtips.jersey1sample.componenttest;

import com.nike.internal.util.MapBuilder;
import com.nike.internal.util.Pair;
import com.nike.wingtips.Span;
import com.nike.wingtips.TraceHeaders;
import com.nike.wingtips.Tracer;
import com.nike.wingtips.jersey1sample.Main;
import com.nike.wingtips.jersey1sample.resource.SampleResource;
import com.nike.wingtips.lifecyclelistener.SpanLifecycleListener;
import com.nike.wingtips.tags.KnownZipkinTags;
import com.nike.wingtips.tags.WingtipsTags;

import com.tngtech.java.junit.dataprovider.DataProvider;
import com.tngtech.java.junit.dataprovider.DataProviderRunner;

import org.eclipse.jetty.server.Server;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import io.restassured.response.ExtractableResponse;

import static com.nike.wingtips.jersey1sample.resource.SampleResource.ASYNC_ERROR_PATH;
import static com.nike.wingtips.jersey1sample.resource.SampleResource.ASYNC_FORWARD_PATH;
import static com.nike.wingtips.jersey1sample.resource.SampleResource.ASYNC_PATH;
import static com.nike.wingtips.jersey1sample.resource.SampleResource.ASYNC_RESULT;
import static com.nike.wingtips.jersey1sample.resource.SampleResource.ASYNC_TIMEOUT_PATH;
import static com.nike.wingtips.jersey1sample.resource.SampleResource.BLOCKING_FORWARD_PATH;
import static com.nike.wingtips.jersey1sample.resource.SampleResource.BLOCKING_PATH;
import static com.nike.wingtips.jersey1sample.resource.SampleResource.BLOCKING_RESULT;
import static com.nike.wingtips.jersey1sample.resource.SampleResource.SIMPLE_PATH;
import static com.nike.wingtips.jersey1sample.resource.SampleResource.SIMPLE_RESULT;
import static com.nike.wingtips.jersey1sample.resource.SampleResource.SLEEP_TIME_MILLIS;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Component test that starts up the sample server and hits it with various requests and verifies that the expected
 * request spans are created/completed appropriately.
 *
 * @author Nic Munroe
 */
@RunWith(DataProviderRunner.class)
public class VerifySampleEndpointsComponentTest {

    private static final int SERVER_PORT = findFreePort();
    private static Server server;

    private SpanRecorder spanRecorder;

    @BeforeClass
    public static void beforeClass() throws Exception {
        server = Main.createServer(SERVER_PORT);
        server.start();
        for (int i = 0; i < 100; i++) {
            if (server.isStarted())
                return;
            Thread.sleep(100);
        }
        throw new IllegalStateException("Server is not up after waiting 10 seconds. Aborting tests.");
    }

    @AfterClass
    public static void afterClass() throws Exception {
        if (server != null) {
            server.stop();
            server.destroy();
        }
    }

    private static int findFreePort() {
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            return serverSocket.getLocalPort();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Before
    public void beforeMethod() {
        clearTracerSpanLifecycleListeners();

        spanRecorder = new SpanRecorder();
        Tracer.getInstance().addSpanLifecycleListener(spanRecorder);
    }

    @After
    public void afterMethod() {
        clearTracerSpanLifecycleListeners();
    }

    private void clearTracerSpanLifecycleListeners() {
        Tracer.getInstance().removeAllSpanLifecycleListeners();
    }

    @DataProvider(value = {
        "true",
        "false"
    })
    @Test
    public void verify_simple_endpoint_traced_correctly(boolean upstreamSendsSpan) {
        Pair<Span, Map<String, String>> upstreamSpanInfo = (upstreamSendsSpan)
                                                           ? generateUpstreamSpanHeaders()
                                                           : Pair.of((Span)null, Collections.<String, String>emptyMap());

        ExtractableResponse response =
            given()
                .baseUri("http://localhost")
                .port(SERVER_PORT)
                .headers(upstreamSpanInfo.getRight())
                .queryParam("foo", "bar")
                .log().all()
            .when()
                .get(SIMPLE_PATH)
            .then()
                .log().all()
                .extract();

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.asString()).isEqualTo(SIMPLE_RESULT);
        Span completedSpan =
            verifySingleSpanCompletedAndReturnedInResponse(response, 0, upstreamSpanInfo.getLeft());
        verifySpanNameAndTags(
            completedSpan,
            "GET",
            "GET",
            SIMPLE_PATH,
            "http://localhost:" + SERVER_PORT + SIMPLE_PATH + "?foo=bar",
            null,
            response.statusCode(),
            null,
            "servlet"
        );
    }

    @DataProvider(value = {
        "true",
        "false"
    })
    @Test
    public void verify_blocking_endpoint_traced_correctly(boolean upstreamSendsSpan) {
        Pair<Span, Map<String, String>> upstreamSpanInfo = (upstreamSendsSpan)
                                                           ? generateUpstreamSpanHeaders()
                                                           : Pair.of((Span)null, Collections.<String, String>emptyMap());

        ExtractableResponse response =
            given()
                .baseUri("http://localhost")
                .port(SERVER_PORT)
                .headers(upstreamSpanInfo.getRight())
                .queryParam("foo", "bar")
                .log().all()
            .when()
                .get(BLOCKING_PATH)
            .then()
                .log().all()
                .extract();

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.asString()).isEqualTo(BLOCKING_RESULT);
        Span completedSpan =
            verifySingleSpanCompletedAndReturnedInResponse(response, SLEEP_TIME_MILLIS, upstreamSpanInfo.getLeft());
        verifySpanNameAndTags(
            completedSpan,
            "GET",
            "GET",
            BLOCKING_PATH,
            "http://localhost:" + SERVER_PORT + BLOCKING_PATH + "?foo=bar",
            null,
            response.statusCode(),
            null,
            "servlet"
        );
    }

    @DataProvider(value = {
        "true",
        "false"
    })
    @Test
    public void verify_async_endpoint_traced_correctly(boolean upstreamSendsSpan) {
        Pair<Span, Map<String, String>> upstreamSpanInfo = (upstreamSendsSpan)
                                                           ? generateUpstreamSpanHeaders()
                                                           : Pair.of((Span)null, Collections.<String, String>emptyMap());

        ExtractableResponse response =
            given()
                .baseUri("http://localhost")
                .port(SERVER_PORT)
                .headers(upstreamSpanInfo.getRight())
                .queryParam("foo", "bar")
                .log().all()
            .when()
                .get(ASYNC_PATH)
            .then()
                .log().all()
                .extract();

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.asString()).isEqualTo(ASYNC_RESULT);
        Span completedSpan =
            verifySingleSpanCompletedAndReturnedInResponse(response, SLEEP_TIME_MILLIS, upstreamSpanInfo.getLeft());
        verifySpanNameAndTags(
            completedSpan,
            "GET",
            "GET",
            ASYNC_PATH,
            "http://localhost:" + SERVER_PORT + ASYNC_PATH + "?foo=bar",
            null,
            response.statusCode(),
            null,
            "servlet"
        );
    }

    @DataProvider(value = {
        "true",
        "false"
    })
    @Test
    public void verify_blocking_forward_endpoint_traced_correctly(boolean upstreamSendsSpan) {
        Pair<Span, Map<String, String>> upstreamSpanInfo = (upstreamSendsSpan)
                                                           ? generateUpstreamSpanHeaders()
                                                           : Pair.of((Span)null, Collections.<String, String>emptyMap());

        ExtractableResponse response =
            given()
                .baseUri("http://localhost")
                .port(SERVER_PORT)
                .headers(upstreamSpanInfo.getRight())
                .queryParam("foo", "bar")
                .log().all()
            .when()
                .get(BLOCKING_FORWARD_PATH)
            .then()
                .log().all()
                .extract();

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.asString()).isEqualTo(BLOCKING_RESULT);
        Span completedSpan =
            verifySingleSpanCompletedAndReturnedInResponse(response, SLEEP_TIME_MILLIS * 2, upstreamSpanInfo.getLeft());
        verifySpanNameAndTags(
            completedSpan,
            "GET",
            "GET",
            BLOCKING_FORWARD_PATH,
            "http://localhost:" + SERVER_PORT + BLOCKING_FORWARD_PATH + "?foo=bar",
            null,
            response.statusCode(),
            null,
            "servlet"
        );
    }

    @DataProvider(value = {
        "true",
        "false"
    })
    @Test
    public void verify_async_forward_endpoint_traced_correctly(boolean upstreamSendsSpan) {
        Pair<Span, Map<String, String>> upstreamSpanInfo = (upstreamSendsSpan)
                                                           ? generateUpstreamSpanHeaders()
                                                           : Pair.of((Span)null, Collections.<String, String>emptyMap());

        ExtractableResponse response =
            given()
                .baseUri("http://localhost")
                .port(SERVER_PORT)
                .headers(upstreamSpanInfo.getRight())
                .queryParam("foo", "bar")
                .log().all()
            .when()
                .get(ASYNC_FORWARD_PATH)
            .then()
                .log().all()
                .extract();

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.asString()).isEqualTo(ASYNC_RESULT);
        Span completedSpan =
            verifySingleSpanCompletedAndReturnedInResponse(response, SLEEP_TIME_MILLIS * 2, upstreamSpanInfo.getLeft());
        verifySpanNameAndTags(
            completedSpan,
            "GET",
            "GET",
            ASYNC_FORWARD_PATH,
            "http://localhost:" + SERVER_PORT + ASYNC_FORWARD_PATH + "?foo=bar",
            null,
            response.statusCode(),
            null,
            "servlet"
        );
    }

    @DataProvider(value = {
        "true",
        "false"
    })
    @Test
    public void verify_async_timeout_endpoint_traced_correctly(boolean upstreamSendsSpan) {
        Pair<Span, Map<String, String>> upstreamSpanInfo = (upstreamSendsSpan)
                                                           ? generateUpstreamSpanHeaders()
                                                           : Pair.of((Span)null, Collections.<String, String>emptyMap());

        ExtractableResponse response =
            given()
                .baseUri("http://localhost")
                .port(SERVER_PORT)
                .headers(upstreamSpanInfo.getRight())
                .queryParam("foo", "bar")
                .log().all()
                .when()
                .get(ASYNC_TIMEOUT_PATH)
                .then()
                .log().all()
                .extract();

        assertThat(response.statusCode()).isEqualTo(500);
        Span completedSpan =
            verifySingleSpanCompletedAndReturnedInResponse(response, SLEEP_TIME_MILLIS, upstreamSpanInfo.getLeft());
        verifySpanNameAndTags(
            completedSpan,
            "GET",
            "GET",
            ASYNC_TIMEOUT_PATH,
            "http://localhost:" + SERVER_PORT + ASYNC_TIMEOUT_PATH + "?foo=bar",
            null,
            response.statusCode(),
            // This is the exception message that Jetty happens to put on the TimeoutException that gets thrown.
            "Async API violation",
            "servlet"
        );
    }
    
    @DataProvider(value = {
        "true",
        "false"
    })
    @Test
    public void verify_async_error_endpoint_traced_correctly(boolean upstreamSendsSpan) {
        Pair<Span, Map<String, String>> upstreamSpanInfo = (upstreamSendsSpan)
                                                           ? generateUpstreamSpanHeaders()
                                                           : Pair.of((Span)null, Collections.<String, String>emptyMap());

        ExtractableResponse response =
            given()
                .baseUri("http://localhost")
                .port(SERVER_PORT)
                .headers(upstreamSpanInfo.getRight())
                .queryParam("foo", "bar")
                .log().all()
            .when()
                .get(ASYNC_ERROR_PATH)
            .then()
                .log().all()
                .extract();

        assertThat(response.statusCode()).isEqualTo(500);
        Span completedSpan =
            verifySingleSpanCompletedAndReturnedInResponse(response, SLEEP_TIME_MILLIS, upstreamSpanInfo.getLeft());
        verifySpanNameAndTags(
            completedSpan,
            "GET",
            "GET",
            ASYNC_ERROR_PATH,
            "http://localhost:" + SERVER_PORT + ASYNC_ERROR_PATH + "?foo=bar",
            null,
            response.statusCode(),
            SampleResource.SampleAsyncErrorServlet.EXCEPTION_MESSAGE,
            "servlet"
        );
    }

    private Pair<Span, Map<String, String>> generateUpstreamSpanHeaders() {
        Span span = Span.newBuilder("upstreamSpan", Span.SpanPurpose.CLIENT).build();
        Map<String, String> headers = MapBuilder
            .builder(TraceHeaders.TRACE_ID, span.getTraceId())
            .put(TraceHeaders.SPAN_ID, span.getSpanId())
            .put(TraceHeaders.SPAN_NAME, span.getSpanName())
            .put(TraceHeaders.TRACE_SAMPLED, String.valueOf(span.isSampleable()))
            .build();

        return Pair.of(span, headers);
    }

    private Span verifySingleSpanCompletedAndReturnedInResponse(ExtractableResponse response,
                                                                long expectedMinSpanDurationMillis,
                                                                Span expectedUpstreamSpan) {
        // We can have a race condition where the response is sent and we try to verify here before the servlet filter
        //      has had a chance to complete the span. Wait a few milliseconds to give the servlet filter time to
        //      finish.
        waitUntilSpanRecorderHasExpectedNumSpans(1);
        
        assertThat(spanRecorder.completedSpans).hasSize(1);
        Span completedSpan = spanRecorder.completedSpans.get(0);
        String traceIdFromResponse = response.header(TraceHeaders.TRACE_ID);
        assertThat(traceIdFromResponse).isNotNull();
        assertThat(completedSpan.getTraceId()).isEqualTo(traceIdFromResponse);

        assertThat(completedSpan.getSpanName()).doesNotContain("?");

        assertThat(TimeUnit.NANOSECONDS.toMillis(completedSpan.getDurationNanos()))
            .isGreaterThanOrEqualTo(expectedMinSpanDurationMillis);

        if (expectedUpstreamSpan != null) {
            assertThat(completedSpan.getTraceId()).isEqualTo(expectedUpstreamSpan.getTraceId());
            assertThat(completedSpan.getParentSpanId()).isEqualTo(expectedUpstreamSpan.getSpanId());
        }

        return completedSpan;
    }

    private void verifySpanNameAndTags(
        Span span,
        String expectedSpanName,
        String expectedHttpMethodTagValue,
        String expectedPathTagValue,
        String expectedUrlTagValue,
        String expectedHttpRouteTagValue,
        int expectedStatusCodeTagValue,
        String expectedErrorTagValue,
        String expectedSpanHandlerTagValue
    ) {
        assertThat(span.getSpanName()).isEqualTo(expectedSpanName);
        assertThat(span.getTags().get(KnownZipkinTags.HTTP_METHOD)).isEqualTo(expectedHttpMethodTagValue);
        assertThat(span.getTags().get(KnownZipkinTags.HTTP_PATH)).isEqualTo(expectedPathTagValue);
        assertThat(span.getTags().get(KnownZipkinTags.HTTP_URL)).isEqualTo(expectedUrlTagValue);
        assertThat(span.getTags().get(KnownZipkinTags.HTTP_ROUTE)).isEqualTo(expectedHttpRouteTagValue);
        assertThat(span.getTags().get(KnownZipkinTags.HTTP_STATUS_CODE))
            .isEqualTo(String.valueOf(expectedStatusCodeTagValue));
        assertThat(span.getTags().get(KnownZipkinTags.ERROR)).isEqualTo(expectedErrorTagValue);
        assertThat(span.getTags().get(WingtipsTags.SPAN_HANDLER)).isEqualTo(expectedSpanHandlerTagValue);
    }

    private void waitUntilSpanRecorderHasExpectedNumSpans(int expectedNumSpans) {
        long timeoutMillis = 5000;
        long startTimeMillis = System.currentTimeMillis();
        while (spanRecorder.completedSpans.size() < expectedNumSpans) {
            try {
                Thread.sleep(10);
            }
            catch (InterruptedException e) {
                throw new RuntimeException(e);
            }

            long timeSinceStart = System.currentTimeMillis() - startTimeMillis;
            if (timeSinceStart > timeoutMillis) {
                throw new RuntimeException(
                    "spanRecorder did not have the expected number of spans after waiting "
                    + timeoutMillis + " milliseconds"
                );
            }
        }
    }

    @SuppressWarnings("WeakerAccess")
    public static class SpanRecorder implements SpanLifecycleListener {

        public final List<Span> completedSpans = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void spanStarted(Span span) { }

        @Override
        public void spanSampled(Span span) { }

        @Override
        public void spanCompleted(Span span) {
            completedSpans.add(span);
        }
    }

}
