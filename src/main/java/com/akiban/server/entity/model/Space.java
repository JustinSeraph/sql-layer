/**
 * END USER LICENSE AGREEMENT (“EULA”)
 *
 * READ THIS AGREEMENT CAREFULLY (date: 9/13/2011):
 * http://www.akiban.com/licensing/20110913
 *
 * BY INSTALLING OR USING ALL OR ANY PORTION OF THE SOFTWARE, YOU ARE ACCEPTING
 * ALL OF THE TERMS AND CONDITIONS OF THIS AGREEMENT. YOU AGREE THAT THIS
 * AGREEMENT IS ENFORCEABLE LIKE ANY WRITTEN AGREEMENT SIGNED BY YOU.
 *
 * IF YOU HAVE PAID A LICENSE FEE FOR USE OF THE SOFTWARE AND DO NOT AGREE TO
 * THESE TERMS, YOU MAY RETURN THE SOFTWARE FOR A FULL REFUND PROVIDED YOU (A) DO
 * NOT USE THE SOFTWARE AND (B) RETURN THE SOFTWARE WITHIN THIRTY (30) DAYS OF
 * YOUR INITIAL PURCHASE.
 *
 * IF YOU WISH TO USE THE SOFTWARE AS AN EMPLOYEE, CONTRACTOR, OR AGENT OF A
 * CORPORATION, PARTNERSHIP OR SIMILAR ENTITY, THEN YOU MUST BE AUTHORIZED TO SIGN
 * FOR AND BIND THE ENTITY IN ORDER TO ACCEPT THE TERMS OF THIS AGREEMENT. THE
 * LICENSES GRANTED UNDER THIS AGREEMENT ARE EXPRESSLY CONDITIONED UPON ACCEPTANCE
 * BY SUCH AUTHORIZED PERSONNEL.
 *
 * IF YOU HAVE ENTERED INTO A SEPARATE WRITTEN LICENSE AGREEMENT WITH AKIBAN FOR
 * USE OF THE SOFTWARE, THE TERMS AND CONDITIONS OF SUCH OTHER AGREEMENT SHALL
 * PREVAIL OVER ANY CONFLICTING TERMS OR CONDITIONS IN THIS AGREEMENT.
 */

package com.akiban.server.entity.model;

import org.codehaus.jackson.map.ObjectMapper;

import java.io.IOException;
import java.io.Reader;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

public final class Space {

    public static Space create(Reader reader) throws IOException {
        Space result = new ObjectMapper().readValue(reader, Space.class);
        result.visit(new Validator());
        return result;
    }

    public void visit(EntityVisitor visitor) {
        for (Map.Entry<String, Entity> entry : entities.entrySet()) {
            entry.getValue().accept(entry.getKey(), visitor);
        }
    }

    public Map<String, Entity> getEntities() {
        return entities;
    }

    @SuppressWarnings("unused")
    void setEntities(Map<String, Entity> entities) {
        this.entities = Collections.unmodifiableMap(new TreeMap<>(entities));
    }

    @Override
    public String toString() {
        return entities.toString();
    }

    Space() {}

    private Map<String, Entity> entities;

    private static class Validator extends AbstractEntityVisitor {

        @Override
        public void visitEntity(String name, Entity entity) {
            validateUUID(entity.uuid());
        }

        @Override
        public void visitScalar(String name, Attribute scalar) {
            validateUUID(scalar.getUUID());
            if (scalar.getType() == null)
                throw new IllegalEntityDefinition("no type set for scalar");
            if (scalar.getAttributes() != null)
                throw new IllegalEntityDefinition("attributes can't be set for scalar");
        }

        @Override
        public void visitCollection(String name, Attribute collection) {
            validateUUID(collection.getUUID());
            if (collection.getType() != null)
                throw new IllegalEntityDefinition("type can't be set for collection");
            if (collection.getAttributes() == null)
                throw new IllegalEntityDefinition("no attributes set for collection");
        }

        private void validateUUID(UUID uuid) {
            if (uuid == null)
                throw new IllegalEntityDefinition("no uuid specified");
            if (!uuids.add(uuid))
                throw new IllegalEntityDefinition("duplicate uuid found: " + uuid);
        }

        private final Set<UUID> uuids = new HashSet<>();
    }
}
