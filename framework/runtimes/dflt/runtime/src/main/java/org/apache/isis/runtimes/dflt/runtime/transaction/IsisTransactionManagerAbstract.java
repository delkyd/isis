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

package org.apache.isis.runtimes.dflt.runtime.transaction;

import static org.apache.isis.core.commons.ensure.Ensure.ensureThatState;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;

import org.apache.isis.core.commons.debug.DebugBuilder;
import org.apache.isis.runtimes.dflt.runtime.system.session.IsisSession;
import org.apache.isis.runtimes.dflt.runtime.system.transaction.IsisTransaction;
import org.apache.isis.runtimes.dflt.runtime.system.transaction.IsisTransactionManager;
import org.apache.isis.runtimes.dflt.runtime.system.transaction.IsisTransactionManagerAware;
import org.apache.isis.runtimes.dflt.runtime.system.transaction.MessageBroker;
import org.apache.isis.runtimes.dflt.runtime.system.transaction.TransactionalClosure;
import org.apache.isis.runtimes.dflt.runtime.system.transaction.TransactionalClosureWithReturn;
import org.apache.isis.runtimes.dflt.runtime.system.transaction.UpdateNotifier;
import org.apache.isis.runtimes.dflt.runtime.transaction.messagebroker.MessageBrokerDefault;
import org.apache.isis.runtimes.dflt.runtime.transaction.updatenotifier.UpdateNotifierDefault;
import org.apache.log4j.Logger;

public abstract class IsisTransactionManagerAbstract<T extends IsisTransaction> implements IsisTransactionManager {

    private static final Logger LOG = Logger.getLogger(IsisTransactionManagerAbstract.class);

    private IsisSession session;

    /**
     * Holds the current or most recently completed transaction.
     */
    private T transaction;

    // ////////////////////////////////////////////////////////////////
    // constructor
    // ////////////////////////////////////////////////////////////////

    public IsisTransactionManagerAbstract() {
    }

    // ////////////////////////////////////////////////////////////////
    // open, close
    // ////////////////////////////////////////////////////////////////

    @Override
    public void open() {
        ensureThatState(session, is(notNullValue()), "session is required");
    }

    @Override
    public void close() {
        if (getTransaction() != null) {
            try {
                abortTransaction();
            } catch (final Exception e2) {
                LOG.error("failure during abort", e2);
            }
        }
        session = null;
    }

    // //////////////////////////////////////////////////////
    // current transaction (if any)
    // //////////////////////////////////////////////////////

    /**
     * Current transaction (if any).
     */
    @Override
    public T getTransaction() {
        return transaction;
    }

    /**
     * Convenience method returning the {@link UpdateNotifier} of the
     * {@link #getTransaction() current transaction}.
     */
    protected UpdateNotifier getUpdateNotifier() {
        return getTransaction().getUpdateNotifier();
    }

    /**
     * Convenience method returning the {@link MessageBroker} of the
     * {@link #getTransaction() current transaction}.
     */
    protected MessageBroker getMessageBroker() {
        return getTransaction().getMessageBroker();
    }

    // ////////////////////////////////////////////////////////////////
    // Transactional Execution
    // ////////////////////////////////////////////////////////////////

    @Override
    public void executeWithinTransaction(final TransactionalClosure closure) {
        final boolean initiallyInTransaction = inTransaction();
        if (!initiallyInTransaction) {
            startTransaction();
        }
        try {
            closure.preExecute();
            closure.execute();
            closure.onSuccess();
            if (!initiallyInTransaction) {
                endTransaction();
            }
        } catch (final RuntimeException ex) {
            closure.onFailure();
            if (!initiallyInTransaction) {
                // temp TODO fix swallowing of exception
                // System.out.println(ex.getMessage());
                // ex.printStackTrace();
                try {
                    abortTransaction();
                } catch (final Exception e) {
                    LOG.error("Abort failure after exception", e);
                    // System.out.println(e.getMessage());
                    // e.printStackTrace();
                    throw new IsisTransactionManagerException("Abort failure: " + e.getMessage(), ex);
                }
            }
            throw ex;
        }
    }

    @Override
    public <Q> Q executeWithinTransaction(final TransactionalClosureWithReturn<Q> closure) {
        final boolean initiallyInTransaction = inTransaction();
        if (!initiallyInTransaction) {
            startTransaction();
        }
        try {
            closure.preExecute();
            final Q retVal = closure.execute();
            closure.onSuccess();
            if (!initiallyInTransaction) {
                endTransaction();
            }
            return retVal;
        } catch (final RuntimeException ex) {
            closure.onFailure();
            if (!initiallyInTransaction) {
                abortTransaction();
            }
            throw ex;
        }
    }

    public boolean inTransaction() {
        return getTransaction() != null && !getTransaction().getState().isComplete();
    }

    // ////////////////////////////////////////////////////////////////
    // create transaction, + hooks
    // ////////////////////////////////////////////////////////////////

    /**
     * Creates a new transaction and saves, to be accessible in
     * {@link #getTransaction()}.
     */
    protected final T createTransaction() {
        this.transaction = createTransaction(createMessageBroker(), createUpdateNotifier());
        return transaction;
    }

    /**
     * Overridable hook.
     * 
     * <p>
     * The provided {@link MessageBroker} and {@link UpdateNotifier} are
     * obtained from the hook methods ( {@link #createMessageBroker()} and
     * {@link #createUpdateNotifier()}).
     * 
     * @see #createMessageBroker()
     * @see #createUpdateNotifier()
     */
    protected abstract T createTransaction(MessageBroker messageBroker, UpdateNotifier updateNotifier);

    /**
     * Overridable hook, used in
     * {@link #createTransaction(MessageBroker, UpdateNotifier)
     * 
     * <p> Called when a new {@link IsisTransaction} is created.
     */
    protected MessageBroker createMessageBroker() {
        return new MessageBrokerDefault();
    }

    /**
     * Overridable hook, used in
     * {@link #createTransaction(MessageBroker, UpdateNotifier)
     * 
     * <p> Called when a new {@link IsisTransaction} is created.
     */
    protected UpdateNotifier createUpdateNotifier() {
        return new UpdateNotifierDefault();
    }

    // ////////////////////////////////////////////////////////////////
    // helpers
    // ////////////////////////////////////////////////////////////////

    protected void ensureTransactionInProgress() {
        ensureThatState(getTransaction() != null && !getTransaction().getState().isComplete(), is(true), "No transaction in progress");
    }

    protected void ensureTransactionNotInProgress() {
        ensureThatState(getTransaction() != null && !getTransaction().getState().isComplete(), is(false), "Transaction in progress");
    }

    // ////////////////////////////////////////////////////////////////////
    // injectInto
    // ////////////////////////////////////////////////////////////////////

    @Override
    public void injectInto(final Object candidate) {
        if (IsisTransactionManagerAware.class.isAssignableFrom(candidate.getClass())) {
            final IsisTransactionManagerAware cast = IsisTransactionManagerAware.class.cast(candidate);
            cast.setTransactionManager(this);
        }
    }

    // //////////////////////////////////////////////////////
    // debugging
    // //////////////////////////////////////////////////////

    @Override
    public void debugData(final DebugBuilder debug) {
        debug.appendln("Transaction", getTransaction());
    }

    // ////////////////////////////////////////////////////////////////
    // Dependencies (injected)
    // ////////////////////////////////////////////////////////////////

    @Override
    public IsisSession getSession() {
        return session;
    }

    /**
     * Should be injected prior to {@link #open() opening}
     */
    public void setSession(final IsisSession session) {
        this.session = session;
    }

}