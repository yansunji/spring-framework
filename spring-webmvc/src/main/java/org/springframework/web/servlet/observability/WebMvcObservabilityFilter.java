/*
 * Copyright 2012-2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.web.servlet.observability;

import java.io.IOException;
import java.util.Collections;
import java.util.Set;

import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.Timer.Builder;
import io.micrometer.core.instrument.Timer.Sample;
import io.micrometer.core.instrument.tracing.context.HttpServerHandlerContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.core.observability.AutoTimer;
import org.springframework.core.observability.annotation.TimedAnnotations;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.util.NestedServletException;

/**
 * Intercepts incoming HTTP requests handled by Spring MVC handlers and records them.
 *
 * @author Jon Schneider
 * @author Phillip Webb
 * @author Chanhyeong LEE
 * @since 6.0.0
 */
public class WebMvcObservabilityFilter extends OncePerRequestFilter {

	private static final Log logger = LogFactory.getLog(WebMvcObservabilityFilter.class);

	private final MeterRegistry registry;

	private final WebMvcTagsProvider tagsProvider;

	private final String metricName;

	private final AutoTimer autoTimer;

	/**
	 * Create a new {@link WebMvcObservabilityFilter} instance.
	 * @param registry the meter registry
	 * @param tagsProvider the tags provider
	 * @param metricName the metric name
	 * @param autoTimer the auto-timers to apply or {@code null} to disable auto-timing
	 */
	public WebMvcObservabilityFilter(MeterRegistry registry, WebMvcTagsProvider tagsProvider, String metricName,
			AutoTimer autoTimer) {
		this.registry = registry;
		this.tagsProvider = tagsProvider;
		this.metricName = metricName;
		this.autoTimer = autoTimer;
	}

	@Override
	protected boolean shouldNotFilterAsyncDispatch() {
		return false;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		TimingContext timingContext = TimingContext.get(request);
		if (timingContext == null) {
			timingContext = startAndAttachTimingContext(request);
		}
		try {
			filterChain.doFilter(request, response);
			if (!request.isAsyncStarted()) {
				// Only record when async processing has finished or never been started.
				// If async was started by something further down the chain we wait
				// until the second filter invocation (but we'll be using the
				// TimingContext that was attached to the first)
				Throwable exception = fetchException(request);
				record(timingContext, request, response, exception);
			}
		}
		catch (Exception ex) {
			response.setStatus(HttpStatus.INTERNAL_SERVER_ERROR.value());
			record(timingContext, request, response, unwrapNestedServletException(ex));
			throw ex;
		}
	}

	private Throwable unwrapNestedServletException(Throwable ex) {
		return (ex instanceof NestedServletException) ? ex.getCause() : ex;
	}

	private TimingContext startAndAttachTimingContext(HttpServletRequest request) {
		HttpServerHandlerContext handlerContext = new HttpServerHandlerContext(HttpServletRequestWrapper.wrap(request));
		Sample sample = Timer.start(this.registry, handlerContext);
		Timer.Scope scope = sample.makeCurrent();
		TimingContext timingContext = new TimingContext(sample, scope, handlerContext);
		timingContext.attachTo(request);
		return timingContext;
	}

	private Throwable fetchException(HttpServletRequest request) {
		return (Throwable) request.getAttribute(DispatcherServlet.EXCEPTION_ATTRIBUTE);
	}

	private void record(TimingContext timingContext, HttpServletRequest request, HttpServletResponse response,
			Throwable exception) {
		try {
			Object handler = getHandler(request);
			Set<Timed> annotations = getTimedAnnotations(handler);
			HttpServerHandlerContext handlerContext = timingContext.getHandlerContext();
			handlerContext.setResponse(HttpServletResponseWrapper.wrap(handlerContext.getRequest(), response, exception));
			AutoTimer.apply(this.autoTimer, this.metricName, annotations,
					builder -> stop(enhanceBuilder(builder, handler, request, response, exception), timingContext));
		}
		catch (Exception ex) {
			logger.warn("Failed to record timer metrics", ex);
			// Allow request-response exchange to continue, unaffected by metrics problem
		}
	}

	private Object getHandler(HttpServletRequest request) {
		return request.getAttribute(HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE);
	}

	private Set<Timed> getTimedAnnotations(Object handler) {
		if (handler instanceof HandlerMethod) {
			HandlerMethod handlerMethod = (HandlerMethod) handler;
			return TimedAnnotations.get(handlerMethod.getMethod(), handlerMethod.getBeanType());
		}
		return Collections.emptySet();
	}

	private void stop(Builder builder, TimingContext timingContext) {
		timingContext.getScope().close();
		timingContext.getSample().stop(builder);
	}

	private Timer.Builder enhanceBuilder(Builder builder, Object handler, HttpServletRequest request, HttpServletResponse response,
			Throwable exception) {
		return builder.tags(this.tagsProvider.getTags(request, response, handler, exception));
	}

	/**
	 * Context object attached to a request to retain information across the multiple
	 * filter calls that happen with async requests.
	 */
	private static class TimingContext {

		private static final String ATTRIBUTE = TimingContext.class.getName();

		private final Sample sample;
		private final Timer.Scope scope;
		private final HttpServerHandlerContext handlerContext;

		TimingContext(Sample sample, Timer.Scope scope, HttpServerHandlerContext handlerContext) {
			this.sample = sample;
			this.scope = scope;
			this.handlerContext = handlerContext;
		}

		Sample getSample() {
			return this.sample;
		}

		Timer.Scope getScope() {
			return this.scope;
		}

		HttpServerHandlerContext getHandlerContext() {
			return this.handlerContext;
		}

		void attachTo(HttpServletRequest request) {
			request.setAttribute(ATTRIBUTE, this);
		}

		static TimingContext get(HttpServletRequest request) {
			return (TimingContext) request.getAttribute(ATTRIBUTE);
		}

	}

}
