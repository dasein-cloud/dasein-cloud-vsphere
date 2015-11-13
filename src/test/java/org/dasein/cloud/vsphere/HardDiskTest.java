package org.dasein.cloud.vsphere;

import com.vmware.vim25.*;
import mockit.Expectations;
import mockit.Mocked;
import mockit.NonStrictExpectations;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.map.SerializationConfig;
import org.dasein.cloud.CloudException;
import org.dasein.cloud.InternalException;
import org.dasein.cloud.compute.VirtualMachine;
import org.dasein.cloud.compute.Volume;
import org.dasein.cloud.compute.VolumeCreateOptions;
import org.dasein.cloud.dc.ResourcePool;
import org.dasein.cloud.dc.StoragePool;
import org.dasein.cloud.vsphere.compute.HardDisk;
import org.dasein.cloud.vsphere.compute.Vm;
import org.dasein.cloud.vsphere.compute.VsphereCompute;
import org.dasein.util.uom.storage.Storage;
import org.dasein.util.uom.storage.StorageUnit;
import org.dasein.util.uom.time.TimePeriod;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;

/**
 * User: daniellemayne
 * Date: 09/10/2015
 * Time: 10:41
 */
public class HardDiskTest extends VsphereTestBase {
    private ObjectManagement om = new ObjectManagement();
    private RetrieveResult hardDisks = om.readJsonFile("src/test/resources/HardDisk/hardDisks.json", RetrieveResult.class);
    private final ResourcePool[] resourcePools = om.readJsonFile("src/test/resources/HardDisk/resourcePools.json", ResourcePool[].class);
    private final RetrieveResult datastores = om.readJsonFile("src/test/resources/HardDisk/datastores.json", RetrieveResult.class);
    private final StoragePool[] storagePools = om.readJsonFile("src/test/resources/HardDisk/storagePools.json", StoragePool[].class);
    private final ManagedObjectReference task = om.readJsonFile("src/test/resources/HardDisk/task.json", ManagedObjectReference.class);
    private final PropertyChange searchResult = om.readJsonFile("src/test/resources/HardDisk/searchResult.json", PropertyChange.class);

    private final RetrieveResult hardDisksNoProperties = om.readJsonFile("src/test/resources/HardDisk/hardDiskNoProperties.json", RetrieveResult.class);
    private final RetrieveResult dcStoragePools = om.readJsonFile("src/test/resources/DataCenters/storagePools.json", RetrieveResult.class);
    private RetrieveResult newHardDisks = om.readJsonFile("src/test/resources/HardDisk/newHardDisks.json", RetrieveResult.class);
    private RetrieveResult postAttachHardDisks = om.readJsonFile("src/test/resources/HardDisk/postAttachHardDisks.json", RetrieveResult.class);
    private RetrieveResult postDetachHardDisks = om.readJsonFile("src/test/resources/HardDisk/postDetachHardDisks.json", RetrieveResult.class);
    private final PropertyChange postRemoveHardDisks = om.readJsonFile("src/test/resources/HardDisk/postRemoveHardDisks.json", PropertyChange.class);

    private HardDisk hd = null;
    private VsphereMethod method = null;
    private List<PropertySpec> hardDiskPSpec = null;
    private List<PropertySpec> datastorePSpec = null;

    @Mocked
    VsphereCompute vsphereComputeMock;
    @Mocked
    Vm vmMock;
    @Mocked
    DataCenters dcMock;

    @Before
    public void setUp() throws Exception {
        super.setUp();
        hd = new HardDisk(vsphereMock);
        method = new VsphereMethod(vsphereMock);
        hardDiskPSpec = hd.getHardDiskPSpec();
        datastorePSpec = hd.getDatastorePropertySpec();
        ObjectManagement om = new ObjectManagement();
        om.mapper.enableDefaultTypingAsProperty(ObjectMapper.DefaultTyping.NON_FINAL, "type");
        om.mapper.configure(SerializationConfig.Feature.FAIL_ON_EMPTY_BEANS, false);
        hardDisks = om.readJsonFile("src/test/resources/HardDisk/hardDisks.json", RetrieveResult.class);
        newHardDisks = om.readJsonFile("src/test/resources/HardDisk/newHardDisks.json", RetrieveResult.class);
        postAttachHardDisks = om.readJsonFile("src/test/resources/HardDisk/postAttachHardDisks.json", RetrieveResult.class);
        postDetachHardDisks = om.readJsonFile("src/test/resources/HardDisk/postDetachHardDisks.json", RetrieveResult.class);
    }

    @Test
    public void listVolumes() throws CloudException, InternalException {
        new Expectations(HardDisk.class) {
            {hd.retrieveObjectList(vsphereMock, "vmFolder", null, hardDiskPSpec);
                result = hardDisks;
            }
            {hd.getAllResourcePoolsIncludingRoot();
                result = resourcePools;
            }
            {hd.retrieveObjectList(vsphereMock, "datastoreFolder", null, datastorePSpec);
                result = datastores;
            }
            {hd.listStoragePools();
                result = storagePools;
            }
            {hd.searchDatastores(vsphereMock, (ManagedObjectReference) any, anyString, null);
                result = task;
            }
        };

        new Expectations(VsphereMethod.class) {
            {method.getOperationComplete((ManagedObjectReference) any, (TimePeriod) any, anyInt);
                result = true;
                times=6;
            }
            {method.getTaskResult();
                result = searchResult;
                times=6;
            }
        };

        Iterable<Volume> volumes = hd.listVolumes();
        assertNotNull("Null object returned for listVolumes", volumes);
        assertTrue("Empty volume list returned", volumes.iterator().hasNext());
        Volume volume = volumes.iterator().next();
        assertNotNull("Null volume found", volume);
        assertEquals("dmTesting7Oct_2.vmdk", volume.getProviderVolumeId());
        assertEquals("Hard disk 1", volume.getName());
        assertEquals("datacenter-21", volume.getProviderRegionId());
        assertEquals("52,428,800 KB", volume.getDescription());
        assertEquals("vm-2318", volume.getProviderVirtualMachineId());
        assertEquals("0", volume.getDeviceId());
        assertEquals(52, volume.getSizeInGigabytes());
        assertTrue(volume.isRootVolume());
        assertNotNull(volume.getTags());
        String filePath = volume.getTag("filePath");
        assertEquals("[shared-datastore-1] dmTesting7Oct/dmTesting7Oct_2.vmdk", filePath);

        int count = 0;
        for (Volume v : volumes) {
            count++;
        }

        assertEquals("Number of volumes is incorrect", 25, count);
    }

    @Test
    public void getVolume() throws CloudException, InternalException {
        new Expectations(HardDisk.class) {
            {hd.retrieveObjectList(vsphereMock, "vmFolder", null, hardDiskPSpec);
                result = hardDisks;
            }
            {hd.getAllResourcePoolsIncludingRoot();
                result = resourcePools;
            }
            {hd.retrieveObjectList(vsphereMock, "datastoreFolder", null, datastorePSpec);
                result = datastores;
            }
            {hd.listStoragePools();
                result = storagePools;
            }
            {hd.searchDatastores(vsphereMock, (ManagedObjectReference) any, anyString, null);
                result = task;
            }
        };

        new Expectations(VsphereMethod.class) {
            {method.getOperationComplete((ManagedObjectReference) any, (TimePeriod) any, anyInt);
                result = true;
                times=6;
            }
            {method.getTaskResult();
                result = searchResult;
                times=6;
            }
        };

        Volume volume = hd.getVolume("dmTesting7Oct_1.vmdk");
        assertNotNull("Valid volume object should not be null", volume);
        assertEquals("dmTesting7Oct_1.vmdk", volume.getProviderVolumeId());
        assertEquals("dmTesting7Oct_1.vmdk", volume.getName());
        assertEquals("domain-c26", volume.getProviderDataCenterId());
        assertEquals("datacenter-21", volume.getProviderRegionId());
        assertEquals("dmTesting7Oct_1.vmdk", volume.getDescription());
        assertNull("Standalone file should not have vm id", volume.getProviderVirtualMachineId());
        assertNull("Standalone file should not have device id", volume.getDeviceId());
        assertEquals(0, volume.getSizeInGigabytes());
        assertNotNull(volume.getTags());
        String filePath = volume.getTag("filePath");
        assertEquals("[shared-datastore-1] dmTesting7Oct/dmTesting7Oct_1.vmdk", filePath);
        assertFalse(volume.isRootVolume());
    }

    @Test
    public void getFakeVolumeShouldReturnNull() throws CloudException, InternalException {
        new Expectations(HardDisk.class) {
            {hd.retrieveObjectList(vsphereMock, "vmFolder", null, hardDiskPSpec);
                result = hardDisks;
            }
            {hd.getAllResourcePoolsIncludingRoot();
                result = resourcePools;
            }
            {hd.retrieveObjectList(vsphereMock, "datastoreFolder", null, datastorePSpec);
                result = datastores;
            }
            {hd.listStoragePools();
                result = storagePools;
            }
            {hd.searchDatastores(vsphereMock, (ManagedObjectReference) any, anyString, null);
                result = task;
            }
        };

        new Expectations(VsphereMethod.class) {
            {method.getOperationComplete((ManagedObjectReference) any, (TimePeriod) any, anyInt);
                result = true;
                times=6;
            }
            {method.getTaskResult();
                result = searchResult;
                times=6;
            }
        };

        Volume volume = hd.getVolume("MyFakeVolume");
        assertNull("Volume returned but id was made up", volume);
    }

    @Test
    public void listVolumesShouldReturnVolumeFilesOnlyIfCloudReturnsNullVmObject() throws CloudException, InternalException {
        new Expectations(HardDisk.class) {
            {hd.retrieveObjectList(vsphereMock, "vmFolder", null, hardDiskPSpec);
                result = null;
                times=1;
            }
            {hd.retrieveObjectList(vsphereMock, "datastoreFolder", null, datastorePSpec);
                result = datastores;
            }
            {hd.listStoragePools();
                result = storagePools;
            }
            {hd.searchDatastores(vsphereMock, (ManagedObjectReference) any, anyString, null);
                result = task;
            }
        };

        new Expectations(VsphereMethod.class) {
            {method.getOperationComplete((ManagedObjectReference) any, (TimePeriod) any, anyInt);
                result = true;
                times=6;
            }
            {method.getTaskResult();
                result = searchResult;
                times=6;
            }
        };

        Iterable<Volume> volumes = hd.listVolumes();
        assertNotNull("Null object not allowed for listVolumes, return empty list instead", volumes);
        assertTrue("Cloud returned null attached volumes but volume list should still contain standalone files", volumes.iterator().hasNext());
        for (Volume v : volumes ) {
            assertNull("Vm id should be null for files. "+v.getName()+" has "+v.getProviderVirtualMachineId(), v.getProviderVirtualMachineId());
        }
    }

    @Test
    public void listVolumesShouldReturnVolumeFilesOnlyIfCloudReturnsEmptyVmObject() throws CloudException, InternalException {
        new Expectations(HardDisk.class) {
            {hd.retrieveObjectList(vsphereMock, "vmFolder", null, hardDiskPSpec);
                result = new RetrieveResult();
                times = 1;
            }
            {hd.getAllResourcePoolsIncludingRoot();
                result = resourcePools;
            }
            {hd.retrieveObjectList(vsphereMock, "datastoreFolder", null, datastorePSpec);
                result = datastores;
            }
            {hd.listStoragePools();
                result = storagePools;
            }
            {hd.searchDatastores(vsphereMock, (ManagedObjectReference) any, anyString, null);
                result = task;
            }
        };

        new Expectations(VsphereMethod.class) {
            {method.getOperationComplete((ManagedObjectReference) any, (TimePeriod) any, anyInt);
                result = true;
                times=6;
            }
            {method.getTaskResult();
                result = searchResult;
                times=6;
            }
        };

        Iterable<Volume> volumes = hd.listVolumes();
        assertNotNull("Null object not allowed for listVolumes, return empty list instead", volumes);
        assertTrue("Cloud returned empty list of attached volumes but volume list should still contain standalone files", volumes.iterator().hasNext());
        for (Volume v : volumes) {
            assertNull("Vm id should be null for files. "+v.getName()+" has "+v.getProviderVirtualMachineId(), v.getProviderVirtualMachineId());
        }
    }

    @Test
    public void listVolumesShouldReturnVolumeFilesOnlyIfCloudReturnsEmptyVmPropertyList() throws CloudException, InternalException {
        new Expectations(HardDisk.class) {
            {hd.retrieveObjectList(vsphereMock, "vmFolder", null, hardDiskPSpec);
                result = hardDisksNoProperties;
                times=1;
            }
            {hd.getAllResourcePoolsIncludingRoot();
                result = resourcePools;
            }
            {hd.retrieveObjectList(vsphereMock, "datastoreFolder", null, datastorePSpec);
                result = datastores;
            }
            {hd.listStoragePools();
                result = storagePools;
            }
            {hd.searchDatastores(vsphereMock, (ManagedObjectReference) any, anyString, null);
                result = task;
            }
        };

        new Expectations(VsphereMethod.class) {
            {method.getOperationComplete((ManagedObjectReference) any, (TimePeriod) any, anyInt);
                result = true;
                times=6;
            }
            {method.getTaskResult();
                result = searchResult;
                times=6;
            }
        };

        Iterable<Volume> volumes = hd.listVolumes();
        assertNotNull("Null object not allowed for listVolumes, return empty list instead", volumes);
        assertTrue("Cloud returned empty vm property list of attached volumes but volume list should still contain standalone files", volumes.iterator().hasNext());
        for (Volume v : volumes) {
            assertNull("Vm id should be null for files. "+v.getName()+" has "+v.getProviderVirtualMachineId(), v.getProviderVirtualMachineId());
        }
    }

    @Test
    public void listVolumesShouldReturnAttachedVolumesWithNoDatacenterIfResourcePoolListIsMissing() throws CloudException, InternalException{
        new Expectations(HardDisk.class) {
            {hd.retrieveObjectList(vsphereMock, "vmFolder", null, hardDiskPSpec);
                result = hardDisks;
                times=1;
            }
            {hd.getAllResourcePoolsIncludingRoot();
                result = new ArrayList<ResourcePool>();
            }
            {hd.retrieveObjectList(vsphereMock, "datastoreFolder", null, datastorePSpec);
                result = datastores;
            }
            {hd.listStoragePools();
                result = storagePools;
            }
            {hd.searchDatastores(vsphereMock, (ManagedObjectReference) any, anyString, null);
                result = task;
            }
        };

        new Expectations(VsphereMethod.class) {
            {method.getOperationComplete((ManagedObjectReference) any, (TimePeriod) any, anyInt);
                result = true;
                times=6;
            }
            {method.getTaskResult();
                result = searchResult;
                times=6;
            }
        };

        Iterable<Volume> volumes = hd.listVolumes();
        assertNotNull("Null object not allowed for listVolumes, return empty list instead", volumes);
        assertTrue("Resource pool map not found but list should still contain volumes", volumes.iterator().hasNext());
        for (Volume v : volumes) {
            if (v.getProviderVirtualMachineId() != null) {
                assertNull("Datacenter id should be null for attached volumes. " + v.getName() + " has " + v.getProviderDataCenterId(), v.getProviderDataCenterId());
            }
        }
    }

    @Test
    public void listVolumesShouldOnlyReturnAttachedVolumesIfCloudDoesNotReturnDatastores() throws CloudException, InternalException{
        new Expectations(HardDisk.class) {
            {hd.retrieveObjectList(vsphereMock, "vmFolder", null, hardDiskPSpec);
                result = hardDisks;
                times=1;
            }
            {hd.getAllResourcePoolsIncludingRoot();
                result = resourcePools;
            }
            {hd.retrieveObjectList(vsphereMock, "datastoreFolder", null, datastorePSpec);
                result = null;
            }
            {hd.listStoragePools();
                result = storagePools;
                times=0;//don't list storage pools if no datastores to iterate over
            }
            {hd.searchDatastores(vsphereMock, (ManagedObjectReference) any, anyString, null);
                result = task;
                times = 0;//no datastores returned to iterate over
            }
        };

        new Expectations(VsphereMethod.class) {
            {method.getOperationComplete((ManagedObjectReference) any, (TimePeriod) any, anyInt);
                result = true;
                times=0;
            }
            {method.getTaskResult();
                result = searchResult;
                times=0;
            }
        };

        Iterable<Volume> volumes = hd.listVolumes();
        assertNotNull("Null object not allowed for listVolumes, return empty list instead", volumes);
        assertTrue("Datastores not found but list should still contain attached volumes", volumes.iterator().hasNext());
        int count = 0;
        for (Volume v : volumes) {
            count ++;
            assertNotNull("Virtual machine id should not be null for attached volumes. "+v.getName()+" has null", v.getProviderVirtualMachineId());
        }
        assertEquals("Number of attached volumes is incorrect", 11, count);
    }

    @Test
    public void listVolumesShouldReturnStandaloneVolumeFilesWithNoDatacenterIfCloudDoesNotReturnStoragePools() throws CloudException, InternalException{
        new Expectations(HardDisk.class) {
            {hd.retrieveObjectList(vsphereMock, "vmFolder", null, hardDiskPSpec);
                result = hardDisks;
                times=1;
            }
            {hd.getAllResourcePoolsIncludingRoot();
                result = resourcePools;
            }
            {hd.retrieveObjectList(vsphereMock, "datastoreFolder", null, datastorePSpec);
                result = datastores;
            }
            {hd.listStoragePools();
                result = new ArrayList<StoragePool>();
            }
            {hd.searchDatastores(vsphereMock, (ManagedObjectReference) any, anyString, null);
                result = task;
            }
        };

        new Expectations(VsphereMethod.class) {
            {method.getOperationComplete((ManagedObjectReference) any, (TimePeriod) any, anyInt);
                result = true;
                times=6;
            }
            {method.getTaskResult();
                result = searchResult;
                times=6;
            }
        };

        Iterable<Volume> volumes = hd.listVolumes();
        assertNotNull("Null object not allowed for listVolumes, return empty list instead", volumes);
        assertTrue("Storage pool map not found but list should still contain volumes", volumes.iterator().hasNext());
        for (Volume v : volumes) {
            if (v.getProviderVirtualMachineId() == null) {
                assertNull("Datacenter id should be null for all volumes. " + v.getName() + " has " + v.getProviderDataCenterId(), v.getProviderDataCenterId());
            }
        }
    }

    @Test
    public void listVolumesShouldReturnAttachedVolumesOnlyIfCloudDoesNotReturnSearchDatastoreTask() throws CloudException, InternalException{
        new Expectations(HardDisk.class) {
            {hd.retrieveObjectList(vsphereMock, "vmFolder", null, hardDiskPSpec);
                result = hardDisks;
                times=1;
            }
            {hd.getAllResourcePoolsIncludingRoot();
                result = resourcePools;
            }
            {hd.retrieveObjectList(vsphereMock, "datastoreFolder", null, datastorePSpec);
                result = datastores;
            }
            {hd.listStoragePools();
                result = storagePools;
            }
            {hd.searchDatastores(vsphereMock, (ManagedObjectReference) any, anyString, null);
                result = null;
            }
        };

        new Expectations(VsphereMethod.class) {
            {method.getOperationComplete((ManagedObjectReference) any, (TimePeriod) any, anyInt);
                result = true;
                times=0;  //no task to query
            }
            {method.getTaskResult();
                result = searchResult;
                times=0; //no task to query
            }
        };

        Iterable<Volume> volumes = hd.listVolumes();
        assertNotNull("Null object not allowed for listVolumes, return empty list instead", volumes);
        assertTrue("Search datastores task not found but list should still contain attached volumes", volumes.iterator().hasNext());
        int count = 0;
        for (Volume v : volumes) {
            count ++;
            assertNotNull("Virtual machine id should not be null for attached volumes. " + v.getName() + " has null", v.getProviderVirtualMachineId());
        }
        assertEquals("Number of attached volumes is incorrect", 11, count);
    }

    @Test
    public void listVolumesShouldReturnAttachedVolumesOnlyIfSearchDatastoreTaskReturnsFalse() throws CloudException, InternalException{
        new Expectations(HardDisk.class) {
            {hd.retrieveObjectList(vsphereMock, "vmFolder", null, hardDiskPSpec);
                result = hardDisks;
                times=1;
            }
            {hd.getAllResourcePoolsIncludingRoot();
                result = resourcePools;
            }
            {hd.retrieveObjectList(vsphereMock, "datastoreFolder", null, datastorePSpec);
                result = datastores;
            }
            {hd.listStoragePools();
                result = storagePools;
            }
            {hd.searchDatastores(vsphereMock, (ManagedObjectReference) any, anyString, null);
                result = task;
            }
        };

        new Expectations(VsphereMethod.class) {
            {method.getOperationComplete((ManagedObjectReference) any, (TimePeriod) any, anyInt);
                result = false;
                times=6;
            }
            {method.getTaskResult();
                result = searchResult;
                times=0;  //search operation failed so no result to query
            }
        };

        Iterable<Volume> volumes = hd.listVolumes();
        assertNotNull("Null object not allowed for listVolumes, return empty list instead", volumes);
        assertTrue("Search datastores task not found but list should still contain attached volumes", volumes.iterator().hasNext());
        int count = 0;
        for (Volume v : volumes) {
            count ++;
            assertNotNull("Virtual machine id should not be null for attached volumes. " + v.getName() + " has null", v.getProviderVirtualMachineId());
        }
        assertEquals("Number of attached volumes is incorrect", 11, count);
    }

    @Test
    public void listVolumesShouldReturnAttachedVolumesOnlyIfCloudReturnsEmptySearchResult() throws CloudException, InternalException{
        new Expectations(HardDisk.class) {
            {hd.retrieveObjectList(vsphereMock, "vmFolder", null, hardDiskPSpec);
                result = hardDisks;
                times=1;
            }
            {hd.getAllResourcePoolsIncludingRoot();
                result = resourcePools;
            }
            {hd.retrieveObjectList(vsphereMock, "datastoreFolder", null, datastorePSpec);
                result = datastores;
            }
            {hd.listStoragePools();
                result = storagePools;
            }
            {hd.searchDatastores(vsphereMock, (ManagedObjectReference) any, anyString, null);
                result = task;
            }
        };

        new Expectations(VsphereMethod.class) {
            {method.getOperationComplete((ManagedObjectReference) any, (TimePeriod) any, anyInt);
                result = true;
                times=6;
            }
            {method.getTaskResult();
                result = new PropertyChange();
                times=6;
            }
        };

        Iterable<Volume> volumes = hd.listVolumes();
        assertNotNull("Null object not allowed for listVolumes, return empty list instead", volumes);
        assertTrue("Search result not found but list should still contain attached volumes", volumes.iterator().hasNext());
        int count = 0;
        for (Volume v : volumes) {
            count ++;
            assertNotNull("Virtual machine id should not be null for attached volumes. " + v.getName() + " has null", v.getProviderVirtualMachineId());
        }
        assertEquals("Number of attached volumes is incorrect", 11, count);
    }

    @Test(expected = NoContextException.class)
    public void listVolumesShouldThrowExceptionIfNullContext() throws CloudException, InternalException {
        new Expectations(HardDisk.class) {
            { vsphereMock.getContext(); result = null; }
        };

        hd.listVolumes();
    }

    @Test(expected = CloudException.class)
    public void listVolumesShouldThrowExceptionIfNullRegion() throws CloudException, InternalException {
        new Expectations(HardDisk.class) {
            { providerContextMock.getRegionId(); result = null; }
        };

        hd.listVolumes();
    }

    @Test
    public void attachVolume() throws CloudException, InternalException {
        new NonStrictExpectations() {
            {vsphereMock.getComputeServices();
                result = vsphereComputeMock;
            }
            {vsphereComputeMock.getVirtualMachineSupport();
                result = vmMock;
            }
            {vmMock.getVirtualMachine(anyString);
                result = new VirtualMachine();
            }
            {vmMock.reconfigVMTask((ManagedObjectReference) any, (VirtualMachineConfigSpec) any);
                result = new ManagedObjectReference();
            }
        };

        new Expectations(HardDisk.class) {
            {hd.retrieveObjectList(vsphereMock, "vmFolder", null, hardDiskPSpec);
                result = hardDisks;
                result = hardDisks;
                result = postAttachHardDisks;
            }
            {hd.getAllResourcePoolsIncludingRoot();
                result = resourcePools;
                times = 2;
            }
            {hd.retrieveObjectList(vsphereMock, "datastoreFolder", null, datastorePSpec);
                result = datastores;
                times = 2;
            }
            {hd.listStoragePools();
                result = storagePools;
                times = 2;
            }
            {hd.searchDatastores(vsphereMock, (ManagedObjectReference) any, anyString, null);
                result = task;
                times = 12;
            }
        };

        new Expectations(VsphereMethod.class) {
            {method.getOperationComplete((ManagedObjectReference) any, (TimePeriod) any, anyInt);
                result = true;
                times=13;
            }
            {method.getTaskResult();
                result = searchResult;
                times=12;
            }
        };

        hd.attach("dmTesting7Oct_1.vmdk", "vm-2318", "1");
        Volume v = hd.getVolume("dmTesting7Oct_1.vmdk");
        assertNotNull(v);
        assertEquals("Volume not attached to correct vm", "vm-2318", v.getProviderVirtualMachineId());
    }

    @Test(expected = CloudException.class)
    public void attachVolumeShouldThrowCloudExceptionIfVmIsNull() throws CloudException, InternalException {
        new NonStrictExpectations() {
            {vsphereMock.getComputeServices();
                result = vsphereComputeMock;
            }
            {vsphereComputeMock.getVirtualMachineSupport();
                result = vmMock;
            }
            {vmMock.getVirtualMachine(anyString);
                result = null;
            }
        };

        hd.attach("dmTesting7Oct_1.vmdk", "vm-0001", "1");
    }

    @Test(expected = CloudException.class)
    public void attachVolumeShouldThrowCloudExceptionIfVolumeIsNull() throws CloudException, InternalException {
        new NonStrictExpectations() {
            {vsphereMock.getComputeServices();
                result = vsphereComputeMock;
            }
            {vsphereComputeMock.getVirtualMachineSupport();
                result = vmMock;
            }
            {vmMock.getVirtualMachine(anyString);
                result = new VirtualMachine();
            }
        };

        new Expectations(HardDisk.class) {
            {hd.retrieveObjectList(vsphereMock, "vmFolder", null, hardDiskPSpec);
                result = hardDisks;
            }
            {hd.getAllResourcePoolsIncludingRoot();
                result = resourcePools;
            }
            {hd.retrieveObjectList(vsphereMock, "datastoreFolder", null, datastorePSpec);
                result = datastores;
            }
            {hd.listStoragePools();
                result = storagePools;
            }
            {hd.searchDatastores(vsphereMock, (ManagedObjectReference) any, anyString, null);
                result = task;
                times = 6;
            }
        };

        new Expectations(VsphereMethod.class) {
            {method.getOperationComplete((ManagedObjectReference) any, (TimePeriod) any, anyInt);
                result = true;
                times=6;
            }
            {method.getTaskResult();
                result = searchResult;
                times=6;
            }
        };

        hd.attach("MyFakeVolume", "vm-2318", "1");
    }

    @Test(expected = CloudException.class)
    public void attachVolumeShouldThrowCloudExceptionIfOperationIsNotSuccessful() throws CloudException, InternalException {
        new NonStrictExpectations() {
            {vsphereMock.getComputeServices();
                result = vsphereComputeMock;
            }
            {vsphereComputeMock.getVirtualMachineSupport();
                result = vmMock;
            }
            {vmMock.getVirtualMachine(anyString);
                result = new VirtualMachine();
            }
            {vmMock.reconfigVMTask((ManagedObjectReference) any, (VirtualMachineConfigSpec) any);
                result = new ManagedObjectReference();
            }
        };

        new Expectations(HardDisk.class) {
            {hd.retrieveObjectList(vsphereMock, "vmFolder", null, hardDiskPSpec);
                result = hardDisks;
                times=2;
            }
            {hd.getAllResourcePoolsIncludingRoot();
                result = resourcePools;
            }
            {hd.retrieveObjectList(vsphereMock, "datastoreFolder", null, datastorePSpec);
                result = datastores;
            }
            {hd.listStoragePools();
                result = storagePools;
            }
            {hd.searchDatastores(vsphereMock, (ManagedObjectReference) any, anyString, null);
                result = task;
            }
        };

        new Expectations(VsphereMethod.class) {
            {method.getOperationComplete((ManagedObjectReference) any, (TimePeriod) any, anyInt);
                result = true;
                result = true;
                result = true;
                result = true;
                result = true;
                result = true;
                result = false;
            }
            {method.getTaskResult();
                result = searchResult;
                times=6;
            }
            {method.getTaskError().getVal();
                result = "Attach failed";
            }
        };

        hd.attach("dmTesting7Oct_1.vmdk", "vm-2318", "1");
    }

    @Test(expected = CloudException.class)
    public void attachVolumeShouldThrowExceptionIfVolumeIsAlreadyAttached() throws CloudException, InternalException {
        new NonStrictExpectations() {
            {vsphereMock.getComputeServices();
                result = vsphereComputeMock;
            }
            {vsphereComputeMock.getVirtualMachineSupport();
                result = vmMock;
            }
            {vmMock.getVirtualMachine(anyString);
                result = new VirtualMachine();
            }
            {vmMock.reconfigVMTask((ManagedObjectReference) any, (VirtualMachineConfigSpec) any);
                result = new ManagedObjectReference();
            }
        };

        new Expectations(HardDisk.class) {
            {hd.retrieveObjectList(vsphereMock, "vmFolder", null, hardDiskPSpec);
                result = hardDisks;
            }
            {hd.getAllResourcePoolsIncludingRoot();
                result = resourcePools;
            }
            {hd.retrieveObjectList(vsphereMock, "datastoreFolder", null, datastorePSpec);
                result = datastores;
            }
            {hd.listStoragePools();
                result = storagePools;
            }
            {hd.searchDatastores(vsphereMock, (ManagedObjectReference) any, anyString, null);
                result = task;
            }
        };

        new Expectations(VsphereMethod.class) {
            {method.getOperationComplete((ManagedObjectReference) any, (TimePeriod) any, anyInt);
                result = true;
                times=6;
            }
            {method.getTaskResult();
                result = searchResult;
                times=6;
            }
        };

        hd.attach("dmTesting7Oct_4.vmdk", "vm-2318", "1");
    }

    @Test
    public void detachVolume() throws CloudException, InternalException {
        new NonStrictExpectations() {
            {vsphereMock.getComputeServices();
                result = vsphereComputeMock;
            }
            {vsphereComputeMock.getVirtualMachineSupport();
                result = vmMock;
            }
            {vmMock.getVirtualMachine(anyString);
                result = new VirtualMachine();
            }
            {vmMock.reconfigVMTask((ManagedObjectReference) any, (VirtualMachineConfigSpec) any);
                result = new ManagedObjectReference();
            }
        };

        new Expectations(HardDisk.class) {
            {hd.retrieveObjectList(vsphereMock, "vmFolder", null, hardDiskPSpec);
                result = hardDisks;
                result = hardDisks;
                result = postDetachHardDisks;
            }
            {hd.getAllResourcePoolsIncludingRoot();
                result = resourcePools;
                times = 2;
            }
            {hd.retrieveObjectList(vsphereMock, "datastoreFolder", null, datastorePSpec);
                result = datastores;
                times = 2;
            }
            {hd.listStoragePools();
                result = storagePools;
                times = 2;
            }
            {hd.searchDatastores(vsphereMock, (ManagedObjectReference) any, anyString, null);
                result = task;
                times = 12;
            }
        };

        new Expectations(VsphereMethod.class) {
            {method.getOperationComplete((ManagedObjectReference) any, (TimePeriod) any, anyInt);
                result = true;
                times=13;
            }
            {method.getTaskResult();
                result = searchResult;
                times=12;
            }
        };

        hd.detach("dmTesting7Oct_4.vmdk", true);
        Volume v = hd.getVolume("dmTesting7Oct_4.vmdk");
        assertNotNull(v);
        assertNull("Detach succeeded without error but volume still has vm id", v.getProviderVirtualMachineId());
    }

    @Test(expected = CloudException.class)
    public void detachVolumeShouldThrowCloudExceptionIfVolumeIsNull() throws CloudException, InternalException {
        new NonStrictExpectations() {
            {vsphereMock.getComputeServices();
                result = vsphereComputeMock;
            }
            {vsphereComputeMock.getVirtualMachineSupport();
                result = vmMock;
            }
            {vmMock.getVirtualMachine(anyString);
                result = new VirtualMachine();
            }
        };

        new Expectations(HardDisk.class) {
            {hd.retrieveObjectList(vsphereMock, "vmFolder", null, hardDiskPSpec);
                result = hardDisks;
            }
            {hd.getAllResourcePoolsIncludingRoot();
                result = resourcePools;
            }
            {hd.retrieveObjectList(vsphereMock, "datastoreFolder", null, datastorePSpec);
                result = datastores;
            }
            {hd.listStoragePools();
                result = storagePools;
            }
            {hd.searchDatastores(vsphereMock, (ManagedObjectReference) any, anyString, null);
                result = task;
                times = 6;
            }
        };

        new Expectations(VsphereMethod.class) {
            {method.getOperationComplete((ManagedObjectReference) any, (TimePeriod) any, anyInt);
                result = true;
                times=6;
            }
            {method.getTaskResult();
                result = searchResult;
                times=6;
            }
        };

        hd.detach("MyFakeVolume", true);
    }

    @Test(expected = CloudException.class)
    public void detachVolumeShouldThrowCloudExceptionIfVmIsNull() throws CloudException, InternalException {
        new NonStrictExpectations() {
            {vsphereMock.getComputeServices();
                result = vsphereComputeMock;
            }
            {vsphereComputeMock.getVirtualMachineSupport();
                result = vmMock;
            }
            {vmMock.getVirtualMachine(anyString);
                result = null;
            }
        };

        new Expectations(HardDisk.class) {
            {hd.retrieveObjectList(vsphereMock, "vmFolder", null, hardDiskPSpec);
                result = hardDisks;
            }
            {hd.getAllResourcePoolsIncludingRoot();
                result = resourcePools;
            }
            {hd.retrieveObjectList(vsphereMock, "datastoreFolder", null, datastorePSpec);
                result = datastores;
            }
            {hd.listStoragePools();
                result = storagePools;
            }
            {hd.searchDatastores(vsphereMock, (ManagedObjectReference) any, anyString, null);
                result = task;
                times = 6;
            }
        };

        new Expectations(VsphereMethod.class) {
            {method.getOperationComplete((ManagedObjectReference) any, (TimePeriod) any, anyInt);
                result = true;
                times=6;
            }
            {method.getTaskResult();
                result = searchResult;
                times=6;
            }
        };

        hd.detach("dmTesting7Oct_4.vmdk", true);
    }

    @Test(expected = CloudException.class)
    public void detachVolumeShouldThrowCloudExceptionIfOperationIsNotSuccessful() throws CloudException, InternalException {
        new NonStrictExpectations() {
            {vsphereMock.getComputeServices();
                result = vsphereComputeMock;
            }
            {vsphereComputeMock.getVirtualMachineSupport();
                result = vmMock;
            }
            {vmMock.getVirtualMachine(anyString);
                result = new VirtualMachine();
            }
            {vmMock.reconfigVMTask((ManagedObjectReference) any, (VirtualMachineConfigSpec) any);
                result = new ManagedObjectReference();
            }
        };

        new Expectations(HardDisk.class) {
            {hd.retrieveObjectList(vsphereMock, "vmFolder", null, hardDiskPSpec);
                result = hardDisks;
                times=2;
            }
            {hd.getAllResourcePoolsIncludingRoot();
                result = resourcePools;
            }
            {hd.retrieveObjectList(vsphereMock, "datastoreFolder", null, datastorePSpec);
                result = datastores;
            }
            {hd.listStoragePools();
                result = storagePools;
            }
            {hd.searchDatastores(vsphereMock, (ManagedObjectReference) any, anyString, null);
                result = task;
            }
        };

        new Expectations(VsphereMethod.class) {
            {method.getOperationComplete((ManagedObjectReference) any, (TimePeriod) any, anyInt);
                result = true;
                result = true;
                result = true;
                result = true;
                result = true;
                result = true;
                result = false;
            }
            {method.getTaskResult();
                result = searchResult;
                times=6;
            }
            {method.getTaskError().getVal();
                result = "detach failed";
            }
        };

        hd.detach("dmTesting7Oct_4.vmdk", true);
    }

    @Test(expected = CloudException.class)
    public void detachVolumeShouldThrowExceptionIfVolumeIsAlreadyDetached() throws CloudException, InternalException {
        new NonStrictExpectations() {
            {vsphereMock.getComputeServices();
                result = vsphereComputeMock;
            }
            {vsphereComputeMock.getVirtualMachineSupport();
                result = vmMock;
            }
            {vmMock.getVirtualMachine(anyString);
                result = new VirtualMachine();
            }
            {vmMock.reconfigVMTask((ManagedObjectReference) any, (VirtualMachineConfigSpec) any);
                result = new ManagedObjectReference();
            }
        };

        new Expectations(HardDisk.class) {
            {hd.retrieveObjectList(vsphereMock, "vmFolder", null, hardDiskPSpec);
                result = hardDisks;
            }
            {hd.getAllResourcePoolsIncludingRoot();
                result = resourcePools;
            }
            {hd.retrieveObjectList(vsphereMock, "datastoreFolder", null, datastorePSpec);
                result = datastores;
            }
            {hd.listStoragePools();
                result = storagePools;
            }
            {hd.searchDatastores(vsphereMock, (ManagedObjectReference) any, anyString, null);
                result = task;
            }
        };

        new Expectations(VsphereMethod.class) {
            {method.getOperationComplete((ManagedObjectReference) any, (TimePeriod) any, anyInt);
                result = true;
                times=6;
            }
            {method.getTaskResult();
                result = searchResult;
                times=6;
            }
        };

        hd.detach("dmTesting7Oct_1.vmdk", true);
    }

    @Test
    public void createVolume() throws CloudException, InternalException {
        final List<PropertySpec> spPSpecs = dcMock.getStoragePoolPropertySpec();
        new NonStrictExpectations() {
            {vsphereMock.getComputeServices();
                result = vsphereComputeMock;
            }
            {vsphereComputeMock.getVirtualMachineSupport();
                result = vmMock;
            }
            {vmMock.getVirtualMachine(anyString);
                result = new VirtualMachine();
            }
            {vmMock.reconfigVMTask((ManagedObjectReference) any, (VirtualMachineConfigSpec) any);
                result = new ManagedObjectReference();
            }
            {dcMock.retrieveObjectList(vsphereMock, "datastoreFolder", null, spPSpecs);
                result = dcStoragePools;
            }
        };

        new Expectations(HardDisk.class) {
            {
                hd.retrieveObjectList(vsphereMock, "vmFolder", null, hardDiskPSpec);
                result = hardDisks;
                result = newHardDisks;
            }
        };

        new Expectations(VsphereMethod.class) {
            {method.getOperationComplete((ManagedObjectReference) any, (TimePeriod) any, anyInt);
                result = true;
                times=1;
            }
        };
        VolumeCreateOptions options = VolumeCreateOptions.getInstance(new Storage<StorageUnit>(2, Storage.GIGABYTE), "myNewDisk", "myNewDiskDescription");
        options.withVirtualMachineId("vm-2318");
        String newVolume = hd.createVolume(options);
        assertNotNull(newVolume);
        assertEquals("New volume id does not match expected", "myNewDisk.vmdk", newVolume);
    }

    @Test(expected = CloudException.class)
    public void createVolumeShouldThrowExceptionIfVmIdIsNull() throws CloudException, InternalException {
        VolumeCreateOptions options = VolumeCreateOptions.getInstance(new Storage<StorageUnit>(2, Storage.GIGABYTE), "myNewDisk", "myNewDiskDescription");
        hd.createVolume(options);
    }

    @Test(expected = CloudException.class)
    public void createVolumeShouldThrowExceptionIfVmIsNull() throws CloudException, InternalException {
        final List<PropertySpec> spPSpecs = dcMock.getStoragePoolPropertySpec();
        new NonStrictExpectations() {
            {vsphereMock.getComputeServices();
                result = vsphereComputeMock;
            }
            {vsphereComputeMock.getVirtualMachineSupport();
                result = vmMock;
            }
            {vmMock.getVirtualMachine(anyString);
                result = null;
            }
        };
        VolumeCreateOptions options = VolumeCreateOptions.getInstance(new Storage<StorageUnit>(2, Storage.GIGABYTE), "myNewDisk", "myNewDiskDescription");
        options.withVirtualMachineId("MyFakeVm");
        hd.createVolume(options);
    }

    @Test(expected = CloudException.class)
    public void createVolumeShouldThrowExceptionIfOperationIsNotSuccessful() throws CloudException, InternalException {
        final List<PropertySpec> spPSpecs = dcMock.getStoragePoolPropertySpec();
        new NonStrictExpectations() {
            {vsphereMock.getComputeServices();
                result = vsphereComputeMock;
            }
            {vsphereComputeMock.getVirtualMachineSupport();
                result = vmMock;
            }
            {vmMock.getVirtualMachine(anyString);
                result = new VirtualMachine();
            }
            {vmMock.reconfigVMTask((ManagedObjectReference) any, (VirtualMachineConfigSpec) any);
                result = new ManagedObjectReference();
            }
            {dcMock.retrieveObjectList(vsphereMock, "datastoreFolder", null, spPSpecs);
                result = dcStoragePools;
            }
        };

        new Expectations(HardDisk.class) {
            {
                hd.retrieveObjectList(vsphereMock, "vmFolder", null, hardDiskPSpec);
                result = hardDisks;
                result = newHardDisks;
            }
        };

        new Expectations(VsphereMethod.class) {
            {method.getOperationComplete((ManagedObjectReference) any, (TimePeriod) any, anyInt);
                result = false;
                times=1;
            }
            {method.getTaskError().getVal();
                result = "Create failed";
            }
        };
        VolumeCreateOptions options = VolumeCreateOptions.getInstance(new Storage<StorageUnit>(2, Storage.GIGABYTE), "myNewDisk", "myNewDiskDescription");
        options.withVirtualMachineId("vm-2318");
        hd.createVolume(options);
    }

    //this test loops for 20 minutes searching for new volume so not great for frequent test runs
    //update the timeout in the code if you wish to run this test
    @Ignore
    @Test(expected = CloudException.class)
    public void createVolumeShouldThrowExceptionIfNewVolumeIsNotFound() throws CloudException, InternalException {
        final List<PropertySpec> spPSpecs = dcMock.getStoragePoolPropertySpec();
        new NonStrictExpectations() {
            {vsphereMock.getComputeServices();
                result = vsphereComputeMock;
            }
            {vsphereComputeMock.getVirtualMachineSupport();
                result = vmMock;
            }
            {vmMock.getVirtualMachine(anyString);
                result = new VirtualMachine();
            }
            {vmMock.reconfigVMTask((ManagedObjectReference) any, (VirtualMachineConfigSpec) any);
                result = new ManagedObjectReference();
            }
            {dcMock.retrieveObjectList(vsphereMock, "datastoreFolder", null, spPSpecs);
                result = dcStoragePools;
            }
        };

        new Expectations(HardDisk.class) {
            {
                hd.retrieveObjectList(vsphereMock, "vmFolder", null, hardDiskPSpec);
                result = hardDisks;
                minTimes = 2;
            }
        };

        new Expectations(VsphereMethod.class) {
            {method.getOperationComplete((ManagedObjectReference) any, (TimePeriod) any, anyInt);
                result = true;
                times=1;
            }
        };
        VolumeCreateOptions options = VolumeCreateOptions.getInstance(new Storage<StorageUnit>(2, Storage.GIGABYTE), "myNewDisk", "myNewDiskDescription");
        options.withVirtualMachineId("vm-2318");
        hd.createVolume(options);
    }

    @Test
    public void removeVolume() throws CloudException, InternalException, RuntimeFaultFaultMsg, FileFaultFaultMsg, InvalidDatastoreFaultMsg {
        new NonStrictExpectations() {
            {serviceContentMock.getFileManager();
                result = new ManagedObjectReference();
            }
            {vimPortMock.deleteDatastoreFileTask((ManagedObjectReference) any, anyString, (ManagedObjectReference) any);
                result = task;
                times = 2;
            }
        };

        new Expectations(HardDisk.class) {
            {hd.retrieveObjectList(vsphereMock, "vmFolder", null, hardDiskPSpec);
                result = hardDisks;
                times=2;
            }
            {hd.getAllResourcePoolsIncludingRoot();
                result = resourcePools;

            }
            {hd.retrieveObjectList(vsphereMock, "datastoreFolder", null, datastorePSpec);
                result = datastores;
            }
            {hd.listStoragePools();
                result = storagePools;
            }
            {hd.searchDatastores(vsphereMock, (ManagedObjectReference) any, anyString, null);
                result = task;
            }
        };

        new Expectations(VsphereMethod.class) {
            {method.getOperationComplete((ManagedObjectReference) any, (TimePeriod) any, anyInt);
                result = true;
                times=14;
            }
            {method.getTaskResult();
                result = searchResult;
                result = searchResult;
                result = searchResult;
                result = searchResult;
                result = searchResult;
                result = searchResult;
                result = postRemoveHardDisks;
                result = postRemoveHardDisks;
                result = postRemoveHardDisks;
                result = postRemoveHardDisks;
                result = postRemoveHardDisks;
                result = postRemoveHardDisks;
            }
        };

        hd.remove("dmTesting7Oct_1.vmdk");
        Volume v = hd.getVolume("dmTesting7Oct_1.vmdk");
        assertNull("Volume was deleted, but object was still returned", v);
    }

    @Test(expected = CloudException.class)
    public void removeVolumeShouldThrowExceptionIfVolumeIdIsNull() throws CloudException, InternalException, RuntimeFaultFaultMsg, FileFaultFaultMsg, InvalidDatastoreFaultMsg {
        new Expectations(HardDisk.class) {
            {hd.retrieveObjectList(vsphereMock, "vmFolder", null, hardDiskPSpec);
                result = hardDisks;
            }
            {hd.getAllResourcePoolsIncludingRoot();
                result = resourcePools;

            }
            {hd.retrieveObjectList(vsphereMock, "datastoreFolder", null, datastorePSpec);
                result = datastores;
            }
            {hd.listStoragePools();
                result = storagePools;
            }
            {hd.searchDatastores(vsphereMock, (ManagedObjectReference) any, anyString, null);
                result = task;
            }
        };

        new Expectations(VsphereMethod.class) {
            {method.getOperationComplete((ManagedObjectReference) any, (TimePeriod) any, anyInt);
                result = true;
                times=6;
            }
            {method.getTaskResult();
                result = searchResult;times = 6;
            }
        };
        hd.remove("MyFakeVolume");
    }

    @Test(expected = CloudException.class)
    public void removeVolumeShouldThrowExceptionIfVmIdIsNotNull() throws CloudException, InternalException, RuntimeFaultFaultMsg, FileFaultFaultMsg, InvalidDatastoreFaultMsg {
        new Expectations(HardDisk.class) {
            {hd.retrieveObjectList(vsphereMock, "vmFolder", null, hardDiskPSpec);
                result = hardDisks;
            }
            {hd.getAllResourcePoolsIncludingRoot();
                result = resourcePools;

            }
            {hd.retrieveObjectList(vsphereMock, "datastoreFolder", null, datastorePSpec);
                result = datastores;
            }
            {hd.listStoragePools();
                result = storagePools;
            }
            {hd.searchDatastores(vsphereMock, (ManagedObjectReference) any, anyString, null);
                result = task;
            }
        };

        new Expectations(VsphereMethod.class) {
            {method.getOperationComplete((ManagedObjectReference) any, (TimePeriod) any, anyInt);
                result = true;
                times=6;
            }
            {method.getTaskResult();
                result = searchResult;
                times = 6;
            }
        };

        hd.remove("dmTesting7Oct_4.vmdk");
    }

    @Test(expected = CloudException.class)
    public void removeVolumeShouldThrowExceptionIfOperationIsNotSuccessful() throws CloudException, InternalException, RuntimeFaultFaultMsg, FileFaultFaultMsg, InvalidDatastoreFaultMsg {
        new NonStrictExpectations() {
            {serviceContentMock.getFileManager();
                result = new ManagedObjectReference();
            }
            {vimPortMock.deleteDatastoreFileTask((ManagedObjectReference) any, anyString, (ManagedObjectReference) any);
                result = task;
            }
        };

        new Expectations(HardDisk.class) {
            {hd.retrieveObjectList(vsphereMock, "vmFolder", null, hardDiskPSpec);
                result = hardDisks;
            }
            {hd.getAllResourcePoolsIncludingRoot();
                result = resourcePools;

            }
            {hd.retrieveObjectList(vsphereMock, "datastoreFolder", null, datastorePSpec);
                result = datastores;
            }
            {hd.listStoragePools();
                result = storagePools;
            }
            {hd.searchDatastores(vsphereMock, (ManagedObjectReference) any, anyString, null);
                result = task;
            }
        };

        new Expectations(VsphereMethod.class) {
            {method.getOperationComplete((ManagedObjectReference) any, (TimePeriod) any, anyInt);
                result = true;
                result = true;
                result = true;
                result = true;
                result = true;
                result = true;
                result = false;
            }
            {method.getTaskResult();
                result = searchResult;
                times = 6;
            }
            {method.getTaskError().getVal();
                result = "Remove failed";
            }
        };

        hd.remove("dmTesting7Oct_1.vmdk");
    }
}
