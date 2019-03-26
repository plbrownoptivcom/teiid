/*
 * JBoss, Home of Professional Open Source.
 * See the COPYRIGHT.txt file distributed with this work for information
 * regarding copyright ownership.  Some portions may be licensed
 * to Red Hat, Inc. under one or more contributor license agreements.
 * 
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 * 
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA
 * 02110-1301 USA.
 */

package org.teiid.dqp.internal.process;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import org.teiid.CommandContext;
import org.teiid.PolicyDecider;
import org.teiid.adminapi.DataPolicy;
import org.teiid.adminapi.DataPolicy.Context;
import org.teiid.adminapi.DataPolicy.PermissionType;
import org.teiid.adminapi.impl.DataPolicyMetadata;
import org.teiid.core.util.Assertion;
import org.teiid.core.util.PropertiesUtils;

public class DataRolePolicyDecider implements PolicyDecider {

    private boolean allowCreateTemporaryTablesByDefault = PropertiesUtils.getBooleanProperty(System.getProperties(), "org.teiid.allowCreateTemporaryTablesByDefault", false); //$NON-NLS-1$
    private boolean allowFunctionCallsByDefault = PropertiesUtils.getBooleanProperty(System.getProperties(), "org.teiid.allowFunctionCallsByDefault", false); //$NON-NLS-1$

	@Override
	public Set<String> getInaccessibleResources(PermissionType action,
			Set<String> resources, Context context, CommandContext commandContext) {
		if ((action == PermissionType.EXECUTE || action == null) && context == Context.FUNCTION && allowFunctionCallsByDefault) {
			return Collections.emptySet();
		}
		Collection<DataPolicy> policies = commandContext.getAllowedDataPolicies().values();
		int policyCount = policies.size();
		boolean[] exclude = new boolean[policyCount];
		Boolean[] results = null;
		List<PermissionType> metadataPermissions = null;
		if (context == Context.METADATA) {
		    Assertion.assertTrue(resources.size() == 1);
		    if (action == PermissionType.READ) {
		        results = new Boolean[5];
                metadataPermissions = Arrays.asList(
                        PermissionType.ALTER, PermissionType.CREATE,
                        PermissionType.UPDATE, PermissionType.READ,
                        PermissionType.DELETE);
		    } else {
	            results = new Boolean[2];
	            metadataPermissions = Arrays.asList(PermissionType.ALTER, action);
		    }
		}
		outer:for (Iterator<String> iter = resources.iterator(); iter.hasNext();) {
			String resource = iter.next();
			Arrays.fill(exclude, false);
			int excludeCount = 0;
			while (resource.length() > 0) {
				Iterator<DataPolicy> policyIter = policies.iterator();
				for (int j = 0; j < policyCount; j++) {
					DataPolicyMetadata policy = (DataPolicyMetadata)policyIter.next();
					if (exclude[j]) {
						continue;
					}
					if (policy.isGrantAll()) {
						if (policy.getSchemas() == null) {
							resources.clear();
							return resources;
						}
						if (action == PermissionType.LANGUAGE) {
							iter.remove();
							continue outer;
						}
						//imported grant all must be checked against the schemas
						if (resource.indexOf('.') > 0) {
							continue;
						}
						if (policy.getSchemas().contains(resource)) {
							iter.remove();
							continue outer;
						}
						continue;
					}
					if (context == Context.METADATA) {
					    for (int i = 0; i < results.length; i++) {
	                        Boolean allows = policy.allows(resource, metadataPermissions.get(i));
	                        if (allows != null && allows && results[i] == null) {
                                resources.clear();
                                return resources;
                            }
	                        if (results[i] == null) {
					            results[i] = allows;
					        }
					    }
					}
					Boolean allows = policy.allows(resource, action);
					if (allows != null) {
						if (allows) {
							iter.remove();
							continue outer;
						}
						exclude[j] = true;
						excludeCount++;
					}
				}
				if (excludeCount == policyCount || action == PermissionType.LANGUAGE) {
					break; //don't check less specific permissions
				}
				resource = resource.substring(0, Math.max(0, resource.lastIndexOf('.')));
			}
		}
		return resources;
	}

	@Override
	public boolean hasRole(String roleName, CommandContext context) {
		return context.getAllowedDataPolicies().containsKey(roleName);
	}

	@Override
	public boolean isTempAccessible(PermissionType action, String resource,
			Context context, CommandContext commandContext) {
		if (resource != null) {
			return getInaccessibleResources(action, new HashSet<String>(Arrays.asList(resource)), context, commandContext).isEmpty();
		}
		Boolean result = null;
    	for(DataPolicy p:commandContext.getAllowedDataPolicies().values()) {
			DataPolicyMetadata policy = (DataPolicyMetadata)p;
			if (policy.isGrantAll()) {
				return true;
			}
			if (policy.isAllowCreateTemporaryTables() != null) {
				if (policy.isAllowCreateTemporaryTables()) {
					return true;
				}
				result = policy.isAllowCreateTemporaryTables();
			}
		}
    	if (result != null) {
    		return result;
    	}
    	return allowCreateTemporaryTablesByDefault;
	}
	
    public void setAllowCreateTemporaryTablesByDefault(
			boolean allowCreateTemporaryTablesByDefault) {
		this.allowCreateTemporaryTablesByDefault = allowCreateTemporaryTablesByDefault;
	}
    
    public void setAllowFunctionCallsByDefault(boolean allowFunctionCallsDefault) {
		this.allowFunctionCallsByDefault = allowFunctionCallsDefault;
	}
    
    @Override
    public boolean validateCommand(CommandContext commandContext) {
    	return !commandContext.getVdb().getDataPolicies().isEmpty();
    }

}
