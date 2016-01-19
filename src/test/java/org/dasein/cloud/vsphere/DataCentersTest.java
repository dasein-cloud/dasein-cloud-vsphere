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

import mockit.*;

import com.vmware.vim25.*;
import org.dasein.cloud.AuthenticationException;
import org.dasein.cloud.CloudException;
import org.dasein.cloud.InternalException;
import org.dasein.cloud.ResourceNotFoundException;
import org.dasein.cloud.compute.AffinityGroup;
import org.dasein.cloud.compute.AffinityGroupFilterOptions;
import org.dasein.cloud.dc.*;
import org.dasein.cloud.util.Cache;
import org.dasein.cloud.util.CacheLevel;
import org.dasein.cloud.vsphere.compute.HostSupport;
import org.dasein.cloud.vsphere.compute.VsphereCompute;
import org.dasein.util.uom.time.Day;
import org.dasein.util.uom.time.Hour;
import org.dasein.util.uom.time.TimePeriod;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * User: daniellemayne
 * Date: 14/09/2015
 * Time: 11:08
 */
@RunWith(JUnit4.class)
public class DataCentersTest extends VsphereTestBase{

    static private RetrieveResult regions;
    static private RetrieveResult datacenters;
    static private RetrieveResult resourcePools;
    static private RetrieveResult storagePools;
    static private RetrieveResult vmFolders;

    static private AffinityGroup[] daseinHosts;
    static private AffinityGroup[] standaloneHost;

    static private RetrieveResult datacentersNoNameProperty;
    static private RetrieveResult datacentersNoStatusProperty;
    static private RetrieveResult resourcePoolsNoNameProperty;
    static private RetrieveResult resourcePoolsNoOwnerProperty;
    static private RetrieveResult storagePoolsNoSummaryProperty;
    static private RetrieveResult vmFoldersNoNameProperty;
    static private RetrieveResult vmFoldersNoParentProperty;
    static private RetrieveResult vmFoldersNoChildEntityProperty;

    private DataCenters dc = null;
    private List<PropertySpec> regPSpecs = null;
    private List<PropertySpec> dcPSpecs = null;
    private List<SelectionSpec> rpSSpecs = null;
    private List<PropertySpec> rpPSpecs = null;
    private List<PropertySpec> spPSpecs = null;
    private List<PropertySpec> vfPSpecs = null;
    private List<PropertySpec> invalidProps = null;

    private Cache<Region> regCache = null;
    private Cache<DataCenter> dcCache = null;
    private Cache<StoragePool> spCache = null;
    private Cache<Folder> vfCache = null;

    @Mocked
    VsphereCompute vsphereComputeMock;
    @Mocked
    HostSupport vsphereAGMock;

    @BeforeClass
    static public void setupFixtures() {
        ObjectManagement om = new ObjectManagement();
        regions = om.readJsonFile("src/test/resources/DataCenters/regions.json", RetrieveResult.class);
        datacenters = om.readJsonFile("src/test/resources/DataCenters/datacenters.json", RetrieveResult.class);
        resourcePools = om.readJsonFile("src/test/resources/DataCenters/resourcePools.json", RetrieveResult.class);
        storagePools = om.readJsonFile("src/test/resources/DataCenters/storagePools.json", RetrieveResult.class);
        vmFolders = om.readJsonFile("src/test/resources/DataCenters/vmFolders.json", RetrieveResult.class);

        daseinHosts = om.readJsonFile("src/test/resources/DataCenters/daseinHosts.json", AffinityGroup[].class);
        standaloneHost = om.readJsonFile("src/test/resources/DataCenters/standaloneHost.json", AffinityGroup[].class);

        datacentersNoNameProperty = om.readJsonFile("src/test/resources/DataCenters/missingNamePropertyDatacenters.json", RetrieveResult.class);
        datacentersNoStatusProperty = om.readJsonFile("src/test/resources/DataCenters/missingStatusPropertyDatacenters.json", RetrieveResult.class);
        resourcePoolsNoNameProperty = om.readJsonFile("src/test/resources/DataCenters/missingNamePropertyResourcePools.json", RetrieveResult.class);
        resourcePoolsNoOwnerProperty = om.readJsonFile("src/test/resources/DataCenters/missingOwnerPropertyResourcePools.json", RetrieveResult.class);
        storagePoolsNoSummaryProperty = om.readJsonFile("src/test/resources/DataCenters/missingSummaryPropertyStoragePools.json", RetrieveResult.class);
        vmFoldersNoNameProperty = om.readJsonFile("src/test/resources/DataCenters/missingNamePropertyVMFolders.json", RetrieveResult.class);
        vmFoldersNoParentProperty = om.readJsonFile("src/test/resources/DataCenters/missingParentPropertyVMFolders.json", RetrieveResult.class);
        vmFoldersNoChildEntityProperty = om.readJsonFile("src/test/resources/DataCenters/missingChildEntityPropertyVMFolders.json", RetrieveResult.class);
    }

    @Before
    public void setUp() throws Exception {
        super.setUp();
        dc = new DataCenters(vsphereMock);
        regPSpecs = dc.getRegionPropertySpec();
        dcPSpecs = dc.getDataCenterPropertySpec();
        rpSSpecs = dc.getResourcePoolSelectionSpec();
        rpPSpecs = dc.getResourcePoolPropertySpec();
        spPSpecs = dc.getStoragePoolPropertySpec();
        vfPSpecs = dc.getVmFolderPropertySpec();

        regCache = Cache.getInstance(vsphereMock, "regions", Region.class, CacheLevel.CLOUD_ACCOUNT, new TimePeriod<Hour>(10, TimePeriod.HOUR));
        dcCache = Cache.getInstance(vsphereMock, "dataCenters", DataCenter.class, CacheLevel.REGION_ACCOUNT, new TimePeriod<Day>(1, TimePeriod.DAY));
        spCache = Cache.getInstance(vsphereMock, "storagePools", StoragePool.class, CacheLevel.CLOUD_ACCOUNT, new TimePeriod<Hour>(10, TimePeriod.HOUR));
        vfCache = Cache.getInstance(vsphereMock, "folders", Folder.class, CacheLevel.CLOUD_ACCOUNT, new TimePeriod<Hour>(10, TimePeriod.HOUR));

        new NonStrictExpectations() {
            { vsphereMock.getComputeServices();
                result = vsphereComputeMock;
            }
            { vsphereComputeMock.getAffinityGroupSupport();
                result = vsphereAGMock;
            }
            { vsphereAGMock.list((AffinityGroupFilterOptions) any);
                result = daseinHosts;
            }
        };
    }

    @Test
    public void listRegions() throws CloudException, InternalException {
        regCache.clear(); //make sure cache is empty before we begin

        new Expectations(DataCenters.class) {
            {dc.retrieveObjectList(vsphereMock, "hostFolder", null, regPSpecs);
                result = regions;
                times=1;
            }
        };

        Iterable<Region> regions = dc.listRegions();
        assertNotNull("Null object returned for listRegions", regions);
        assertTrue("Empty region list returned", regions.iterator().hasNext());
        Region region = regions.iterator().next();
        assertEquals("WTC", region.getName());
        assertEquals("datacenter-21", region.getProviderRegionId());

        int count = 0;
        for (Region r : regions) {
            count++;
        }
        assertEquals("Number of regions returned is incorrect", 1, count);
    }

    @Test
    public void getRegion() throws CloudException, InternalException{
        new NonStrictExpectations(DataCenters.class) {
            {dc.retrieveObjectList(vsphereMock, "hostFolder", null, regPSpecs);
                result = regions;
            }
        };

        Region region = dc.getRegion("datacenter-21");
        assertNotNull("Null object returned for getRegion", region);
        assertEquals("WTC", region.getName());
        assertEquals("datacenter-21", region.getProviderRegionId());
    }

    @Test
    public void getFakeRegionShouldReturnNull() throws CloudException, InternalException{
        new NonStrictExpectations(DataCenters.class) {
            {dc.retrieveObjectList(vsphereMock, "hostFolder", null, regPSpecs);
                result = regions;
            }
        };

        Region region = dc.getRegion("myFakeRegion");
        assertTrue("Region returned but id was made up", region == null);
    }

    @Test
    public void listRegionsShouldNotCallCloudIfRegionCacheIsValid() throws CloudException, InternalException {
        regCache.clear(); //make sure cache is empty before we begin

        new Expectations(DataCenters.class) {
            {dc.retrieveObjectList(vsphereMock, "hostFolder", null, regPSpecs);
                result = regions;
                times=1; //should go to cloud first time only and cache should be used for second call
            }
        };

        dc.listRegions();
        dc.listRegions();
    }

    @Test
    public void listRegionsShouldReturnEmptyListIfCloudReturnsNullObject() throws CloudException, InternalException {
        regCache.clear();

        new Expectations(DataCenters.class) {
            {dc.retrieveObjectList(vsphereMock, anyString, (List) any, (List) any);
                result = null;
                times=1;
            }
        };

        Iterable<Region> regions = dc.listRegions();
        assertNotNull("Null object not allowed for listRegions, return empty list instead", regions);
        assertFalse("Cloud returned null but region list is not empty", regions.iterator().hasNext());
        regCache.clear(); //make sure cache is empty when we finish
    }

    @Test
    public void listRegionsShouldReturnEmptyListIfCloudReturnsEmptyObject() throws CloudException, InternalException {
        regCache.clear();

        new Expectations(DataCenters.class) {
            {dc.retrieveObjectList(vsphereMock, anyString, (List) any, (List) any);
                result = new RetrieveResult();
                times=1;
            }
        };

        Iterable<Region> regions = dc.listRegions();
        assertNotNull("Null object not allowed for listRegions, return empty list instead", regions);
        assertFalse("Cloud returned empty list but region list is not empty", regions.iterator().hasNext());
        regCache.clear(); //make sure cache is empty when we finish
    }

    @Test
    public void listRegionsShouldReturnEmptyListIfCloudReturnsEmptyPropertyList() throws CloudException, InternalException {
        regCache.clear();

        new Expectations(DataCenters.class) {
            {dc.retrieveObjectList(vsphereMock, anyString, (List) any, (List) any);
                RetrieveResult rr = new RetrieveResult();
                ObjectContent oc = new ObjectContent();
                oc.setObj(new ManagedObjectReference());
                rr.getObjects().add(oc);
                result = rr;
                times=1;
            }
        };

        Iterable<Region> regions = dc.listRegions();
        assertNotNull("Null object not allowed for listRegions, return empty list instead", regions);
        assertFalse("Cloud returned empty property list but region list is not empty", regions.iterator().hasNext());
        regCache.clear(); //make sure cache is empty when we finish
    }

    @Test(expected = InternalException.class)
    public void listRegionsShouldThrowExceptionIfRequestContainsInvalidProperty() throws CloudException, InternalException {
        invalidProps = new ArrayList<PropertySpec>();
        PropertySpec propertySpec = new PropertySpec();
        propertySpec.setAll(Boolean.FALSE);
        propertySpec.setType("Datacenter");
        propertySpec.getPathSet().add("name");
        propertySpec.getPathSet().add("config");  //not a property for a Datacenter object
        invalidProps.add(propertySpec);

        new Expectations(DataCenters.class) {
            {dc.retrieveObjectList(vsphereMock, "hostFolder", null, invalidProps);
                result = new InternalException("Invalid Property config for Datacenter", new InvalidPropertyFaultMsg("Invalid Property config for Datacenter", new InvalidProperty()));
            };
        };

        dc.retrieveObjectList(vsphereMock, "hostFolder", null, invalidProps);
    }

    @Test
    public void listDataCenters() throws CloudException, InternalException{
        dcCache.clear(); //make sure cache is empty before we begin

        new Expectations(DataCenters.class) {
            {dc.retrieveObjectList(vsphereMock, "hostFolder", null, regPSpecs);
                result = regions;
                minTimes=0;
            }
            {dc.retrieveObjectList(vsphereMock, "hostFolder", null, dcPSpecs);
                result = datacenters;
                times=1;
            }
        };

        Iterable<DataCenter> dcs = dc.listDataCenters("datacenter-21");
        assertNotNull(dcs);
        assertTrue(dcs.iterator().hasNext());
        DataCenter dataCenter = dcs.iterator().next();
        assertEquals("WTC-Dev-1", dataCenter.getName());
        assertEquals("domain-c26", dataCenter.getProviderDataCenterId());
        assertEquals(true, dataCenter.isActive());
        assertEquals(true, dataCenter.isAvailable());

        int count = 0;
        for (DataCenter center : dcs) {
            count++;
        }
        assertEquals("Number of datacenters returned is incorrect", 2, count);
    }

    @Test
    public void getDataCenter() throws CloudException, InternalException{
        new NonStrictExpectations(DataCenters.class) {
            {dc.retrieveObjectList(vsphereMock, "hostFolder", null, regPSpecs);
                result = regions;
            }
            {dc.retrieveObjectList(vsphereMock, "hostFolder", null, dcPSpecs);
                result = datacenters;
            }
        };

        DataCenter dataCenter = dc.getDataCenter("domain-c70");
        assertNotNull(dataCenter);
        assertEquals("WTC-Dev-2", dataCenter.getName());
        assertEquals("domain-c70", dataCenter.getProviderDataCenterId());
    }

    @Test
    public void getFakeDataCenterShouldReturnNull() throws CloudException, InternalException{
        new NonStrictExpectations(DataCenters.class) {
            {dc.retrieveObjectList(vsphereMock, "hostFolder", null, regPSpecs);
                result = regions;
            }
            {dc.retrieveObjectList(vsphereMock, "hostFolder", null, dcPSpecs);
                result = datacenters;
            }
        };

        DataCenter dataCenter = dc.getDataCenter("myFakeDC");
        assertTrue("DataCenter returned but id was made up", dataCenter == null);
    }

    @Test
    public void listDataCentersShouldNotCallCloudIfDataCenterCacheIsValid() throws CloudException, InternalException {
        dcCache.clear(); //make sure cache is empty before we begin

        new Expectations(DataCenters.class) {
            {dc.retrieveObjectList(vsphereMock, "hostFolder", null, regPSpecs);
                result = regions;
                minTimes=0;
            }
            {dc.retrieveObjectList(vsphereMock, "hostFolder", null, dcPSpecs);
                result = datacenters;
                times=1; //should go to the cloud the first time only
            }
        };

        dc.listDataCenters("datacenter-21");
        dc.listDataCenters("datacenter-21");
    }

    @Test
    public void listDataCentersShouldReturnDummyDCIfCloudReturnsNullObject() throws CloudException, InternalException{
        dcCache.clear(); //make sure cache is empty before we begin

        new Expectations(DataCenters.class) {
            {dc.retrieveObjectList(vsphereMock, "hostFolder", null, regPSpecs);
                result = regions;
                minTimes=0;
            }
            {dc.retrieveObjectList(vsphereMock, anyString, (List) any, (List) any);
                result = null;
                times=1;
            }
        };

        Iterable<DataCenter> dcs = dc.listDataCenters("datacenter-21");
        assertNotNull(dcs);
        assertTrue(dcs.iterator().hasNext());
        DataCenter dataCenter = dcs.iterator().next();
        assertEquals("WTC", dataCenter.getName());
        assertEquals("datacenter-21-a", dataCenter.getProviderDataCenterId());
        assertEquals(true, dataCenter.isActive());
        assertEquals(true, dataCenter.isAvailable());

        int count = 0;
        for (DataCenter center : dcs) {
            count++;
        }
        assertEquals("Number of datacenters returned is incorrect", 1, count);
        dcCache.clear();
    }

    @Test
    public void listDataCentersShouldReturnDummyDCIfCloudReturnsEmptyObject() throws CloudException, InternalException{
        dcCache.clear(); //make sure cache is empty before we begin

        new Expectations(DataCenters.class) {
            {dc.retrieveObjectList(vsphereMock, "hostFolder", null, regPSpecs);
                result = regions;
                minTimes=0;
            }
            {dc.retrieveObjectList(vsphereMock, anyString, (List) any, (List) any);
                result = new RetrieveResult();
                times=1;
            }
        };

        Iterable<DataCenter> dcs = dc.listDataCenters("datacenter-21");
        assertNotNull(dcs);
        assertTrue(dcs.iterator().hasNext());
        DataCenter dataCenter = dcs.iterator().next();
        assertEquals("WTC", dataCenter.getName());
        assertEquals("datacenter-21-a", dataCenter.getProviderDataCenterId());
        assertEquals(true, dataCenter.isActive());
        assertEquals(true, dataCenter.isAvailable());

        int count = 0;
        for (DataCenter center : dcs) {
            count++;
        }
        assertEquals("Number of datacenters returned is incorrect", 1, count);
        dcCache.clear();
    }

    @Test
    public void listDataCentersShouldReturnDummyDCIfCloudReturnsEmptyPropertyList() throws CloudException, InternalException{
        dcCache.clear(); //make sure cache is empty before we begin

        new Expectations(DataCenters.class) {
            {dc.retrieveObjectList(vsphereMock, "hostFolder", null, regPSpecs);
                result = regions;
                minTimes=0;
            }
            {dc.retrieveObjectList(vsphereMock, anyString, (List) any, (List) any);
                RetrieveResult rr = new RetrieveResult();
                ObjectContent oc = new ObjectContent();
                oc.setObj(new ManagedObjectReference());
                rr.getObjects().add(oc);
                result = rr;
                times=1;
            }
        };

        Iterable<DataCenter> dcs = dc.listDataCenters("datacenter-21");
        assertNotNull(dcs);
        assertTrue(dcs.iterator().hasNext());
        DataCenter dataCenter = dcs.iterator().next();
        assertEquals("WTC", dataCenter.getName());
        assertEquals("datacenter-21-a", dataCenter.getProviderDataCenterId());
        assertEquals(true, dataCenter.isActive());
        assertEquals(true, dataCenter.isAvailable());

        int count = 0;
        for (DataCenter center : dcs) {
            count++;
        }
        assertEquals("Number of datacenters returned is incorrect", 1, count);
        dcCache.clear();
    }

    @Test
    public void listDataCentersShouldReturnDummyDCIfCloudDoesNotReturnNameProperty() throws CloudException, InternalException{
        dcCache.clear(); //make sure cache is empty before we begin

        new Expectations(DataCenters.class) {
            {dc.retrieveObjectList(vsphereMock, "hostFolder", null, regPSpecs);
                result = regions;
                minTimes=0;
            }
            {dc.retrieveObjectList(vsphereMock, anyString, (List) any, (List) any);
                result = datacentersNoNameProperty;
                times=1;
            }
        };

        Iterable<DataCenter> dcs = dc.listDataCenters("datacenter-21");
        assertNotNull(dcs);
        assertTrue(dcs.iterator().hasNext());
        DataCenter dataCenter = dcs.iterator().next();
        assertEquals("WTC", dataCenter.getName());
        assertEquals("datacenter-21-a", dataCenter.getProviderDataCenterId());
        assertEquals(true, dataCenter.isActive());
        assertEquals(true, dataCenter.isAvailable());

        int count = 0;
        for (DataCenter center : dcs) {
            count++;
        }
        assertEquals("Number of datacenters returned is incorrect", 1, count);
        dcCache.clear();
    }

    @Test
    public void listDataCentersShouldReturnDummyDCIfCloudDoesNotReturnStatusProperty() throws CloudException, InternalException{
        dcCache.clear(); //make sure cache is empty before we begin

        new Expectations(DataCenters.class) {
            {dc.retrieveObjectList(vsphereMock, "hostFolder", null, regPSpecs);
                result = regions;
                minTimes=0;
            }
            {dc.retrieveObjectList(vsphereMock, anyString, (List) any, (List) any);
                result = datacentersNoStatusProperty;
                times=1;
            }
        };

        Iterable<DataCenter> dcs = dc.listDataCenters("datacenter-21");
        assertNotNull(dcs);
        assertTrue(dcs.iterator().hasNext());
        DataCenter dataCenter = dcs.iterator().next();
        assertEquals("WTC", dataCenter.getName());
        assertEquals("datacenter-21-a", dataCenter.getProviderDataCenterId());
        assertEquals(true, dataCenter.isActive());
        assertEquals(true, dataCenter.isAvailable());

        int count = 0;
        for (DataCenter center : dcs) {
            count++;
        }
        assertEquals("Number of datacenters returned is incorrect", 1, count);
        dcCache.clear();
    }

    @Test(expected = ResourceNotFoundException.class)
    public void listDataCentersShouldThrowExceptionIfRegionNotValid() throws CloudException, InternalException{
        new Expectations(DataCenters.class) {
            {dc.retrieveObjectList(vsphereMock, "hostFolder", null, regPSpecs);
                result = regions;
                minTimes=0;    //cache may be valid so may not be called
            }
        };

        dc.listDataCenters("MyFakeRegionId");
    }

    @Test
    public void listResourcePools() throws CloudException, InternalException{
        new Expectations(DataCenters.class) {
            {dc.retrieveObjectList(vsphereMock, "hostFolder", rpSSpecs, rpPSpecs);
                result = resourcePools;
            }
        };

        Iterable<ResourcePool> resourcePools = dc.listResourcePools("domain-c26");
        assertNotNull(resourcePools);
        assertTrue(resourcePools.iterator().hasNext());
        ResourcePool resourcePool = resourcePools.iterator().next();
        assertEquals("Cluster1-Resource_Pool1", resourcePool.getName());
        assertEquals("resgroup-76", resourcePool.getProvideResourcePoolId());
        assertEquals("domain-c26", resourcePool.getDataCenterId());
        assertEquals(true, resourcePool.isAvailable());

        int count = 0;
        for (ResourcePool r : resourcePools) {
            count++;
        }
        assertEquals("Number of resource pools returned is incorrect", 2, count);
    }

    @Test
    public void getResourcePool() throws CloudException, InternalException{
        new NonStrictExpectations(DataCenters.class) {
            {dc.retrieveObjectList(vsphereMock, "hostFolder", rpSSpecs, rpPSpecs);
                result = resourcePools;
            }
        };

        ResourcePool resourcePool = dc.getResourcePool("resgroup-78");
        assertNotNull(resourcePool);
        assertEquals("Cluster2-Resource_Pool1", resourcePool.getName());
        assertEquals("resgroup-78", resourcePool.getProvideResourcePoolId());
        assertEquals("domain-c70", resourcePool.getDataCenterId());
        assertEquals(true, resourcePool.isAvailable());
    }

    @Test
    public void getFakeResourcePoolShouldReturnNull() throws CloudException, InternalException{
        new NonStrictExpectations(DataCenters.class) {
            {dc.retrieveObjectList(vsphereMock, "hostFolder", rpSSpecs, rpPSpecs);
                result = resourcePools;
            }
        };

        ResourcePool resourcePool = dc.getResourcePool("myFakeRP");
        assertTrue("ResourcePool returned but id was made up", resourcePool == null);
    }

    @Test
    public void listResourcePoolsShouldReturnEmptyListIfCloudReturnsNullObject() throws CloudException, InternalException{
        new NonStrictExpectations(DataCenters.class) {
            {dc.retrieveObjectList(vsphereMock, anyString, (List) any, (List) any);
                result = null;
            }
        };

        Iterable<ResourcePool> list = dc.listResourcePools(null);
        assertNotNull("Null object not allowed for listResourcePools, return empty list instead", list);
        assertFalse("Null object returned from cloud, but resource pool list is not empty", list.iterator().hasNext());
    }

    @Test
    public void listResourcePoolsShouldReturnEmptyListIfCloudReturnsEmptyObject() throws CloudException, InternalException{
        new Expectations(DataCenters.class) {
            {dc.retrieveObjectList(vsphereMock, anyString, (List) any, (List) any);
                result = new RetrieveResult();
                times=1;
            }
        };

        Iterable<ResourcePool> list = dc.listResourcePools(null);
        assertNotNull("Null object not allowed for listResourcePools, return empty list instead", list);
        assertFalse("Empty object list returned from cloud, but resource pool list is not empty", list.iterator().hasNext());
    }

    @Test
    public void listResourcePoolsShouldReturnEmptyListIfCloudReturnsEmptyPropertyList() throws CloudException, InternalException{
        new Expectations(DataCenters.class) {
            {dc.retrieveObjectList(vsphereMock, anyString, (List) any, (List) any);
                RetrieveResult rr = new RetrieveResult();
                ObjectContent oc = new ObjectContent();
                oc.setObj(new ManagedObjectReference());
                rr.getObjects().add(oc);
                result = rr;
                times=1;
            }
        };

        Iterable<ResourcePool> list = dc.listResourcePools(null);
        assertNotNull("Null object not allowed for listResourcePools, return empty list instead", list);
        assertFalse("Empty property list returned from cloud, but resource pool list is not empty", list.iterator().hasNext());
    }

    @Test
    public void listResourcePoolsShouldReturnEmptyListIfCloudDoesNotReturnNameProperty() throws CloudException, InternalException{
        new Expectations(DataCenters.class) {
            {dc.retrieveObjectList(vsphereMock, anyString, (List) any, (List) any);
                result = resourcePoolsNoNameProperty;
                times=1;
            }
        };

        Iterable<ResourcePool> list = dc.listResourcePools(null);
        assertNotNull("Null object not allowed for listResourcePools, return empty list instead", list);
        assertFalse("Cloud did not return all mandatory fields (name), but resource pool list is not empty", list.iterator().hasNext());
    }

    @Test
    public void listResourcePoolsShouldReturnEmptyListIfCloudDoesNotReturnOwnerProperty() throws CloudException, InternalException{
        new Expectations(DataCenters.class) {
            {dc.retrieveObjectList(vsphereMock, anyString, (List) any, (List) any);
                result = resourcePoolsNoOwnerProperty;
                times=1;
            }
        };

        Iterable<ResourcePool> list = dc.listResourcePools(null);
        assertNotNull("Null object not allowed for listResourcePools, return empty list instead", list);
        assertFalse("Cloud did not return all mandatory fields (owner), but resource pool list is not empty", list.iterator().hasNext());
    }

    @Test
    public void listStoragePools() throws CloudException, InternalException{
        spCache.clear();

        new Expectations(DataCenters.class) {
            { dc.retrieveObjectList(vsphereMock, "datastoreFolder", null, spPSpecs);
                result = storagePools;
                times=1;
            }
        };

        Iterable<StoragePool> sps = dc.listStoragePools();
        assertNotNull(sps);
        assertTrue(sps.iterator().hasNext());
        StoragePool storagePool = sps.iterator().next();
        assertEquals("shared-datastore-1", storagePool.getStoragePoolName());
        assertEquals("datastore-37", storagePool.getStoragePoolId());
        assertEquals("datacenter-21", storagePool.getRegionId());
        assertNull("Storage pool is shared, datacenter id should be null", storagePool.getDataCenterId());
        assertNull("Storage pool is shared, affinity group id should be null", storagePool.getAffinityGroupId());
        assertEquals(3902537, storagePool.getCapacity().longValue(), 1);
        assertEquals(1885831, storagePool.getProvisioned().longValue(), 1);
        assertEquals(2016706, storagePool.getFreeSpace().longValue(), 1);

        int count = 0;
        for (StoragePool sp : sps) {
            count++;
        }
        assertEquals("Number of storage pools returned is incorrect", 6, count);
    }

    @Test
    public void getStoragePool() throws CloudException, InternalException{
        new NonStrictExpectations(DataCenters.class) {
            { dc.retrieveObjectList(vsphereMock, "datastoreFolder", null, spPSpecs);
                result = storagePools;
            }
        };

        StoragePool storagePool = dc.getStoragePool("datastore-44");
        assertEquals("local-storage-1 (1)", storagePool.getStoragePoolName());
        assertEquals("datastore-44", storagePool.getStoragePoolId());
        assertEquals("datacenter-21", storagePool.getRegionId());
        assertEquals("domain-c26", storagePool.getDataCenterId());
        assertEquals("host-43", storagePool.getAffinityGroupId());
        assertEquals(285212, storagePool.getCapacity().longValue(), 1);
        assertEquals(996, storagePool.getProvisioned().longValue(), 1);
        assertEquals(284216, storagePool.getFreeSpace().longValue(), 1);
    }

    @Test
    public void getFakeStoragePoolShouldReturnNull() throws CloudException, InternalException{
        new NonStrictExpectations(DataCenters.class) {
            { dc.retrieveObjectList(vsphereMock, "datastoreFolder", null, spPSpecs);
                result = storagePools;
            }
        };

        StoragePool pool = dc.getStoragePool("myFakeSP");
        assertTrue("StoragePool returned but id was made up", pool == null);
    }

    @Test
    public void listStoragePoolsShouldNotCallCloudIfStoragePoolCacheIsValid() throws CloudException, InternalException {
        spCache.clear();

        new Expectations(DataCenters.class) {
            { dc.retrieveObjectList(vsphereMock, "datastoreFolder", null, spPSpecs);
                result = storagePools;
                times=1;
            }
        };

        dc.listStoragePools();
        dc.listStoragePools();
        spCache.clear();
    }

    @Test
    public void listStoragePoolsShouldReturnEmptyListIfCloudReturnsNullObject() throws CloudException, InternalException{
        spCache.clear();
        new NonStrictExpectations(DataCenters.class) {
            {dc.retrieveObjectList(vsphereMock, anyString, (List) any, (List) any);
                result = null;
            }
        };

        Iterable<StoragePool> list = dc.listStoragePools();
        assertNotNull("Null object not allowed for listStoragePools, return empty list instead", list);
        assertFalse("Null object returned from cloud, but storage pool list is not empty", list.iterator().hasNext());
        spCache.clear();
    }

    @Test
    public void listStoragePoolsShouldReturnEmptyListIfCloudReturnsEmptyObject() throws CloudException, InternalException{
        spCache.clear();
        new Expectations(DataCenters.class) {
            {dc.retrieveObjectList(vsphereMock, anyString, (List) any, (List) any);
                result = new RetrieveResult();
                times=1;
            }
        };

        Iterable<StoragePool> list = dc.listStoragePools();
        assertNotNull("Null object not allowed for listStoragePools, return empty list instead", list);
        assertFalse("Empty object returned from cloud, but storage pool list is not empty", list.iterator().hasNext());
        spCache.clear();
    }

    @Test
    public void listStoragePoolsShouldReturnEmptyListIfCloudReturnsEmptyPropertyList() throws CloudException, InternalException{
        spCache.clear();
        new Expectations(DataCenters.class) {
            {dc.retrieveObjectList(vsphereMock, anyString, (List) any, (List) any);
                RetrieveResult rr = new RetrieveResult();
                ObjectContent oc = new ObjectContent();
                oc.setObj(new ManagedObjectReference());
                rr.getObjects().add(oc);
                result = rr;
                times=1;
            }
        };

        Iterable<StoragePool> list = dc.listStoragePools();
        assertNotNull("Null object not allowed for listStoragePools, return empty list instead", list);
        assertFalse("Empty property list returned from cloud, but storage pool list is not empty", list.iterator().hasNext());
        spCache.clear();
    }

    @Test
    public void listStoragePoolsShouldReturnEmptyListIfCloudDoesNotReturnSummaryProperty() throws CloudException, InternalException{
        spCache.clear();
        new Expectations(DataCenters.class) {
            {dc.retrieveObjectList(vsphereMock, anyString, (List) any, (List) any);
                result = storagePoolsNoSummaryProperty;
                times=1;
            }
        };

        Iterable<StoragePool> list = dc.listStoragePools();
        assertNotNull("Null object not allowed for listStoragePools, return empty list instead", list);
        assertFalse("Cloud did not return all mandatory fields (summary), but storage pool list is not empty", list.iterator().hasNext());
        spCache.clear();
    }

    @Test
    public void getStoragePoolShouldReturnObjectWithNoDatacenterIdIfMatchToHostIsNotFound() throws CloudException, InternalException{
        spCache.clear(); //force regeneration of storage pool list

        new Expectations(DataCenters.class) {
            { dc.retrieveObjectList(vsphereMock, "datastoreFolder", null, spPSpecs);
                result = storagePools;
                times=1;
            }
            { vsphereAGMock.list((AffinityGroupFilterOptions) any);
                result = standaloneHost;
            }
        };

        StoragePool storagePool = dc.getStoragePool("datastore-44");
        assertNull("DatacenterId should be null when a host cannot be found that matches the datastore host mount point.", storagePool.getDataCenterId());
        spCache.clear(); //force next test to regenerate real list
    }

    @Test
    public void getStoragePoolShouldReturnObjectWithNoDatacenterIdIfHostListIsEmpty() throws CloudException, InternalException{
        spCache.clear();

        new Expectations(DataCenters.class) {
            { dc.retrieveObjectList(vsphereMock, "datastoreFolder", null, spPSpecs);
                result = storagePools;
                minTimes=0; //may or may not already be cached
            }
            { vsphereAGMock.list((AffinityGroupFilterOptions) any);
                result = new ArrayList<AffinityGroup>();
            }
        };

        StoragePool storagePool = dc.getStoragePool("datastore-44");
        assertNull("DatacenterId should be null when a host cannot be found that matches the datastore host mount point.", storagePool.getDataCenterId());
        spCache.clear(); //force next test to regenerate real list
    }

    @Test
    public void listVmFolders() throws CloudException, InternalException{
        vfCache.clear();

        new Expectations(DataCenters.class) {
            { dc.retrieveObjectList(vsphereMock, "vmFolder", null, vfPSpecs);
                result = vmFolders;
                times=1;
            }
        };

        Iterable<Folder> folders = dc.listVMFolders();
        assertNotNull(folders);
        assertTrue(folders.iterator().hasNext());
        Folder folder = folders.iterator().next();
        assertEquals("Folder1", folder.getName());
        assertEquals("group-d80", folder.getId());
        assertEquals(FolderType.VM, folder.getType());
        assertNull("Parent folder should be null", folder.getParent());
        assertNotNull("Children should not be null, return empty list instead", folder.getChildren());

        int count = 0;
        for (Folder folder1 : folders) {
            count++;
        }
        assertEquals("Number of folders returned is incorrect", 7, count);
    }

    @Test
    public void getVmFolder() throws CloudException, InternalException{
        new NonStrictExpectations(DataCenters.class) {
            { dc.retrieveObjectList(vsphereMock, "vmFolder", null, vfPSpecs);
                result = vmFolders;
            }
        };

        Folder folder = dc.getVMFolder("group-v81");
        assertEquals("VM Folder1", folder.getName());
        assertEquals("group-v81", folder.getId());
        assertEquals(FolderType.VM, folder.getType());
        assertEquals("vm", folder.getParent().getName());
        assertEquals(1, folder.getChildren().size());
        assertEquals("DMNestedFolder", folder.getChildren().get(0).getName());
    }

    @Test
    public void getFakeVmFolderShouldReturnNull() throws CloudException, InternalException{
        new NonStrictExpectations(DataCenters.class) {
            { dc.retrieveObjectList(vsphereMock, "vmFolder", null, vfPSpecs);
                result = vmFolders;
            }
        };

        Folder folder = dc.getVMFolder("myFakeFolder");
        assertTrue("Folder returned but id was made up", folder == null);
    }

    @Test
    public void listVMFoldersShouldNotCallCloudIfVmFolderCacheIsValid() throws CloudException, InternalException {
        vfCache.clear();

        new Expectations(DataCenters.class) {
            {
                dc.retrieveObjectList(vsphereMock, "vmFolder", null, vfPSpecs);
                result = vmFolders;
                times=1;
            }
        };

        dc.listVMFolders();
        dc.listVMFolders();
        vfCache.clear();
    }

    @Test
    public void listVMFoldersShouldReturnEmptyListIfCloudReturnsNullObject() throws CloudException, InternalException{
        vfCache.clear();
        new NonStrictExpectations(DataCenters.class) {
            {dc.retrieveObjectList(vsphereMock, anyString, (List) any, (List) any);
                result = null;
            }
        };

        Iterable<Folder> list = dc.listVMFolders();
        assertNotNull("Null object not allowed for listVMFolders, return empty list instead", list);
        assertFalse("Null object returned from cloud, but vm folder list is not empty", list.iterator().hasNext());
        vfCache.clear();
    }

    @Test
    public void listVMFoldersShouldReturnEmptyListIfCloudReturnsEmptyObject() throws CloudException, InternalException{
        vfCache.clear();
        new Expectations(DataCenters.class) {
            {dc.retrieveObjectList(vsphereMock, anyString, (List) any, (List) any);
                result = new RetrieveResult();
                times=1;
            }
        };

        Iterable<Folder> list = dc.listVMFolders();
        assertNotNull("Null object not allowed for listVMFolders, return empty list instead", list);
        assertFalse("Empty object returned from cloud, but vm folder list is not empty", list.iterator().hasNext());
        vfCache.clear();
    }

    @Test
    public void listVMFoldersShouldReturnEmptyListIfCloudReturnsEmptyPropertyList() throws CloudException, InternalException{
        vfCache.clear();
        new Expectations(DataCenters.class) {
            {dc.retrieveObjectList(vsphereMock, anyString, (List) any, (List) any);
                RetrieveResult rr = new RetrieveResult();
                ObjectContent oc = new ObjectContent();
                oc.setObj(new ManagedObjectReference());
                rr.getObjects().add(oc);
                result = rr;
                times=1;
            }
        };

        Iterable<Folder> list = dc.listVMFolders();
        assertNotNull("Null object not allowed for listVMFolders, return empty list instead", list);
        assertFalse("Empty property list returned from cloud, but vm folder list is not empty", list.iterator().hasNext());
        vfCache.clear();
    }

    @Test
    public void listVMFoldersShouldReturnEmptyListIfCloudDoesNotReturnNameProperty() throws CloudException, InternalException{
        vfCache.clear();
        new Expectations(DataCenters.class) {
            {dc.retrieveObjectList(vsphereMock, anyString, (List) any, (List) any);
                result = vmFoldersNoNameProperty;
                times=1;
            }
        };

        Iterable<Folder> list = dc.listVMFolders();
        assertNotNull("Null object not allowed for listVMFolders, return empty list instead", list);
        assertFalse("Cloud did not return all mandatory fields (name), but vm folder list is not empty", list.iterator().hasNext());
        vfCache.clear();
    }

    @Test
    public void getVMFolderShouldReturnNullParentIfCloudDoesNotReturnParentProperty() throws CloudException, InternalException{
        vfCache.clear();
        new Expectations(DataCenters.class) {
            {dc.retrieveObjectList(vsphereMock, anyString, (List) any, (List) any);
                result = vmFoldersNoParentProperty;
                times=1;
            }
        };

        Folder folder = dc.getVMFolder("group-v81");
        assertNull("Cloud did not return parent field, but vm folder parent is set", folder.getParent());
        vfCache.clear();
    }

    @Test
    public void getVMFolderShouldReturnEmptyChildrenListIfCloudDoesNotReturnChildEntityProperty() throws CloudException, InternalException{
        vfCache.clear();
        new Expectations(DataCenters.class) {
            {dc.retrieveObjectList(vsphereMock, anyString, (List) any, (List) any);
                result = vmFoldersNoChildEntityProperty;
                times=1;
            }
        };

        Folder folder = dc.getVMFolder("group-v81");
        assertNotNull("Cloud did not return childEntity property, but vm folder list is null instead of empty", folder.getChildren());
        assertTrue("No child entities were returned but child entity list is not empty", folder.getChildren().size() == 0);
        vfCache.clear();
    }
}