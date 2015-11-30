package org.dasein.cloud.vsphere;

import com.vmware.vim25.PropertySpec;
import org.dasein.cloud.CloudException;
import org.dasein.cloud.InternalException;
import org.junit.Before;
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

    @Test(expected = CloudException.class)
    public void retrieveObjectListRequestShouldThrowExceptionIfEmptyPropertySpecObject() throws CloudException, InternalException {
        final List<PropertySpec> props = new ArrayList<PropertySpec>();

        vin.retrieveObjectList(vsphereMock, "hostFolder", null, props);
    }

    @Test(expected = CloudException.class)
    public void retrieveObjectListRequestShouldThrowExceptionIfEmptyBaseFolderString() throws CloudException, InternalException {
        final List<PropertySpec> props = new ArrayList<PropertySpec>();
        props.add(new PropertySpec());

        vin.retrieveObjectList(vsphereMock, "", null, props);
    }
}
