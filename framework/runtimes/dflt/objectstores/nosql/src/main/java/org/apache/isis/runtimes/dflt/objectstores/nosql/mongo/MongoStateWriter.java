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

package org.apache.isis.runtimes.dflt.objectstores.nosql.mongo;

import java.util.List;

import org.apache.isis.core.commons.exceptions.UnexpectedCallException;
import org.apache.isis.runtimes.dflt.objectstores.nosql.StateWriter;
import org.apache.log4j.Logger;

import com.mongodb.BasicDBObject;
import com.mongodb.DB;
import com.mongodb.DBCollection;

public class MongoStateWriter implements StateWriter {
    private static final Logger LOG = Logger.getLogger(MongoStateWriter.class);
    private final BasicDBObject dbObject;
    private final DBCollection instances;

    public MongoStateWriter(final DB db, final String specName) {
        dbObject = new BasicDBObject();
        instances = db.getCollection(specName);
    }

    public void flush() {
        instances.save(dbObject);
        LOG.debug("saved " + dbObject);
    }

    @Override
    public void writeId(final String oid) {
        writeField("_id", oid);
    }

    @Override
    public void writeType(final String type) {
        writeField("_type", type);
    }

    @Override
    public void writeField(final String id, final String data) {
        dbObject.put(id, data);
    }

    @Override
    public void writeField(final String id, final long l) {
        dbObject.put(id, Long.toString(l));
    }

    @Override
    public void writeEncryptionType(final String type) {
    }

    @Override
    public void writeVersion(final String currentVersion, final String newVersion) {
    }

    @Override
    public void writeTime(final String time) {
    }

    @Override
    public void writeUser(final String user) {
    }

    @Override
    public StateWriter addAggregate(final String id) {
        throw new UnexpectedCallException();
    }

    @Override
    public StateWriter createElementWriter() {
        return null;
    }

    @Override
    public void writeCollection(final String id, final List<StateWriter> elements) {
    }
}