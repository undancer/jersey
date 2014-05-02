/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2012-2014 Oracle and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * http://glassfish.java.net/public/CDDL+GPL_1_1.html
 * or packager/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at packager/legal/LICENSE.txt.
 *
 * GPL Classpath Exception:
 * Oracle designates this particular file as subject to the "Classpath"
 * exception as provided by Oracle in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */
package org.glassfish.jersey.message.internal;

import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.Iterator;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.ws.rs.BadRequestException;
import javax.ws.rs.ProcessingException;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.NoContentException;
import javax.ws.rs.ext.MessageBodyReader;
import javax.ws.rs.ext.ReaderInterceptor;
import javax.ws.rs.ext.ReaderInterceptorContext;

import org.glassfish.jersey.internal.LocalizationMessages;
import org.glassfish.jersey.internal.PropertiesDelegate;
import org.glassfish.jersey.internal.inject.ServiceLocatorSupplier;
import org.glassfish.jersey.message.MessageBodyWorkers;

import org.glassfish.hk2.api.ServiceLocator;

import jersey.repackaged.com.google.common.collect.Lists;

/**
 * Represents reader interceptor chain executor for both client and server side.
 * It constructs wrapped interceptor chain and invokes it. At the end of the chain
 * a {@link MessageBodyReader message body reader} execution interceptor is inserted,
 * which finally reads an entity from the output stream provided by the chain.
 *
 * @author Miroslav Fuksa (miroslav.fuksa at oracle.com)
 * @author Jakub Podlesak (jakub.podlesak at oracle.com)
 */
public final class ReaderInterceptorExecutor extends InterceptorExecutor<ReaderInterceptor>
        implements ReaderInterceptorContext, ServiceLocatorSupplier {

    private final static Logger LOGGER = Logger.getLogger(ReaderInterceptorExecutor.class.getName());

    private InputStream inputStream;
    private final MultivaluedMap<String, String> headers;

    private final Iterator<ReaderInterceptor> interceptors;
    private int processedCount;
    private final MessageBodyWorkers workers;
    private final boolean translateNce;

    private final ServiceLocator serviceLocator;

    /**
     * Constructs a new executor to read given type from provided {@link InputStream entityStream}.
     *
     * @param rawType            raw Java entity type.
     * @param type               generic Java entity type.
     * @param annotations        array of annotations on the declaration of the artifact
     *                           that will be initialized with the produced instance. E.g. if the message
     *                           body is to be converted into a method parameter, this will be the annotations
     *                           on that parameter returned by {@code Method.getParameterAnnotations}.
     * @param mediaType          media type of the HTTP entity.
     * @param headers            mutable message headers.
     * @param propertiesDelegate request-scoped properties delegate.
     * @param inputStream        entity input stream.
     * @param workers            {@link org.glassfish.jersey.message.MessageBodyWorkers Message body workers}.
     * @param readerInterceptors Reader interceptor that are to be used to intercept the reading of an entity.
     *                           The interceptors will be executed in the same order as given in this parameter.
     * @param translateNce       if {@code true}, the {@link javax.ws.rs.core.NoContentException} thrown by a selected message body
     *                           reader will be translated into a {@link javax.ws.rs.BadRequestException} as required by
     * @param serviceLocator Service locator.
     */
    public ReaderInterceptorExecutor(Class<?> rawType, Type type, Annotation[] annotations, MediaType mediaType,
                                     MultivaluedMap<String, String> headers, PropertiesDelegate propertiesDelegate,
                                     InputStream inputStream, MessageBodyWorkers workers,
                                     Iterable<ReaderInterceptor> readerInterceptors,
                                     boolean translateNce, ServiceLocator serviceLocator) {

        super(rawType, type, annotations, mediaType, propertiesDelegate);
        this.headers = headers;
        this.inputStream = inputStream;
        this.workers = workers;
        this.translateNce = translateNce;
        this.serviceLocator = serviceLocator;

        final List<ReaderInterceptor> effectiveInterceptors = Lists.newArrayList(readerInterceptors);
        effectiveInterceptors.add(new TerminalReaderInterceptor());

        this.interceptors = effectiveInterceptors.iterator();
        this.processedCount = 0;
    }

    /**
     * Starts the interceptor chain execution.
     *
     * @return an entity read from the stream.
     */
    @Override
    @SuppressWarnings("unchecked")
    public Object proceed() throws IOException {
        if (!interceptors.hasNext()) {
            throw new ProcessingException(LocalizationMessages.ERROR_INTERCEPTOR_READER_PROCEED());
        }
        final ReaderInterceptor interceptor = interceptors.next();
        traceBefore(interceptor, MsgTraceEvent.RI_BEFORE);
        try {
            return interceptor.aroundReadFrom(this);
        } finally {
            processedCount++;
            traceAfter(interceptor, MsgTraceEvent.RI_AFTER);
        }
    }

    @Override
    public InputStream getInputStream() {
        return this.inputStream;
    }

    @Override
    public void setInputStream(InputStream is) {
        this.inputStream = is;

    }

    @Override
    public MultivaluedMap<String, String> getHeaders() {
        return headers;
    }

    /**
     * Get number of processed interceptors.
     *
     * @return number of processed interceptors.
     */
    int getProcessedCount() {
        return processedCount;
    }

    @Override
    public ServiceLocator getServiceLocator() {
        return serviceLocator;
    }

    /**
     * Terminal reader interceptor which choose the appropriate {@link MessageBodyReader}
     * and reads the entity from the input stream. The order of actions is the following: <br>
     * 1. choose the appropriate {@link MessageBodyReader} <br>
     * 3. reads the entity from the output stream <br>
     */
    private class TerminalReaderInterceptor implements ReaderInterceptor {

        @Override
        @SuppressWarnings("unchecked")
        public Object aroundReadFrom(ReaderInterceptorContext context) throws IOException, WebApplicationException {
            processedCount--; //this is not regular interceptor -> count down

            traceBefore(null, MsgTraceEvent.RI_BEFORE);
            try {
                final TracingLogger tracingLogger = getTracingLogger();
                if (tracingLogger.isLogEnabled(MsgTraceEvent.MBR_FIND)) {
                    tracingLogger.log(MsgTraceEvent.MBR_FIND,
                            context.getType().getName(),
                            (context.getGenericType() instanceof Class ?
                                    ((Class) context.getGenericType()).getName() : context.getGenericType()),
                            String.valueOf(context.getMediaType()), java.util.Arrays.toString(context.getAnnotations()));
                }

                final MessageBodyReader bodyReader = workers.getMessageBodyReader(
                        context.getType(),
                        context.getGenericType(),
                        context.getAnnotations(),
                        context.getMediaType(),
                        ReaderInterceptorExecutor.this);

                final EntityInputStream input = new EntityInputStream(context.getInputStream());

                if (bodyReader == null) {
                    if (input.isEmpty() && !context.getHeaders().containsKey(HttpHeaders.CONTENT_TYPE)) {
                        return null;
                    } else {
                        LOGGER.log(Level.SEVERE, LocalizationMessages.ERROR_NOTFOUND_MESSAGEBODYREADER(context.getMediaType(),
                                context.getType(), context.getGenericType()));
                        throw new MessageBodyProviderNotFoundException(LocalizationMessages.ERROR_NOTFOUND_MESSAGEBODYREADER(
                                context.getMediaType(), context.getType(), context.getGenericType()));
                    }
                }
                Object entity = invokeReadFrom(context, bodyReader, input);

                if (bodyReader instanceof CompletableReader) {
                    entity = ((CompletableReader) bodyReader).complete(entity);
                }
                return entity;
            } finally {
                clearLastTracedInterceptor();
                traceAfter(null, MsgTraceEvent.RI_AFTER);
            }
        }

        @SuppressWarnings("unchecked")
        private Object invokeReadFrom(ReaderInterceptorContext context, MessageBodyReader bodyReader, EntityInputStream input)
                throws WebApplicationException, IOException {

            final TracingLogger tracingLogger = getTracingLogger();
            final long timestamp = tracingLogger.timestamp(MsgTraceEvent.MBR_READ_FROM);
            try {
                Object entity;
                if (translateNce) {
                    try {
                        entity = bodyReader.readFrom(context.getType(), context.getGenericType(), context.getAnnotations(),
                                context.getMediaType(), context.getHeaders(), input);
                    } catch (NoContentException ex) {
                        throw new BadRequestException(ex);
                    }
                } else {
                    entity = bodyReader.readFrom(context.getType(), context.getGenericType(), context.getAnnotations(),
                            context.getMediaType(), context.getHeaders(), input);
                }
                return entity;
            } finally {
                tracingLogger.logDuration(MsgTraceEvent.MBR_READ_FROM, timestamp, bodyReader);
            }
        }
    }
}
