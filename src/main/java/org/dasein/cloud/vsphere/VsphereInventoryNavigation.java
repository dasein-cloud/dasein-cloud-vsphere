/**
 * Copyright (C) 2012-2016 Dell, Inc.
 * See annotations for authorship information
 *
 * ====================================================================
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
 * ====================================================================
 */

package org.dasein.cloud.vsphere;

import com.vmware.vim25.*;

import org.dasein.cloud.*;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;

/**
 * User: daniellemayne
 * Date: 14/09/2015
 * Time: 09:57
 *
 * VsphereInventoryNavigation is a convenience class for traversing the object hierarchy
 * in a Vsphere environment starting at the ServiceInstance.
 */
public class VsphereInventoryNavigation {

    private static final String VIMSERVICEINSTANCETYPE = "ServiceInstance";
    private static final String VIMSERVICEINSTANCEVALUE = "ServiceInstance";

    public RetrieveResult retrieveObjectList(@Nonnull Vsphere provider, @Nonnull String baseFolder, @Nullable List<SelectionSpec> selectionSpecsArr, @Nonnull List<PropertySpec> pSpecs) throws InternalException, CloudException {
        if ("".equals(baseFolder)) {
            throw new InternalException("baseFolder must be non-empty string");
        }
        if (pSpecs.size() == 0) {
            throw new InternalException("PropertySpec list must have at least one element");
        }

        VsphereConnection vsphereConnection = provider.getServiceInstance();
        ServiceContent serviceContent = vsphereConnection.getServiceContent();
        VimPortType vimPortType = vsphereConnection.getVimPort();

        ManagedObjectReference rootFolder = serviceContent.getRootFolder();

        VsphereTraversalSpec traversalSpec = new VsphereTraversalSpec("VisitFolders", "childEntity", "Folder", false)
            .withSelectionSpec("VisitFolders", "DataCenterTo" + baseFolder, "Datacenter", baseFolder, false);

        if (selectionSpecsArr != null) {
            traversalSpec = traversalSpec.withSelectionSpec(selectionSpecsArr);
        }

        traversalSpec = traversalSpec.withObjectSpec(rootFolder, true)
                .withPropertySpec(pSpecs);

        traversalSpec.finalizeTraversalSpec();

        ServiceContent vimServiceContent;
        try {
            ManagedObjectReference ref = new ManagedObjectReference();
            ref.setType(VIMSERVICEINSTANCETYPE);
            ref.setValue(VIMSERVICEINSTANCEVALUE);
            vimServiceContent = vimPortType.retrieveServiceContent(ref);
        } catch ( RuntimeFaultFaultMsg e ) {
            if (e.getFaultInfo() instanceof NoPermission) {
                throw new AuthenticationException("NoPermission fault when retrieving service content", e).withFaultType(AuthenticationException.AuthenticationFaultType.FORBIDDEN);
            }
            throw new GeneralCloudException("Error retrieving service content for inventory search", e, CloudErrorType.GENERAL);
        }

        RetrieveResult props;
        try {
            props = vimPortType.retrievePropertiesEx(vimServiceContent.getPropertyCollector(), traversalSpec.getPropertyFilterSpecList(), new RetrieveOptions());
        } catch ( InvalidPropertyFaultMsg e ) {
            throw new InternalException("InvalidPropertyFault", e);
        } catch ( RuntimeFaultFaultMsg e ) {
            if (e.getFaultInfo() instanceof NoPermission) {
                throw new AuthenticationException("NoPermission fault when searching inventory", e).withFaultType(AuthenticationException.AuthenticationFaultType.FORBIDDEN);
            }
            throw new GeneralCloudException("Error retrieving object list", e, CloudErrorType.GENERAL);
        } catch ( Exception e ) {
            throw new GeneralCloudException("Error retrieving object list", e, CloudErrorType.GENERAL);
        }

        return props;
    }

    public ManagedObjectReference searchDatastores(Vsphere provider, @Nonnull ManagedObjectReference hostDatastoreBrowser, @Nonnull String datastoreFolder, @Nullable HostDatastoreBrowserSearchSpec searchSpec) throws CloudException, InternalException {
        VsphereConnection vsphereConnection = provider.getServiceInstance();
        VimPortType vimPortType = vsphereConnection.getVimPort();
        try {
            return vimPortType.searchDatastoreSubFoldersTask(hostDatastoreBrowser, datastoreFolder, searchSpec);
        }catch (RuntimeFaultFaultMsg runtimeFaultFaultMsg) {
            if (runtimeFaultFaultMsg.getFaultInfo() instanceof NoPermission) {
                throw new AuthenticationException("NoPermission fault when searching datastores", runtimeFaultFaultMsg).withFaultType(AuthenticationException.AuthenticationFaultType.FORBIDDEN);
            }
            throw new GeneralCloudException("Error searching datastores", runtimeFaultFaultMsg, CloudErrorType.GENERAL);
        }catch (Exception e) {
            throw new GeneralCloudException("Error searching datastores", e, CloudErrorType.GENERAL);
        }
    }
}
