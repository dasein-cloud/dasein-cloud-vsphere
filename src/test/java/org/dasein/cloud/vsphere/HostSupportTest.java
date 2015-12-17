package org.dasein.cloud.vsphere;

import com.vmware.vim25.*;
import mockit.Expectations;
import mockit.NonStrictExpectations;
import org.dasein.cloud.AuthenticationException;
import org.dasein.cloud.CloudException;
import org.dasein.cloud.InternalException;
import org.dasein.cloud.compute.AffinityGroup;
import org.dasein.cloud.compute.AffinityGroupFilterOptions;
import org.dasein.cloud.util.Cache;
import org.dasein.cloud.util.CacheLevel;
import org.dasein.cloud.vsphere.compute.HostSupport;
import org.dasein.util.uom.time.Day;
import org.dasein.util.uom.time.TimePeriod;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;
import static org.junit.Assert.assertFalse;

/**
 * User: daniellemayne
 * Date: 18/09/2015
 * Time: 14:52
 */
public class HostSupportTest extends VsphereTestBase{
    static private RetrieveResult hosts;
    static private RetrieveResult hostsWithNoNameProperty;
    static private RetrieveResult hostsWithNoHostProperty;
    static private RetrieveResult hostsWithNoProperties;

    private HostSupport hs = null;
    private List<PropertySpec> hostPSpec = null;
    private List<SelectionSpec> hostSSpec = null;

    private Cache<AffinityGroup> cache = null;

    @BeforeClass
    static public void setupFixtures() {
        ObjectManagement om = new ObjectManagement();
        hosts = om.readJsonFile("src/test/resources/HostSupport/hosts.json", RetrieveResult.class);
        hostsWithNoNameProperty = om.readJsonFile("src/test/resources/HostSupport/missingNamePropertyHosts.json", RetrieveResult.class);
        hostsWithNoHostProperty = om.readJsonFile("src/test/resources/HostSupport/missingHostPropertyHosts.json", RetrieveResult.class);
        hostsWithNoProperties = om.readJsonFile("src/test/resources/HostSupport/missingPropertiesHosts.json", RetrieveResult.class);
    }

    @Before
    public void setUp() throws Exception {
        super.setUp();
        hs = new HostSupport(vsphereMock);
        hostPSpec = hs.getHostPSpec();
        hostSSpec = hs.getHostSSpec();
        cache = Cache.getInstance(vsphereMock, "affinityGroups", AffinityGroup.class, CacheLevel.REGION_ACCOUNT, new TimePeriod<Day>(1, TimePeriod.DAY));
    }

    @Test
    public void listHosts() throws CloudException, InternalException {
        cache.clear();

        new Expectations(HostSupport.class) {
            {hs.retrieveObjectList(vsphereMock, "hostFolder", hostSSpec, hostPSpec);
                result = hosts;
                times=1;
            }
        };

        Iterable<AffinityGroup> hosts = hs.list(AffinityGroupFilterOptions.getInstance());
        assertNotNull(hosts);
        assertTrue(hosts.iterator().hasNext());
        AffinityGroup host = hosts.iterator().next();
        assertEquals("host-43", host.getAffinityGroupId());
        assertEquals("esquin-dev-vrtx-1-bl02.esquin.dev", host.getAffinityGroupName());
        assertEquals("domain-c26", host.getDataCenterId());
        assertEquals(0, host.getCreationTimestamp(), 0);
        assertEquals("Affinity group for esquin-dev-vrtx-1-bl02.esquin.dev", host.getDescription());
        assertNotNull(host.getTags());

        int count = 0;
        for (AffinityGroup ag : hosts) {
            count++;
        }
        assertEquals("Number of hosts returned is incorrect", 4, count);
    }

    @Test
    public void getHost() throws CloudException, InternalException{
        new NonStrictExpectations(HostSupport.class) {
            {hs.retrieveObjectList(vsphereMock, "hostFolder", hostSSpec, hostPSpec);
                result = hosts;
            }
        };

        AffinityGroup host = hs.get("host-72");
        assertEquals("host-72", host.getAffinityGroupId());
        assertEquals("esquin-dev-vrtx-1-bl03.esquin.dev", host.getAffinityGroupName());
        assertEquals("domain-c70", host.getDataCenterId());
        assertEquals(0, host.getCreationTimestamp(), 0);
        assertEquals("Affinity group for esquin-dev-vrtx-1-bl03.esquin.dev", host.getDescription());
        assertNotNull(host.getTags());
    }

    @Test
    public void getFakeHostShouldReturnNull() throws CloudException, InternalException{
        new NonStrictExpectations(HostSupport.class) {
            {hs.retrieveObjectList(vsphereMock, "hostFolder", hostSSpec, hostPSpec);
                result = hosts;
            }
        };

        AffinityGroup ag = hs.get("myFakeHost");
        assertTrue("Host returned but id was made up", ag == null);
    }

    @Test
    public void listShouldNotCallCloudIfHostCacheIsValid() throws CloudException, InternalException {
        cache.clear(); //make sure cache is empty before we begin

        new Expectations(HostSupport.class) {
            {hs.retrieveObjectList(vsphereMock, "hostFolder", hostSSpec, hostPSpec);
                result = hosts;
                times=1;
            }
        };

        hs.list(AffinityGroupFilterOptions.getInstance());
        hs.list(AffinityGroupFilterOptions.getInstance());
    }

    @Test
    public void listShouldReturnEmptyListIfCloudReturnsNullObject() throws CloudException, InternalException {
        cache.clear();

        new Expectations(HostSupport.class) {
            {hs.retrieveObjectList(vsphereMock, anyString, (List) any, (List) any);
                result = null;
                times=1;
            }
        };

        Iterable<AffinityGroup> hosts = hs.list(AffinityGroupFilterOptions.getInstance());
        assertNotNull("Null object not allowed for list, return empty list instead", hosts);
        assertFalse("Cloud returned null but host list is not empty", hosts.iterator().hasNext());
        cache.clear(); //make sure cache is empty when we finish
    }

    @Test
    public void listShouldReturnEmptyListIfCloudReturnsEmptyObject() throws CloudException, InternalException {
        cache.clear();

        new Expectations(HostSupport.class) {
            {hs.retrieveObjectList(vsphereMock, anyString, (List) any, (List) any);
                result = new RetrieveResult();
                times=1;
            }
        };

        Iterable<AffinityGroup> hosts = hs.list(AffinityGroupFilterOptions.getInstance());
        assertNotNull("Null object not allowed for list, return empty list instead", hosts);
        assertFalse("Cloud returned empty list but host list is not empty", hosts.iterator().hasNext());
        cache.clear(); //make sure cache is empty when we finish
    }

    @Test
    public void listShouldReturnEmptyListIfCloudReturnsEmptyPropertyList() throws CloudException, InternalException {
        cache.clear();

        new Expectations(HostSupport.class) {
            {hs.retrieveObjectList(vsphereMock, anyString, (List) any, (List) any);
                result = hostsWithNoProperties;
                times=1;
            }
        };

        Iterable<AffinityGroup> hosts = hs.list(AffinityGroupFilterOptions.getInstance());
        assertNotNull("Null object not allowed for list, return empty list instead", hosts);
        assertFalse("Cloud returned empty property list but host list is not empty", hosts.iterator().hasNext());
        cache.clear(); //make sure cache is empty when we finish
    }

    @Test
    public void listShouldReturnEmptyListIfCloudDoesNotReturnNameProperty() throws CloudException, InternalException{
        cache.clear();
        new Expectations(HostSupport.class) {
            {hs.retrieveObjectList(vsphereMock, anyString, (List) any, (List) any);
                result = hostsWithNoNameProperty;
                times=1;
            }
        };

        Iterable<AffinityGroup> list = hs.list(AffinityGroupFilterOptions.getInstance());
        assertNotNull("Null object not allowed for list, return empty list instead", list);
        assertFalse("Cloud did not return all mandatory fields (name), but host list is not empty", list.iterator().hasNext());
        cache.clear();
    }

    @Test
    public void listShouldReturnEmptyListIfCloudDoesNotReturnClusterHostProperty() throws CloudException, InternalException{
        cache.clear();
        new Expectations(HostSupport.class) {
            {hs.retrieveObjectList(vsphereMock, anyString, (List) any, (List) any);
                result = hostsWithNoHostProperty;
                times=1;
            }
        };

        Iterable<AffinityGroup> list = hs.list(AffinityGroupFilterOptions.getInstance());
        assertNotNull("Null object not allowed for list, return empty list instead", list);
        assertFalse("Cloud did not return all mandatory fields (host), but host list is not empty", list.iterator().hasNext());
        cache.clear();
    }
}
