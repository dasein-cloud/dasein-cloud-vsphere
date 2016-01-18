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

import org.dasein.cloud.CloudException;
import org.dasein.cloud.InternalException;

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
            throw new CloudException("baseFolder must be non-empty string");
        }
        if (pSpecs.size() == 0) {
            throw new CloudException("PropertySpec list must have at least one element");
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
            throw new CloudException(e);
        }

        RetrieveResult props;
        try {
            props = vimPortType.retrievePropertiesEx(vimServiceContent.getPropertyCollector(), traversalSpec.getPropertyFilterSpecList(), new RetrieveOptions());
        } catch ( InvalidPropertyFaultMsg e ) {
            throw new InternalException("InvalidPropertyFault", e);
        } catch ( RuntimeFaultFaultMsg e ) {
            throw new CloudException("RuntimeFault", e);
        } catch ( Exception e ) {
            throw new CloudException(e);
        }

        return props;
    }

    public ManagedObjectReference searchDatastores(Vsphere provider, @Nonnull ManagedObjectReference hostDatastoreBrowser, @Nonnull String datastoreFolder, @Nullable HostDatastoreBrowserSearchSpec searchSpec) throws CloudException, InternalException{
        VsphereConnection vsphereConnection = provider.getServiceInstance();
        VimPortType vimPortType = vsphereConnection.getVimPort();
        try {
            return vimPortType.searchDatastoreSubFoldersTask(hostDatastoreBrowser, datastoreFolder, searchSpec);
        } catch (FileFaultFaultMsg fileFaultFaultMsg) {
            throw new CloudException("FileFaultFaultMessage searching datastores", fileFaultFaultMsg);
        } catch (InvalidDatastoreFaultMsg invalidDatastoreFaultMsg) {
            throw new CloudException("InvalidDatastoreFaultMsg searching datastores", invalidDatastoreFaultMsg);
        } catch (RuntimeFaultFaultMsg runtimeFaultFaultMsg) {
            throw new CloudException("RuntimeFaultFaultMsg searching datastores", runtimeFaultFaultMsg);
        }
    }
}
