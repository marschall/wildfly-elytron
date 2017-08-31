/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2016 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.wildfly.security.authz;

import static org.wildfly.security._private.ElytronMessages.log;
import static org.wildfly.common.Assert.checkNotNullParam;

import java.security.Principal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import org.wildfly.security.permission.PermissionVerifier;

/**
 * A simple {@link PermissionMapper} implementation that maps to pre-defined {@link PermissionVerifier} instances.
 *
 * This {@code PermissionMapper} is constructed using a {@link Builder} which is used to construct an ordered list of
 * {@code PermissionVerifier} instances along with a set of principal names and a list of principal names.
 *
 * At the time {@link #mapPermissions(PermissionMappable, Roles)} is called this list is iterated to find corresponding
 * definitions where either the name of the {@link Principal} within the {@link PermissionMappable} is contained
 * within the mapping or the {@link Roles} in the {@code mapPermission} call contain at least one of the roles in the mapping
 * then the associated {@code PermissionVerifier} will be used.
 *
 * It is possible that multiple mappings could be matched during the call to {@link #mapPermissions(PermissionMappable, Roles)}
 * and this is why the ordering is important, by default only the first match will be used however this can be overridden by
 * calling {@link Builder#setMappingMode(MappingMode)} to choose a different mode to combine the resulting
 * {@link PermissionVerifier} instances.
 *
 * @author <a href="mailto:darran.lofthouse@jboss.com">Darran Lofthouse</a>
 */
public class SimplePermissionMapper implements PermissionMapper {

    private final MappingMode mappingMode;

    private final List<Mapping> mappings;

    private SimplePermissionMapper(MappingMode mappingMode, List<Mapping> mappings) {
        this.mappingMode = mappingMode;
        this.mappings = mappings;
    }

    @Override
    public PermissionVerifier mapPermissions(PermissionMappable permissionMappable, Roles roles) {
        checkNotNullParam("permissionMappable", permissionMappable);
        checkNotNullParam("roles", roles);

        PermissionVerifier result = null;

        for (Mapping current : mappings) {
            if (current.principalPredicate.test(permissionMappable.getPrincipal().getName()) || roles.containsAny(current.roles)) {
                    switch (mappingMode) {
                        case FIRST_MATCH:
                            return current.permissionVerifier;
                        case AND:
                            result = result != null ? result.and(current.permissionVerifier) : current.permissionVerifier;
                            break;
                        case OR:
                            result = result != null ? result.or(current.permissionVerifier) : current.permissionVerifier;
                            break;
                        case UNLESS:
                            result = result != null ? result.unless(current.permissionVerifier) : current.permissionVerifier;
                            break;
                        case XOR:
                            result = result != null ? result.xor(current.permissionVerifier) : current.permissionVerifier;
                            break;
                }
            }
        }


        return result != null ? result : PermissionVerifier.NONE;
    }

    /**
     * Construct a new {@link Builder} for creating the {@link PermissionMapper}.
     *
     * @return a new {@link Builder} for creating the {@link PermissionMapper}.
     */
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {

        private boolean built = false;

        private MappingMode mappingMode = MappingMode.FIRST_MATCH;

        private final List<Mapping> mappings = new ArrayList<>();

        Builder() {
        }

        /**
         * Set the mapping mode that the newly created {@link PermissionMapper} should use.
         *
         * @param mappingMode the mapping mode.
         * @return {@code this} builder to allow chaining.
         */
        public Builder setMappingMode(MappingMode mappingMode) {
            assertNotBuilt();
            this.mappingMode = mappingMode;

            return this;
        }

        /**
         * Add a new mapping to a {@link PermissionVerifier}, if the {@link PermissionMappable} being mapped has a principal name that is in the {@link Set} of principals or of any of the assigned roles are matched this mapping will be a match.
         *
         * @param principals the principal names to compare with the {@link PermissionMappable} principal.
         * @param roles the role names to compare with the roles being passed for mapping.
         * @param permissionVerifier the {@link PermissionVerifier} to use in the event of a resulting match.
         * @return {@code this} builder to allow chaining.
         */
        public Builder addMapping(Set<String> principals, Set<String> roles, PermissionVerifier permissionVerifier) {
            assertNotBuilt();
            mappings.add(new Mapping(new HashSet<>(checkNotNullParam("principals", principals))::contains, roles, permissionVerifier));

            return this;
        }

        /**
         * Add a new mapping to a {@link PermissionVerifier}, if the {@link PermissionMappable} being mapped has a principal or any of the assigned roles are matched this mapping will be a match.
         *
         * @param permissionVerifier the {@link PermissionVerifier} to use in the event of a resulting match.
         * @return {@code this} builder to allow chaining.
         */
        public Builder addMatchAllPrincipals(PermissionVerifier permissionVerifier) {
            assertNotBuilt();
            mappings.add(new Mapping(name -> true, Collections.emptySet(), permissionVerifier));

            return this;
        }


        /**
         * Build and return the resulting {@link PermissionMapper}.
         *
         * @return the resulting {@link PermissionMapper}
         */
        public PermissionMapper build() {
            assertNotBuilt();
            built = true;

            return new SimplePermissionMapper(mappingMode, mappings);
        }

        private void assertNotBuilt() {
            if (built) {
                throw log.builderAlreadyBuilt();
            }
        }
    }

    static class Mapping {

        final Predicate<String> principalPredicate;

        final Set<String> roles;

        final PermissionVerifier permissionVerifier;

        Mapping(Predicate<String> principalPredicate, Set<String> roles, PermissionVerifier permissionVerifier) {
            this.principalPredicate = principalPredicate;
            this.roles = Collections.unmodifiableSet(new HashSet<>(checkNotNullParam("roles", roles)));
            this.permissionVerifier = checkNotNullParam("permissionVerifier", permissionVerifier);
        }

    }

    public enum MappingMode {

        /**
         * If multiple mappings are found only the first will be used.
         */
        FIRST_MATCH,

        /**
         * If multiple mappings are found the corresponding {@link PermissionVerifier} instances will be combined using 'and'.
         * Will assign permission which would be assigned by all mappings.
         */
        AND,

        /**
         * If multiple mappings are found the corresponding {@link PermissionVerifier} instances will be combined using 'or'.
         * Will assign permissions which would be assigned by at least one mapping.
         */
        OR,

        /**
         * If multiple mappings are found the corresponding {@link PermissionVerifier} instances will be combined using 'xor'.
         * Will assign permissions which would be assigned by odd amount of mappings.
         */
        XOR,

        /**
         * If multiple mappings are found the corresponding {@link PermissionVerifier} instances will be combined using 'unless'.
         * Will assign permissions which would be assigned by first mapping but not by others.
         */
        UNLESS;
    }

}
