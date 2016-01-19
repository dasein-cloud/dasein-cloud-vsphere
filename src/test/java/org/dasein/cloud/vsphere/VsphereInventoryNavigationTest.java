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
import mockit.Expectations;
import org.dasein.cloud.AuthenticationException;
import org.dasein.cloud.CloudException;
import org.dasein.cloud.InternalException;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * User: daniellemayne
 * Date: 24/09/2015
 * Time: 12:43
 */
public class VsphereInventoryNavigationTest extends VsphereTestBase {
    private VsphereInventoryNavigation vin = null;

    @Before
    public void setUp() throws Exception{
        super.setUp();
        vin = new VsphereInventoryNavigation();
    }

    @Test(expected = InternalException.class)
    public void retrieveObjectListRequestShouldThrowExceptionIfEmptyPropertySpecObject() throws CloudException, InternalException {
        final List<PropertySpec> props = new ArrayList<PropertySpec>();

        vin.retrieveObjectList(vsphereMock, "hostFolder", null, props);
    }

    @Test(expected = InternalException.class)
    public void retrieveObjectListRequestShouldThrowExceptionIfEmptyBaseFolderString() throws CloudException, InternalException {
        final List<PropertySpec> props = new ArrayList<PropertySpec>();
        props.add(new PropertySpec());

        vin.retrieveObjectList(vsphereMock, "", null, props);
    }

    @Test(expected = AuthenticationException.class)
    public void searchDatastoresShouldThrowAuthenticationExceptionIfNoPermissionFaultIsThrown() throws CloudException, InternalException, RuntimeFaultFaultMsg, FileFaultFaultMsg, InvalidDatastoreFaultMsg {
        new Expectations() {
            {vimPortMock.searchDatastoreSubFoldersTask((ManagedObjectReference) any, anyString, (HostDatastoreBrowserSearchSpec) any);
                result = new RuntimeFaultFaultMsg("Invalid permissions", new NoPermission());}
        };

        vin.searchDatastores(vsphereMock, new ManagedObjectReference(), "blah", new HostDatastoreBrowserSearchSpec());
    }
}
