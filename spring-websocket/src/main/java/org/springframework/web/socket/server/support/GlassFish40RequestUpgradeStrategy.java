/*
 * Copyright 2002-2013 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.web.socket.server.support;

import java.lang.reflect.Constructor;

import org.glassfish.tyrus.core.EndpointWrapper;
import org.glassfish.tyrus.spi.SPIEndpoint;
import org.glassfish.tyrus.websockets.WebSocketApplication;
import org.springframework.util.ClassUtils;


/**
 * Extension of the {@link AbstractGlassFishRequestUpgradeStrategy} that provides support
 * for only GlassFish 4.0.
 *
 * @author Rossen Stoyanchev
 * @author Michael Irwin
 * @since 4.0
 */
public class GlassFish40RequestUpgradeStrategy extends AbstractGlassFishRequestUpgradeStrategy {

	protected WebSocketApplication createTyrusEndpoint(EndpointWrapper endpoint) {
		try {
			Class<?> clazz = ClassUtils.forName("org.glassfish.tyrus.server.TyrusEndpoint", 
					GlassFish40RequestUpgradeStrategy.class.getClassLoader());
			Constructor<?> constructor = clazz.getConstructor(SPIEndpoint.class);
			WebSocketApplication app = (WebSocketApplication) constructor.newInstance(endpoint);
			return app;
		}
		catch (ReflectiveOperationException exception) {
			throw new RuntimeException(exception);
		}
	}

}
