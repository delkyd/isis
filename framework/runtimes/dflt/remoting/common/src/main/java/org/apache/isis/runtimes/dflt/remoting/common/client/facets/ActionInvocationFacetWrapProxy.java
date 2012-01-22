/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */

package org.apache.isis.runtimes.dflt.remoting.common.client.facets;

import java.util.List;

import org.apache.isis.applib.Identifier;
import org.apache.isis.core.commons.authentication.AuthenticationSession;
import org.apache.isis.core.commons.exceptions.IsisException;
import org.apache.isis.core.metamodel.adapter.ObjectAdapter;
import org.apache.isis.core.metamodel.adapter.oid.Oid;
import org.apache.isis.core.metamodel.facetapi.DecoratingFacet;
import org.apache.isis.core.metamodel.facets.actions.executed.ExecutedFacet;
import org.apache.isis.core.metamodel.facets.actions.executed.ExecutedFacet.Where;
import org.apache.isis.core.metamodel.facets.actions.invoke.ActionInvocationFacet;
import org.apache.isis.core.metamodel.facets.actions.invoke.ActionInvocationFacetAbstract;
import org.apache.isis.core.metamodel.spec.ActionType;
import org.apache.isis.core.metamodel.spec.ObjectSpecification;
import org.apache.isis.core.metamodel.spec.feature.ObjectAction;
import org.apache.isis.core.metamodel.spec.feature.ObjectActionParameter;
import org.apache.isis.runtimes.dflt.remoting.common.data.Data;
import org.apache.isis.runtimes.dflt.remoting.common.data.common.NullData;
import org.apache.isis.runtimes.dflt.remoting.common.data.common.ObjectData;
import org.apache.isis.runtimes.dflt.remoting.common.data.common.ReferenceData;
import org.apache.isis.runtimes.dflt.remoting.common.exchange.ExecuteServerActionRequest;
import org.apache.isis.runtimes.dflt.remoting.common.exchange.ExecuteServerActionResponse;
import org.apache.isis.runtimes.dflt.remoting.common.exchange.KnownObjectsRequest;
import org.apache.isis.runtimes.dflt.remoting.common.facade.ServerFacade;
import org.apache.isis.runtimes.dflt.remoting.common.protocol.ObjectEncoderDecoder;
import org.apache.isis.runtimes.dflt.runtime.persistence.ConcurrencyException;
import org.apache.isis.runtimes.dflt.runtime.system.context.IsisContext;
import org.apache.isis.runtimes.dflt.runtime.system.persistence.AdapterManager;
import org.apache.isis.runtimes.dflt.runtime.system.persistence.PersistenceSession;
import org.apache.isis.runtimes.dflt.runtime.system.transaction.MessageBroker;
import org.apache.isis.runtimes.dflt.runtime.system.transaction.UpdateNotifier;
import org.apache.isis.runtimes.dflt.runtime.transaction.messagebroker.MessageList;
import org.apache.isis.runtimes.dflt.runtime.transaction.messagebroker.WarningList;
import org.apache.log4j.Logger;

/**
 * A reflection peer for executing actions remotely, instead of on the local
 * machine. Any calls to <code>execute</code> are passed over the network to the
 * server for invocation. There are two cases where the request is not passed to
 * the server, ie it is executed locally: 1) where the method is static, ie is
 * on the class rather than an instance; 2) if the instance is not persistent;
 * 3) if the method is marked as 'local'. If a method is marked as being
 * 'remote' then static methods and methods on transient objects will be passed
 * to the server.
 * 
 * <p>
 * If any of the objects involved have been changed on the server by another
 * process then a ConcurrencyException will be passed back to the client and
 * re-thrown.
 * </p>
 */
public final class ActionInvocationFacetWrapProxy extends ActionInvocationFacetAbstract implements DecoratingFacet<ActionInvocationFacet> {

    private final static Logger LOG = Logger.getLogger(ActionInvocationFacetWrapProxy.class);
    private final ServerFacade serverFacade;
    private final ObjectEncoderDecoder encoder;
    private final ActionInvocationFacet underlyingFacet;
    private final ObjectAction objectAction;

    public ActionInvocationFacetWrapProxy(final ActionInvocationFacet underlyingFacet, final ServerFacade connection, final ObjectEncoderDecoder encoder, final ObjectAction objectAction) {
        super(underlyingFacet.getFacetHolder());
        this.underlyingFacet = underlyingFacet;
        this.serverFacade = connection;
        this.encoder = encoder;
        this.objectAction = objectAction;
    }

    @Override
    public ActionInvocationFacet getDecoratedFacet() {
        return underlyingFacet;
    }

    @Override
    public ObjectAdapter invoke(final ObjectAdapter target, final ObjectAdapter[] parameters) {
        if (isToBeExecutedRemotely(target)) {
            /*
             * NOTE - only remotely executing actions on objects not collection
             * - due to collections not having OIDs yet
             */
            return executeRemotely(target, parameters);
        } else {
            LOG.debug(debug("execute locally", getIdentifier(), target, parameters));
            return underlyingFacet.invoke(target, parameters);
        }
    }

    @Override
    public ObjectSpecification getReturnType() {
        return underlyingFacet.getReturnType();
    }

    @Override
    public ObjectSpecification getOnType() {
        return underlyingFacet.getOnType();
    }

    public Identifier getIdentifier() {
        return objectAction.getIdentifier();
    }

    private ObjectAdapter executeRemotely(final ObjectAdapter targetAdapter, final ObjectAdapter[] parameterAdapters) {
        if (LOG.isDebugEnabled()) {
            LOG.debug(debug("execute remotely", getIdentifier(), targetAdapter, parameterAdapters));
        }

        final KnownObjectsRequest knownObjects = new KnownObjectsRequest();
        final Data[] parameterObjectData = parameterValues(parameterAdapters, knownObjects);
        final ReferenceData targetReference = targetAdapter == null ? null : encoder.encodeActionTarget(targetAdapter, knownObjects);
        ExecuteServerActionResponse response;
        try {
            final ExecuteServerActionRequest request = new ExecuteServerActionRequest(getAuthenticationSession(), ActionType.USER, objectAction.getIdentifier().toNameParmsIdentityString(), targetReference, parameterObjectData);
            response = serverFacade.executeServerAction(request);

            // must deal with transient-now-persistent objects first
            if (targetAdapter.isTransient()) {
                encoder.madePersistent(targetAdapter, response.getPersistedTarget());
            }

            final List<ObjectActionParameter> parameters2 = objectAction.getParameters();
            for (int i = 0; i < parameterAdapters.length; i++) {
                if (parameters2.get(i).getSpecification().isNotCollection()) {
                    encoder.madePersistent(parameterAdapters[i], response.getPersistedParameters()[i]);
                }
            }

            final Data returned = response.getReturn();
            final ObjectAdapter returnedObject = returned instanceof NullData ? null : encoder.decode(returned);

            final ObjectData[] updates = response.getUpdates();
            for (final ObjectData update : updates) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("update " + update.getOid());
                }
                encoder.decode(update);
            }

            final ReferenceData[] disposed = response.getDisposed();
            for (final ReferenceData element : disposed) {
                final Oid oid = element.getOid();
                if (LOG.isDebugEnabled()) {
                    LOG.debug("disposed " + oid);
                }
                final ObjectAdapter adapter = getAdapterManager().getAdapterFor(oid);
                getUpdateNotifier().addDisposedObject(adapter);
            }

            copyMessages(response);

            copyWarnings(response);

            return returnedObject;

        } catch (final ConcurrencyException e) {
            final Oid source = e.getSource();
            if (source == null) {
                throw e;
            }
            final ObjectAdapter failedObject = getAdapterManager().getAdapterFor(source);
            getPersistenceSession().reload(failedObject);
            if (LOG.isInfoEnabled()) {
                LOG.info("concurrency conflict: " + e.getMessage());
            }
            throw new ConcurrencyException("Object automatically reloaded: " + failedObject.titleString(), e);
        } catch (final IsisException e) {
            LOG.error("remoting exception", e);
            throw e;
        }

    }

    private void copyWarnings(final WarningList response) {
        for (final String warning : response.getWarnings()) {
            getMessageBroker().addWarning(warning);
        }
    }

    private void copyMessages(final MessageList response) {
        for (final String message : response.getMessages()) {
            getMessageBroker().addMessage(message);
        }
    }

    private boolean isToBeExecutedRemotely(final ObjectAdapter targetAdapter) {
        final ExecutedFacet facet = objectAction.getFacet(ExecutedFacet.class);

        final boolean localOverride = (facet.value() == Where.LOCALLY);
        if (localOverride) {
            return false;
        }

        final boolean remoteOverride = (facet.value() == Where.REMOTELY);
        if (remoteOverride) {
            return true;
        }

        if (targetAdapter.getSpecification().isService()) {
            return true;
        }

        return targetAdapter.isPersistent();
    }

    private Data[] parameterValues(final ObjectAdapter[] parameters, final KnownObjectsRequest knownObjects) {
        final ObjectSpecification[] parameterTypes = new ObjectSpecification[parameters.length];
        for (int i = 0; i < parameterTypes.length; i++) {
            parameterTypes[i] = parameters[i].getSpecification();
        }
        return encoder.encodeActionParameters(parameterTypes, parameters, knownObjects);
    }

    private static String debug(final String message, final Identifier identifier, final ObjectAdapter target, final ObjectAdapter[] parameters) {
        if (!LOG.isDebugEnabled()) {
            return "";
        }
        final StringBuilder str = new StringBuilder();
        str.append(message);
        str.append(" ");
        str.append(identifier);
        str.append(" on ");
        str.append(target);
        for (int i = 0; i < parameters.length; i++) {
            if (i > 0) {
                str.append(',');
            }
            str.append(parameters[i]);
        }
        return str.toString();
    }

    // /////////////////////////////////////////////////////////
    // Dependencies (from context)
    // /////////////////////////////////////////////////////////

    protected PersistenceSession getPersistenceSession() {
        return IsisContext.getPersistenceSession();
    }

    protected AdapterManager getAdapterManager() {
        return getPersistenceSession().getAdapterManager();
    }

    protected AuthenticationSession getAuthenticationSession() {
        return IsisContext.getAuthenticationSession();
    }

    protected MessageBroker getMessageBroker() {
        return IsisContext.getMessageBroker();
    }

    protected UpdateNotifier getUpdateNotifier() {
        return IsisContext.getUpdateNotifier();
    }

}