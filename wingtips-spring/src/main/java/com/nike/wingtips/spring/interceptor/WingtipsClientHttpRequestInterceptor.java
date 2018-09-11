package com.nike.wingtips.spring.interceptor;

import static com.nike.wingtips.spring.util.WingtipsSpringUtil.getRequestMethodAsString;
import static com.nike.wingtips.spring.util.WingtipsSpringUtil.propagateTracingHeaders;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.RestTemplate;

import com.nike.wingtips.Span;
import com.nike.wingtips.Span.SpanPurpose;
import com.nike.wingtips.Tracer;
import com.nike.wingtips.http.HttpRequestTracingUtils;
import com.nike.wingtips.spring.interceptor.tag.SpringHttpClientTagAdapter;
import com.nike.wingtips.spring.util.HttpRequestWrapperWithModifiableHeaders;
import com.nike.wingtips.tags.HttpTagStrategy;
import com.nike.wingtips.tags.OpenTracingTagStrategy;

/**
 * A {@link ClientHttpRequestInterceptor} which propagates Wingtips tracing information on a downstream {@link
 * RestTemplate} call's request headers, with an option to surround downstream calls in a subspan. The subspan option
 * defaults to on and is highly recommended since the subspans will provide you with timing info for your downstream
 * calls separate from any parent span that may be active at the time this interceptor executes.
 *
 * <p>If the subspan option is enabled but there's no current span on the current thread when this interceptor executes,
 * then a new root span (new trace) will be created rather than a subspan. In either case the newly created span will
 * have a {@link Span#getSpanPurpose()} of {@link SpanPurpose#CLIENT} since this interceptor is for a client call.
 * The {@link Span#getSpanName()} for the newly created span will be generated by {@link
 * #getSubspanSpanName(HttpRequest)} - override that method if you want a different span naming format.
 *
 * <p>Note that if you have the subspan option turned off then this interceptor will propagate the {@link
 * Tracer#getCurrentSpan()}'s tracing info downstream if it's available, but will do nothing if no current span exists
 * on the current thread when this interceptor executes as there's no tracing info to propagate. Turning on the
 * subspan option mitigates this as it guarantees there will be a span to propagate.
 *
 * <p>Since this interceptor works by setting request headers and we may be passed an immutable request, we wrap
 * the request in a {@link HttpRequestWrapperWithModifiableHeaders} to guarantee that the request headers are mutable.
 * Keep in mind that this will make the headers mutable for any interceptors that execute after this one.
 */
@SuppressWarnings("WeakerAccess")
public class WingtipsClientHttpRequestInterceptor implements ClientHttpRequestInterceptor {

    private final Logger logger = LoggerFactory.getLogger(this.getClass());
    
    /**
     * The default implementation of this class. Since this class is thread-safe you can reuse this rather than creating
     * a new object.
     */
    public static final WingtipsClientHttpRequestInterceptor DEFAULT_IMPL = new WingtipsClientHttpRequestInterceptor();

    /**
     * If this is true then all downstream calls that this interceptor intercepts will be surrounded by a
     * subspan which will be started immediately before the call and completed as soon as the call completes.
     */
    protected final boolean surroundCallsWithSubspan;

    private HttpTagStrategy<HttpRequest, ClientHttpResponse> tagStrategy;
    
    /**
     * Default constructor - sets {@link #surroundCallsWithSubspan} to true.
     */
    public WingtipsClientHttpRequestInterceptor() {
        this(true);
    }

    /**
     * Constructor that lets you choose whether downstream calls will be surrounded with a subspan.
     * 
     * Uses a default span tag strategy that implements OpenTracing tags
     *
     * @param surroundCallsWithSubspan pass in true to have downstream calls surrounded with a new span, false to only
     * propagate the current span's info downstream (no subspan).
     */
    public WingtipsClientHttpRequestInterceptor(boolean surroundCallsWithSubspan) {
        this(surroundCallsWithSubspan, new OpenTracingTagStrategy<HttpRequest, ClientHttpResponse>(new SpringHttpClientTagAdapter()));
    }

    /**
     * Constructor that lets you choose whether downstream calls will be surrounded with a subspan and supply the relevant tag strategy
     * for the subspan.
     * 
     * @param surroundCallsWithSubspan pass in true to have downstream calls surrounded with a new span, false to only
     * propagate the current span's info downstream (no subspan).
     * @param tagStrategy set the strategy by which to tag the span
     */
    public WingtipsClientHttpRequestInterceptor(boolean surroundCallsWithSubspan, 
            HttpTagStrategy<HttpRequest, ClientHttpResponse> tagStrategy) {
        this.surroundCallsWithSubspan = surroundCallsWithSubspan;
        this.tagStrategy = tagStrategy;
    }
    
    @Override
    public ClientHttpResponse intercept(HttpRequest request, 
            byte[] body, 
            ClientHttpRequestExecution execution) throws IOException {
        if (surroundCallsWithSubspan) {
            return createNewSpanAndExecuteRequest(request, body, execution);
        }
        return propagateTracingHeadersAndExecuteRequest(request, body, execution);
        
    }
    
    private ClientHttpResponse createNewSpanAndExecuteRequest(HttpRequest request, 
            byte[] body, 
            ClientHttpRequestExecution execution) throws IOException {
        // Will start a new trace if necessary, or a subspan if a trace is already in progress.
        Span spanAroundCall = Tracer.getInstance().startSpanInCurrentContext(getSubspanSpanName(request), SpanPurpose.CLIENT);
        tagSpanWithRequestAttributes(spanAroundCall, request);

        try {
            ClientHttpResponse response = propagateTracingHeadersAndExecuteRequest(request, body, execution);
            tagSpanWithResponseAttributes(spanAroundCall, response);
            return response;
        } catch(Throwable exception) {
            handleErroredRequestTags(spanAroundCall, exception);
            throw exception;
        }
        finally {
            // Span.close() contains the logic we want - if the spanAroundCall was an overall span (new trace)
            //      then tracer.completeRequestSpan() will be called, otherwise it's a subspan and
            //      tracer.completeSubSpan() will be called.
            spanAroundCall.close();
        }
    }

    private ClientHttpResponse propagateTracingHeadersAndExecuteRequest(HttpRequest request, byte[] body, ClientHttpRequestExecution execution) throws IOException {
        HttpRequest wrapperRequest = new HttpRequestWrapperWithModifiableHeaders(request);
        propagateTracingHeaders(wrapperRequest, Tracer.getInstance().getCurrentSpan());

        return execution.execute(wrapperRequest, body);
    }
    
    /**
     * Returns the name that should be used for the subspan surrounding the call. Defaults to {@code
     * resttemplate_downstream_call-[HTTP_METHOD]_[REQUEST_URI]} with any query string stripped, e.g. for a GET
     * call to https://foo.bar/baz?stuff=things, this would return {@code
     * "resttemplate_downstream_call-GET_https://foo.bar/baz"}. You can override this method
     * to return something else if you want a different subspan name format.
     *
     * @param request The request that is about to be executed.
     * @return The name that should be used for the subspan surrounding the call.
     */
    protected String getSubspanSpanName(HttpRequest request) {
        return HttpRequestTracingUtils.getSubspanSpanNameForHttpRequest(
            "resttemplate_downstream_call", getRequestMethodAsString(request.getMethod()), request.getURI().toString()
        );
    }
    
    /**
     * Broken out as a separate method so we can surround it in a try{} to ensure we don't break the overall
     * span handling with exceptions from the {@code tagStrategy}.
     * @param span The span to be tagged
     * @param requestObj The request context to use for tag values
     */
    private void tagSpanWithRequestAttributes(Span span, HttpRequest requestObj) {
        try {
            tagStrategy.tagSpanWithRequestAttributes(span, requestObj);
        } catch(Throwable taggingException) {
            logger.warn("Unable to tag span with request attributes", taggingException);
        }
    }

    /**
     * Broken out as a separate method so we can surround it in a try{} to ensure we don't break the overall
     * span handling with exceptions from the {@code tagStrategy}.
     * @param span The span to be tagged
     * @param responseObj The response context to be used for tag values
     */
    private void tagSpanWithResponseAttributes(Span span, ClientHttpResponse responseObj) {
        try {
            tagStrategy.tagSpanWithResponseAttributes(span, responseObj);
        } catch(Throwable taggingException) {
            logger.warn("Unable to tag span with response attributes", taggingException);
        }
    }

    /**
     * Broken out as a separate method so we can surround it in a try{} to ensure we don't break the overall
     * span handling with exceptions from the {@code tagStrategy}.
     * @param span The span to be tagged
     * @param throwable The exception context to use for tag values
     */
    private void handleErroredRequestTags(Span span, Throwable throwable) {
        try {
            tagStrategy.handleErroredRequest(span, throwable);
        } catch(Throwable taggingException) {
            logger.warn("Unable to tag errored span with exception", taggingException);
        }
    }
    
}
