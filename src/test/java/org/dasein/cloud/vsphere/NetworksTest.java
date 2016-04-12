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

import com.vmware.vim25.PropertySpec;
import com.vmware.vim25.RetrieveResult;
import mockit.Expectations;
import mockit.NonStrictExpectations;
import org.dasein.cloud.CloudException;
import org.dasein.cloud.InternalException;
import org.dasein.cloud.network.VLAN;
import org.dasein.cloud.network.VLANState;
import org.dasein.cloud.util.Cache;
import org.dasein.cloud.util.CacheLevel;
import org.dasein.cloud.vsphere.network.VSphereNetwork;
import org.dasein.util.uom.time.Day;
import org.dasein.util.uom.time.TimePeriod;
import org.junit.Before;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

/**
 * User: daniellemayne
 * Date: 22/09/2015
 * Time: 12:35
 */
public class NetworksTest extends VsphereTestBase {
    private ObjectManagement om = new ObjectManagement();
    private final RetrieveResult networks = om.readJsonFile("src/test/resources/Networks/networks.json", RetrieveResult.class);
    private final RetrieveResult networksNoProperties = om.readJsonFile("src/test/resources/Networks/missingPropertiesNetworks.json", RetrieveResult.class);
    private final RetrieveResult networksNoSummaryProperty = om.readJsonFile("src/test/resources/Networks/missingSummaryPropertyNetworks.json", RetrieveResult.class);
    private final RetrieveResult networksNoConfigProperty = om.readJsonFile("src/test/resources/Networks/missingConfigPropertyNetworks.json", RetrieveResult.class);

    private final RetrieveResult switches = om.readJsonFile("src/test/resources/Networks/switches.json", RetrieveResult.class);
    private VSphereNetwork network = null;
    private List<PropertySpec> networkPSpec = null;
    private List<PropertySpec> switchPSpec = null;

    private Cache<VLAN> cache = null;

    @Before
    public void setUp() throws Exception {
        super.setUp();
        network = new VSphereNetwork(vsphereMock);
        networkPSpec = network.getNetworkPSpec();
        switchPSpec = network.getSwitchPSpec();
        cache = Cache.getInstance(vsphereMock, "networks", VLAN.class, CacheLevel.REGION_ACCOUNT, new TimePeriod<Day>(1, TimePeriod.DAY));
    }

    @Test
    public void listNetworks() throws CloudException, InternalException {
        cache.clear();

        new NonStrictExpectations(VSphereNetwork.class) {
            {network.retrieveObjectList(vsphereMock, "networkFolder", null, networkPSpec);
                result = networks;
            }
            {network.retrieveObjectList(vsphereMock, "networkFolder", null, switchPSpec);
                result = switches;
            }
        };

        Iterable<VLAN> vlans = network.listVlans();
        assertNotNull(vlans);
        assertTrue(vlans.iterator().hasNext());
        VLAN vlan = vlans.iterator().next();
        assertEquals("network-57", vlan.getProviderVlanId());
        assertEquals("1-My Fancy Test Network", vlan.getName());
        assertNull("Vlans are not dc specific. DataCenter should be null", vlan.getProviderDataCenterId());
        assertEquals(VLANState.AVAILABLE, vlan.getCurrentState());
        assertEquals("1-My Fancy Test Network (network-57)", vlan.getDescription());
        assertNotNull(vlan.getTags());

        int count = 0;
        for (VLAN v : vlans) {
            count++;
        }
        assertEquals("Number of vlans returned is incorrect", 5, count);
    }

    @Test
    public void getNetwork() throws CloudException, InternalException{
        new NonStrictExpectations(VSphereNetwork.class) {
            {network.retrieveObjectList(vsphereMock, "networkFolder", null, networkPSpec);
                result = networks;
            }
            {network.retrieveObjectList(vsphereMock, "networkFolder", null, switchPSpec);
                result = switches;
            }
        };

        VLAN vlan = network.getVlan("dvportgroup-56");
        assertEquals("dvportgroup-56", vlan.getProviderVlanId());
        assertEquals("VM Network", vlan.getName());
        assertNull("Vlans are not dc specific. DataCenter should be null", vlan.getProviderDataCenterId());
        assertEquals(VLANState.AVAILABLE, vlan.getCurrentState());
        assertEquals("VM Network (dvportgroup-56)", vlan.getDescription());
        assertNotNull(vlan.getTags());
        assertNotNull(vlan.getTag("switch.uuid"));
        assertEquals("aa11 bb22 cc33", vlan.getTag("switch.uuid"));
    }

    @Test
    public void getFakeNetworkShouldReturnNull() throws CloudException, InternalException{
        new NonStrictExpectations(VSphereNetwork.class) {
            {network.retrieveObjectList(vsphereMock, "networkFolder", null, networkPSpec);
                result = networks;
            }
            {network.retrieveObjectList(vsphereMock, "networkFolder", null, switchPSpec);
                result = switches;
            }
        };

        VLAN vlan = network.getVlan("MyFakeNetwork");
        assertTrue("Vlan returned but id was made up", vlan == null);
    }

    @Test
    public void listVlansShouldNotCallCloudIfVlanCacheIsValid() throws CloudException, InternalException {
        cache.clear(); //make sure cache is empty before we begin

        new Expectations(VSphereNetwork.class) {
            {network.retrieveObjectList(vsphereMock, "networkFolder", null, networkPSpec);
                result = networks;
                times=1;
            }
        };

        network.listVlans();
        network.listVlans();
    }

    @Test
    public void listVlansShouldReturnEmptyListIfCloudReturnsNullObject() throws CloudException, InternalException {
        cache.clear();

        new Expectations(VSphereNetwork.class) {
            {network.retrieveObjectList(vsphereMock, anyString, (List) any, (List) any);
                result = null;
                result = switches;
            }
        };

        Iterable<VLAN> vlans = network.listVlans();
        assertNotNull("Null object not allowed for listVlans, return empty list instead", vlans);
        assertFalse("Cloud returned null but vlan list is not empty", vlans.iterator().hasNext());
        cache.clear(); //make sure cache is empty when we finish
    }

    @Test
    public void listVlansShouldReturnEmptyListIfCloudReturnsEmptyObject() throws CloudException, InternalException {
        cache.clear();

        new Expectations(VSphereNetwork.class) {
            {network.retrieveObjectList(vsphereMock, anyString, (List) any, (List) any);
                result = new RetrieveResult();
                result = switches;
            }
        };

        Iterable<VLAN> networks = network.listVlans();
        assertNotNull("Null object not allowed for listVlans, return empty list instead", networks);
        assertFalse("Cloud returned empty list but vlan list is not empty", networks.iterator().hasNext());
        cache.clear(); //make sure cache is empty when we finish
    }

    @Test
    public void listVlansShouldReturnEmptyListIfCloudReturnsEmptyPropertyList() throws CloudException, InternalException {
        cache.clear();

        new Expectations(VSphereNetwork.class) {
            {network.retrieveObjectList(vsphereMock, anyString, (List) any, (List) any);
                result = networksNoProperties;
                result = switches;
            }
        };

        Iterable<VLAN> vlans = network.listVlans();
        assertNotNull("Null object not allowed for listVlans, return empty list instead", vlans);
        assertFalse("Cloud returned empty property list but vlan list is not empty", vlans.iterator().hasNext());
        cache.clear(); //make sure cache is empty when we finish
    }

    @Test
    public void listVlansShouldReturnEmptyListIfCloudDoesNotReturnSummaryProperty() throws CloudException, InternalException{
        cache.clear();
        new Expectations(VSphereNetwork.class) {
            {network.retrieveObjectList(vsphereMock, anyString, (List) any, (List) any);
                result = networksNoSummaryProperty;
                result = switches;
            }
        };

        Iterable<VLAN> list = network.listVlans();
        assertNotNull("Null object not allowed for listVlans, return empty list instead", list);
        assertFalse("Cloud did not return all mandatory fields (summary), but vlan list is not empty", list.iterator().hasNext());
        cache.clear();
    }

    @Test
    public void listVlansShouldOnlyReturnStandardNetworksIfCloudDoesNotReturnConfigProperty() throws CloudException, InternalException{
        cache.clear();
        new Expectations(VSphereNetwork.class) {
            {network.retrieveObjectList(vsphereMock, anyString, (List) any, (List) any);
                result = networksNoConfigProperty;
                result = switches;
            }
        };

        Iterable<VLAN> list = network.listVlans();
        assertNotNull(list);

        int count = 0;
        for (VLAN v : list) {
            count++;
        }
        assertEquals("Number of vlans returned is incorrect", 2, count);

        cache.clear();
    }

    @Test(expected = NoContextException.class)
    public void listShouldThrowExceptionIfNullContext() throws CloudException, InternalException {
        new Expectations(VSphereNetwork.class) {
            { vsphereMock.getContext(); result = null; }
        };

        network.listVlans();
    }
}
