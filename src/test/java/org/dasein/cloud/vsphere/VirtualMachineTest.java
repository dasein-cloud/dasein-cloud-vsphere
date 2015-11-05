package org.dasein.cloud.vsphere;

import com.vmware.vim25.*;
import mockit.Expectations;
import mockit.Mocked;
import mockit.NonStrictExpectations;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.map.SerializationConfig;
import org.dasein.cloud.CloudException;
import org.dasein.cloud.InternalException;
import org.dasein.cloud.compute.*;
import org.dasein.cloud.dc.DataCenter;
import org.dasein.cloud.dc.Folder;
import org.dasein.cloud.dc.ResourcePool;
import org.dasein.cloud.util.Cache;
import org.dasein.cloud.util.CacheLevel;
import org.dasein.cloud.vsphere.compute.HostSupport;
import org.dasein.cloud.vsphere.compute.Vm;
import org.dasein.cloud.vsphere.compute.VsphereCompute;
import org.dasein.util.uom.time.Minute;
import org.dasein.util.uom.time.TimePeriod;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.junit.Assert.*;

/**
 * User: daniellemayne
 * Date: 03/11/2015
 * Time: 08:46
 */
@RunWith(JUnit4.class)
public class VirtualMachineTest extends VsphereTestBase {
    private ObjectManagement om = new ObjectManagement();
    private RetrieveResult virtualMachines;
    private RetrieveResult virtualMachineNoConfig;
    private RetrieveResult virtualMachineNoGuestInfo;
    private RetrieveResult virtualMachineNoRuntime;
    private RetrieveResult virtualMachineNoDatastore;
    private RetrieveResult virtualMachineNoResourcePool;
    private RetrieveResult virtualMachineNoParent;
    private RetrieveResult virtualMachineAllTemplate;
    private RetrieveResult virtualMachinePostAlterVMSize;
    private RetrieveResult virtualMachinePostClone;
    private final ResourcePool[] rootResourcePools = om.readJsonFile("src/test/resources/VirtualMachine/rootResourcePools.json", ResourcePool[].class);
    private final ResourcePool[] resourcePools = om.readJsonFile("src/test/resources/VirtualMachine/resourcePools.json", ResourcePool[].class);
    private final Folder[] vmFolders = om.readJsonFile("src/test/resources/VirtualMachine/vmFolders.json", Folder[].class);
    private final DataCenter datacenter = om.readJsonFile("src/test/resources/VirtualMachine/daseinDatacenter.json", DataCenter.class);
    private final AffinityGroup[] daseinHosts = om.readJsonFile("src/test/resources/VirtualMachine/daseinHosts.json", AffinityGroup[].class);
    private final PropertyChange cloneResult = om.readJsonFile("src/test/resources/VirtualMachine/cloneResult.json", PropertyChange.class);
    private final ManagedObjectReference task = om.readJsonFile("src/test/resources/VirtualMachine/task.json", ManagedObjectReference.class);

    private Vm vm = null;
    private VsphereMethod method = null;
    private List<PropertySpec> vmPSpec = null;
    private Cache<ResourcePool> rpCache = null;

    @Mocked
    DataCenters dcMock;
    @Mocked
    VsphereCompute vsphereComputeMock;
    @Mocked
    HostSupport vsphereAGMock;

    @Before
    public void setUp() throws Exception {
        super.setUp();
        vm = new Vm(vsphereMock);
        method = new VsphereMethod(vsphereMock);
        vmPSpec = vm.getVirtualMachinePSpec();
        rpCache = Cache.getInstance(vsphereMock, "resourcePools", ResourcePool.class, CacheLevel.REGION_ACCOUNT, new TimePeriod<Minute>(15, TimePeriod.MINUTE));

        ObjectManagement om = new ObjectManagement();
        om.mapper.enableDefaultTypingAsProperty(ObjectMapper.DefaultTyping.NON_FINAL, "type");
        om.mapper.configure(SerializationConfig.Feature.FAIL_ON_EMPTY_BEANS, false);
        virtualMachines = om.readJsonFile("src/test/resources/VirtualMachine/virtualMachines.json", RetrieveResult.class);
        virtualMachineNoConfig = om.readJsonFile("src/test/resources/VirtualMachine/virtualMachinesNoConfigProperty.json", RetrieveResult.class);
        virtualMachineNoGuestInfo = om.readJsonFile("src/test/resources/VirtualMachine/virtualMachinesNoGuestInfoProperty.json", RetrieveResult.class);
        virtualMachineNoRuntime = om.readJsonFile("src/test/resources/VirtualMachine/virtualMachinesNoRuntimeProperty.json", RetrieveResult.class);
        virtualMachineNoDatastore = om.readJsonFile("src/test/resources/VirtualMachine/virtualMachinesNoDatastoreProperty.json", RetrieveResult.class);
        virtualMachineNoResourcePool = om.readJsonFile("src/test/resources/VirtualMachine/virtualMachinesNoResourcePoolProperty.json", RetrieveResult.class);
        virtualMachineNoParent = om.readJsonFile("src/test/resources/VirtualMachine/virtualMachinesNoParentProperty.json", RetrieveResult.class);
        virtualMachineAllTemplate = om.readJsonFile("src/test/resources/VirtualMachine/virtualMachinesAllTemplate.json", RetrieveResult.class);
        virtualMachinePostAlterVMSize = om.readJsonFile("src/test/resources/VirtualMachine/virtualMachinesPostAlterVMSize.json", RetrieveResult.class);
        virtualMachinePostClone = om.readJsonFile("src/test/resources/VirtualMachine/virtualMachinesPostClone.json", RetrieveResult.class);
    }

    @Test
    public void listVirtualMachines() throws CloudException, InternalException {
        new Expectations(Vm.class) {
            {vm.retrieveObjectList(vsphereMock, "vmFolder", null, vmPSpec);
                result = virtualMachines;
            }
            {vm.getResourcePools(anyBoolean);
                result = rootResourcePools;
            }
        };

        new NonStrictExpectations() {
            {vsphereMock.getDataCenterServices();
                result = dcMock;
            }
            {dcMock.listVMFolders();
                result = vmFolders;
            }
            {dcMock.getDataCenter(anyString);
                result = datacenter;
            }
        };

        Iterable<VirtualMachine> vms = vm.listVirtualMachines();
        assertNotNull("Null object returned for valid listVirtualMachines", vms);
        assertTrue("Empty vm list returned for valid listVirtualMachines", vms.iterator().hasNext());
        VirtualMachine v = vms.iterator().next();
        assertEquals("vm-211", v.getProviderVirtualMachineId());
        assertEquals("testvm-stateless-1446545324274", v.getName());
        assertEquals(Platform.UBUNTU, v.getPlatform());
        assertEquals(Architecture.I64, v.getArchitecture());
        assertEquals("Ubuntu Linux (64-bit)", v.getDescription());
        assertEquals("1:1024", v.getProductId());
        assertEquals("host-49", v.getAffinityGroupId());
        assertEquals("resgroup-46", v.getResourcePoolId());
        assertEquals("vm-206", v.getProviderMachineImageId());
        assertEquals("network-77", v.getProviderVlanId());
        assertNull(v.getPrivateDnsAddress());
        assertNull(v.getProviderAssignedIpAddressId());
        assertEquals(VmState.STOPPED, v.getCurrentState());
        assertFalse(v.isRebootable());
        assertEquals("TESTACCOUNTNO", v.getProviderOwnerId());
        assertEquals("domain-c45", v.getProviderDataCenterId());
        assertEquals("datacenter-2", v.getProviderRegionId());
        assertEquals("vm", v.getTag("vmFolder"));
        assertEquals("group-v3", v.getTag("vmFolderId"));
        assertEquals("datastore-62", v.getTag("datastore0"));
    }

    @Test
    public void getVirtualMachine() throws CloudException, InternalException {
        new Expectations(Vm.class) {
            {vm.retrieveObjectList(vsphereMock, "vmFolder", null, vmPSpec);
                result = virtualMachines;
            }
            {vm.getResourcePools(anyBoolean);
                result = rootResourcePools;
            }
        };

        new NonStrictExpectations() {
            {vsphereMock.getDataCenterServices();
                result = dcMock;
            }
            {dcMock.listVMFolders();
                result = vmFolders;
            }
            {dcMock.getDataCenter(anyString);
                result = datacenter;
            }
        };

        VirtualMachine v = vm.getVirtualMachine("vm-207");
        assertNotNull("Null object returned for valid getVirtualMachine", v);
        assertEquals("vm-207", v.getProviderVirtualMachineId());
        assertEquals("smtest2", v.getName());
        assertEquals(Platform.UBUNTU, v.getPlatform());
        assertEquals(Architecture.I64, v.getArchitecture());
        assertEquals("Ubuntu Linux (64-bit)", v.getDescription());
        assertEquals("1:1024", v.getProductId());
        assertEquals("host-49", v.getAffinityGroupId());
        assertEquals("resgroup-46", v.getResourcePoolId());
        assertEquals("TESTACCOUNTNO-unknown", v.getProviderMachineImageId());
        assertEquals("network-77", v.getProviderVlanId());
        assertEquals("smtest2", v.getPrivateDnsAddress());
        assertEquals("192.168.6.35", v.getProviderAssignedIpAddressId());
        assertEquals(VmState.RUNNING, v.getCurrentState());
        assertTrue(v.isRebootable());
        assertEquals("TESTACCOUNTNO", v.getProviderOwnerId());
        assertEquals("domain-c45", v.getProviderDataCenterId());
        assertEquals("datacenter-2", v.getProviderRegionId());
        assertEquals("vm", v.getTag("vmFolder"));
        assertEquals("group-v3", v.getTag("vmFolderId"));
        assertEquals("datastore-61", v.getTag("datastore0"));
    }

    @Test
    public void getFakeVirtualMachineShouldReturnNull() throws CloudException, InternalException {
        new Expectations(Vm.class) {
            {vm.retrieveObjectList(vsphereMock, "vmFolder", null, vmPSpec);
                result = virtualMachines;
            }

            {vm.getResourcePools(anyBoolean);
                result = rootResourcePools;
            }
        };

        new NonStrictExpectations() {
            {vsphereMock.getDataCenterServices();
                result = dcMock;
            }

            {dcMock.listVMFolders();
                result = vmFolders;
            }
        };

        VirtualMachine v = vm.getVirtualMachine("MyFakeVm");
        assertNull("Virtual machine returned but id was made up", v);
    }

    @Test
    public void listVirtualMachinesShouldReturnEmptyListIfCloudReturnsNullObject() throws CloudException, InternalException {
        new Expectations(Vm.class) {
            {vm.retrieveObjectList(vsphereMock, "vmFolder", null, vmPSpec);
                result = null;
            }
        };

        Iterable<VirtualMachine> vms = vm.listVirtualMachines();
        assertNotNull("List can be empty but should not be null", vms);
        assertFalse("Cloud returned null object but list is not empty", vms.iterator().hasNext());
    }

    @Test
    public void listVirtualMachinesShouldReturnEmptyListIfCloudReturnsEmptyObject() throws CloudException, InternalException {
        new Expectations(Vm.class) {
            {vm.retrieveObjectList(vsphereMock, "vmFolder", null, vmPSpec);
                result = new RetrieveResult();
            }
        };

        Iterable<VirtualMachine> vms = vm.listVirtualMachines();
        assertNotNull("List can be empty but should not be null", vms);
        assertFalse("Cloud returned empty object but list is not empty", vms.iterator().hasNext());
    }

    @Test
    public void listVirtualMachinesShouldReturnEmptyListIfResourcePoolListIsEmpty() throws CloudException, InternalException {
        new Expectations(Vm.class) {
            {vm.retrieveObjectList(vsphereMock, "vmFolder", null, vmPSpec);
                result = virtualMachines;
            }

            {vm.getResourcePools(anyBoolean);
                result = new ArrayList<ResourcePool>();
            }
        };

        new NonStrictExpectations() {
            {vsphereMock.getDataCenterServices();
                result = dcMock;
            }
            {dcMock.listVMFolders();
                result = vmFolders;
            }
        };


        Iterable<VirtualMachine> vms = vm.listVirtualMachines();
        assertNotNull("List can be empty but should not be null", vms);
        assertFalse("Resource pool list was empty but vm still returned", vms.iterator().hasNext());
    }

    @Test
    public void listVirtualMachinesShouldReturnObjectsWithoutVmFolderTagIfVmFolderListIsEmpty() throws CloudException, InternalException {
        new Expectations(Vm.class) {
            {vm.retrieveObjectList(vsphereMock, "vmFolder", null, vmPSpec);
                result = virtualMachines;
            }

            {vm.getResourcePools(anyBoolean);
                result = rootResourcePools;
            }
        };

        new NonStrictExpectations() {
            {vsphereMock.getDataCenterServices();
                result = dcMock;
            }
            {dcMock.listVMFolders();
                result = new ArrayList<Folder>();
            }
        };


        Iterable<VirtualMachine> vms = vm.listVirtualMachines();
        assertNotNull("Null object returned for valid listVirtualMachines", vms);
        for (VirtualMachine v : vms) {
            assertNull("Vm folder list was null but vm "+v.getName()+" still has vm folder tag", v.getTag("vmFolder"));
        }
    }

    @Test
    public void listVirtualMachinesShouldReturnEmptyListIfCloudDoesNotReturnConfigProperty() throws CloudException, InternalException {
        new Expectations(Vm.class) {
            {vm.retrieveObjectList(vsphereMock, "vmFolder", null, vmPSpec);
                result = virtualMachineNoConfig;
            }

            {vm.getResourcePools(anyBoolean);
                result = rootResourcePools;
            }
        };

        new NonStrictExpectations() {
            {vsphereMock.getDataCenterServices();
                result = dcMock;
            }
            {dcMock.listVMFolders();
                result = vmFolders;
            }
        };

        Iterable<VirtualMachine> vms = vm.listVirtualMachines();
        assertNotNull("List can be empty but should not be null", vms);
        assertFalse("No valid vm objects returned but list is not empty", vms.iterator().hasNext());
    }

    @Test
    public void listVirtualMachinesShouldReturnEmptyListIfCloudDoesNotReturnGuestInfoProperty() throws CloudException, InternalException {
        new Expectations(Vm.class) {
            {vm.retrieveObjectList(vsphereMock, "vmFolder", null, vmPSpec);
                result = virtualMachineNoGuestInfo;
            }

            {vm.getResourcePools(anyBoolean);
                result = rootResourcePools;
            }
        };

        new NonStrictExpectations() {
            {vsphereMock.getDataCenterServices();
                result = dcMock;
            }
            {dcMock.listVMFolders();
                result = vmFolders;
            }
        };

        Iterable<VirtualMachine> vms = vm.listVirtualMachines();
        assertNotNull("List can be empty but should not be null", vms);
        assertFalse("No valid vm objects returned but list is not empty", vms.iterator().hasNext());
    }

    @Test
    public void listVirtualMachinesShouldReturnEmptyListIfCloudDoesNotReturnRuntimeProperty() throws CloudException, InternalException {
        new Expectations(Vm.class) {
            {vm.retrieveObjectList(vsphereMock, "vmFolder", null, vmPSpec);
                result = virtualMachineNoRuntime;
            }

            {vm.getResourcePools(anyBoolean);
                result = rootResourcePools;
            }
        };

        new NonStrictExpectations() {
            {vsphereMock.getDataCenterServices();
                result = dcMock;
            }
            {dcMock.listVMFolders();
                result = vmFolders;
            }
        };

        Iterable<VirtualMachine> vms = vm.listVirtualMachines();
        assertNotNull("List can be empty but should not be null", vms);
        assertFalse("No valid vm objects returned but list is not empty", vms.iterator().hasNext());
    }

    @Test
    public void listVirtualMachinesShouldReturnEmptyListIfCloudDoesNotReturnDatastoreProperty() throws CloudException, InternalException {
        new Expectations(Vm.class) {
            {vm.retrieveObjectList(vsphereMock, "vmFolder", null, vmPSpec);
                result = virtualMachineNoDatastore;
            }

            {vm.getResourcePools(anyBoolean);
                result = rootResourcePools;
            }
        };

        new NonStrictExpectations() {
            {vsphereMock.getDataCenterServices();
                result = dcMock;
            }
            {dcMock.listVMFolders();
                result = vmFolders;
            }
        };

        Iterable<VirtualMachine> vms = vm.listVirtualMachines();
        assertNotNull("List can be empty but should not be null", vms);
        assertFalse("No valid vm objects returned but list is not empty", vms.iterator().hasNext());
    }

    @Test
    public void listVirtualMachinesShouldReturnEmptyListIfCloudDoesNotReturnResourcePoolProperty() throws CloudException, InternalException {
        new Expectations(Vm.class) {
            {vm.retrieveObjectList(vsphereMock, "vmFolder", null, vmPSpec);
                result = virtualMachineNoResourcePool;
            }

            {vm.getResourcePools(anyBoolean);
                result = rootResourcePools;
            }
        };

        new NonStrictExpectations() {
            {vsphereMock.getDataCenterServices();
                result = dcMock;
            }
            {dcMock.listVMFolders();
                result = vmFolders;
            }
        };

        Iterable<VirtualMachine> vms = vm.listVirtualMachines();
        assertNotNull("List can be empty but should not be null", vms);
        assertFalse("No valid vm objects returned but list is not empty", vms.iterator().hasNext());
    }

    @Test
    public void listVirtualMachinesShouldReturnObjectsWithoutVmFolderTagIfCloudDoesNotReturnParentProperty() throws CloudException, InternalException {
        new Expectations(Vm.class) {
            {vm.retrieveObjectList(vsphereMock, "vmFolder", null, vmPSpec);
                result = virtualMachineNoParent;
            }

            {vm.getResourcePools(anyBoolean);
                result = rootResourcePools;
            }
        };

        new NonStrictExpectations() {
            {vsphereMock.getDataCenterServices();
                result = dcMock;
            }
            {dcMock.listVMFolders();
                result = vmFolders;
            }
        };


        Iterable<VirtualMachine> vms = vm.listVirtualMachines();
        assertNotNull("Null object returned for valid listVirtualMachines", vms);
        for (VirtualMachine v : vms) {
            assertNull("Parent property was not returned but vm "+v.getName()+" still has vm folder tag", v.getTag("vmFolder"));
        }
    }

    @Test
    public void listVirtualMachinesShouldReturnEmptyListIfCloudReturnsAllTemplateObjects() throws CloudException, InternalException {
        new Expectations(Vm.class) {
            {vm.retrieveObjectList(vsphereMock, "vmFolder", null, vmPSpec);
                result = virtualMachineAllTemplate;
            }

            {vm.getResourcePools(anyBoolean);
                result = rootResourcePools;
            }
        };

        new NonStrictExpectations() {
            {vsphereMock.getDataCenterServices();
                result = dcMock;
            }
            {dcMock.listVMFolders();
                result = vmFolders;
            }
        };

        Iterable<VirtualMachine> vms = vm.listVirtualMachines();
        assertNotNull("List can be empty but should not be null", vms);
        assertFalse("No valid vm objects returned but list is not empty", vms.iterator().hasNext());
    }

    @Test(expected = NoContextException.class)
    public void listVirtualMachinesShouldThrowExceptionIfContextIsNull() throws CloudException, InternalException {
        new NonStrictExpectations() {
            {vsphereMock.getContext();
                result = null;
            }
        };

        vm.listVirtualMachines();
    }

    @Test(expected = CloudException.class)
    public void listVirtualMachinesShouldThrowExceptionIfRegionIsNull() throws CloudException, InternalException {
        new NonStrictExpectations() {
            {providerContextMock.getRegionId();
                result = null;
            }
        };

        vm.listVirtualMachines();
    }

    @Test
    public void listProducts() throws CloudException, InternalException {
        rpCache.clear();

        new Expectations() {
            {dcMock.listResourcePools(null);
                result = resourcePools;
                times = 1;
            }
        };

        Iterable<VirtualMachineProduct> products = vm.listAllProducts();
        assertNotNull("Valid listProducts returned null object", products);
        assertTrue("Valid listProducts returned empty list", products.iterator().hasNext());
        VirtualMachineProduct product = products.iterator().next();
        assertNotNull(product);
        assertEquals("1 CPU/512MB RAM", product.getName());
        assertEquals("1:512", product.getProviderProductId());
        assertEquals(1, product.getRootVolumeSize().intValue());
        assertEquals(1, product.getCpuCount());
        assertEquals("Custom product - 1 CPU, 512MB RAM", product.getDescription());
        assertEquals(512, product.getRamSize().intValue());
        assertEquals(0, product.getStandardHourlyRate(), 0);

        int count = 0;
        for (VirtualMachineProduct p : products) {
            count++;
        }
        assertEquals("Incorrect number of products "+count, 44, count);
    }

    @Test
    public void listProductsWithFilterOptions() throws CloudException, InternalException {
        new Expectations() {
            {dcMock.listResourcePools(null);
                result = resourcePools;
                minTimes = 0;
            }
        };

        Iterable<VirtualMachineProduct> products = vm.listProducts("ignored", VirtualMachineProductFilterOptions.getInstance().withCpuCount(1));
        assertNotNull("Valid listProducts request returned null object", products);
        int count = 0;
        for (VirtualMachineProduct product : products) {
            count++;
        }
        assertEquals("Incorrect number of products returned for filter "+count, 12, count);
    }

    @Test
    public void getProduct() throws CloudException, InternalException {
        VirtualMachineProduct product = vm.getProduct("2:2048");
        assertNotNull(product);
        assertEquals("2 CPU/2048MB RAM", product.getName());
        assertEquals("2:2048", product.getProviderProductId());
        assertEquals(1, product.getRootVolumeSize().intValue());
        assertEquals(2, product.getCpuCount());
        assertEquals("Custom product - 2 CPU, 2048MB RAM", product.getDescription());
        assertEquals(2048, product.getRamSize().intValue());
        assertEquals(0, product.getStandardHourlyRate(), 0);
    }

    @Test
    public void getUnknownProductShouldReturnANewProductObject() throws CloudException, InternalException {
        VirtualMachineProduct product = vm.getProduct("4:512");
        assertNotNull(product);
        assertEquals("4 CPU/512MB RAM", product.getName());
        assertEquals("4:512", product.getProviderProductId());
        assertEquals(1, product.getRootVolumeSize().intValue());
        assertEquals(4, product.getCpuCount());
        assertEquals("Custom product - 4 CPU, 512MB RAM", product.getDescription());
        assertEquals(512, product.getRamSize().intValue());
        assertEquals(0, product.getStandardHourlyRate(), 0);
    }

    @Test
    public void listProductsShouldUseResourcePoolCacheWhenItIsValid() throws CloudException, InternalException {
        rpCache.clear();

        new Expectations() {
            {dcMock.listResourcePools(null);
                result = resourcePools;
                times = 1;
            }
        };

        vm.listAllProducts();
        vm.listAllProducts();
    }

    @Test(expected = NumberFormatException.class)
    public void getProductShouldThrowExceptionIfProductIdIsNotNumeric() throws CloudException, InternalException {
        vm.getProduct("MyFakeProduct");
    }

    @Test
    public void alterVirtualMachineSize() throws CloudException, InternalException {
        new Expectations(Vm.class) {
            {vm.retrieveObjectList(vsphereMock, "vmFolder", null, vmPSpec);
                result = virtualMachines;
                result = virtualMachinePostAlterVMSize;
            }
            {vm.getResourcePools(anyBoolean);
                result = rootResourcePools;
            }
            {vm.reconfigVMTask((ManagedObjectReference) any, (VirtualMachineConfigSpec) any);
                result = task;
            }
        };

        new Expectations(VsphereMethod.class) {
            {method.getOperationComplete((ManagedObjectReference) any, (TimePeriod) any, anyInt);
                result = true;
            }
        };

        new NonStrictExpectations() {
            {vsphereMock.getDataCenterServices();
                result = dcMock;
            }
            {dcMock.listVMFolders();
                result = vmFolders;
            }
            {dcMock.getDataCenter(anyString);
                result = datacenter;
            }
        };

        VirtualMachine virtualMachine = vm.alterVirtualMachineSize("vm-211", "2", "2048");
        assertNotNull("Null object returned for valid alterVM operation", virtualMachine);
        assertEquals("vm-211", virtualMachine.getProviderVirtualMachineId());
        assertEquals("2:2048", virtualMachine.getProductId());
    }

    @Test(expected = CloudException.class)
    public void alterVirtualMachineSizeShouldThrowExceptionIfVmIsNull() throws CloudException, InternalException {
        new Expectations(Vm.class) {
            {vm.retrieveObjectList(vsphereMock, "vmFolder", null, vmPSpec);
                result = virtualMachines;
            }
            {vm.getResourcePools(anyBoolean);
                result = rootResourcePools;
            }
            {vm.reconfigVMTask((ManagedObjectReference) any, (VirtualMachineConfigSpec) any);
                times = 0;
            }
        };

        new Expectations(VsphereMethod.class) {
            {method.getOperationComplete((ManagedObjectReference) any, (TimePeriod) any, anyInt);
                times = 0;
            }
        };

        new NonStrictExpectations() {
            {vsphereMock.getDataCenterServices();
                result = dcMock;
            }
            {dcMock.listVMFolders();
                result = vmFolders;
            }
            {dcMock.getDataCenter(anyString);
                result = datacenter;
            }
        };

        vm.alterVirtualMachineSize("MyFakeVm", "2", "2048");
    }

    @Test(expected = CloudException.class)
    public void alterVirtualMachineSizeShouldThrowExceptionIfCpuCountAndRamInMBIsNull() throws CloudException, InternalException {
        new Expectations(Vm.class) {
            {vm.retrieveObjectList(vsphereMock, "vmFolder", null, vmPSpec);
                result = virtualMachines;
            }
            {vm.getResourcePools(anyBoolean);
                result = rootResourcePools;
            }
            {vm.reconfigVMTask((ManagedObjectReference) any, (VirtualMachineConfigSpec) any);
                times = 0;
            }
        };

        new Expectations(VsphereMethod.class) {
            {method.getOperationComplete((ManagedObjectReference) any, (TimePeriod) any, anyInt);
                times = 0;
            }
        };

        new NonStrictExpectations() {
            {vsphereMock.getDataCenterServices();
                result = dcMock;
            }
            {dcMock.listVMFolders();
                result = vmFolders;
            }
            {dcMock.getDataCenter(anyString);
                result = datacenter;
            }
        };

        vm.alterVirtualMachineSize("vm-211", null, null);
    }

    @Test(expected = CloudException.class)
    public void alterVirtualMachineSizeShouldThrowExceptionIfOperationIsNotSuccesssful() throws CloudException, InternalException {
        new Expectations(Vm.class) {
            {vm.retrieveObjectList(vsphereMock, "vmFolder", null, vmPSpec);
                result = virtualMachines;
            }
            {vm.getResourcePools(anyBoolean);
                result = rootResourcePools;
            }
            {vm.reconfigVMTask((ManagedObjectReference) any, (VirtualMachineConfigSpec) any);
                result = task;
            }
        };

        new Expectations(VsphereMethod.class) {
            {method.getOperationComplete((ManagedObjectReference) any, (TimePeriod) any, anyInt);
                result = false;
            }
            {method.getTaskError().getVal();
                result = "Alter vm op failed";
            }
        };

        new NonStrictExpectations() {
            {vsphereMock.getDataCenterServices();
                result = dcMock;
            }
            {dcMock.listVMFolders();
                result = vmFolders;
            }
            {dcMock.getDataCenter(anyString);
                result = datacenter;
            }
        };

        vm.alterVirtualMachineSize("vm-211", "2", "2048");
    }

    @Test
    public void cloneVirtualMachine() throws CloudException, InternalException {
        new Expectations(Vm.class) {
            {vm.retrieveObjectList(vsphereMock, "vmFolder", null, vmPSpec);
                result = virtualMachines;
                result = virtualMachinePostClone;
            }
            {vm.getResourcePools(anyBoolean);
                result = rootResourcePools;
            }
            {vm.cloneVmTask((ManagedObjectReference) any, (ManagedObjectReference) any, anyString, (VirtualMachineCloneSpec) any);
                result = task;
            }
        };

        new Expectations(VsphereMethod.class) {
            {method.getOperationComplete((ManagedObjectReference) any, (TimePeriod) any, anyInt);
                result = true;
            }
            {method.getTaskResult();
                result = cloneResult;
            }
        };

        new NonStrictExpectations() {
            {vsphereMock.getDataCenterServices();
                result = dcMock;
            }
            {dcMock.listVMFolders();
                result = vmFolders;
            }
            {dcMock.getDataCenter(anyString);
                result = datacenter;
            }
            {dcMock.listResourcePools(anyString);
                result = new ArrayList<ResourcePool>();
            }
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

        VirtualMachine virtualMachine = vm.clone("vm-211", "domain-c45", "myNewVm", "clone vm test", false);
        assertNotNull("Null object returned for valid clone operation", virtualMachine);
        assertEquals("myNewVm", virtualMachine.getName());
        assertEquals("1:1024", virtualMachine.getProductId());
        assertEquals(VmState.STOPPED, virtualMachine.getCurrentState());
        assertEquals("group-v3", virtualMachine.getTag("vmFolderId"));
    }

    @Test(expected = CloudException.class)
    public void cloneVirtualMachineShouldThrowExceptionIfVmIsNull() throws CloudException, InternalException {
        new Expectations(Vm.class) {
            {vm.retrieveObjectList(vsphereMock, "vmFolder", null, vmPSpec);
                result = virtualMachines;
            }
            {vm.getResourcePools(anyBoolean);
                result = rootResourcePools;
            }
            {vm.cloneVmTask((ManagedObjectReference) any, (ManagedObjectReference) any, anyString, (VirtualMachineCloneSpec) any);
                times = 0;
            }
        };

        new Expectations(VsphereMethod.class) {
            {method.getOperationComplete((ManagedObjectReference) any, (TimePeriod) any, anyInt);
                times = 0;
            }
            {method.getTaskResult();
                times = 0;
            }
        };

        new NonStrictExpectations() {
            {vsphereMock.getDataCenterServices();
                result = dcMock;
            }
            {dcMock.listVMFolders();
                result = vmFolders;
            }
            {dcMock.getDataCenter(anyString);
                result = datacenter;
            }
            {dcMock.listResourcePools(anyString);
                result = new ArrayList<ResourcePool>();
            }
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

        vm.clone("MyFakeVm", "domain-c45", "myNewVm", "clone vm test", false);
    }

    @Test(expected = InternalException.class)
    public void cloneVirtualMachineShouldThrowExceptionIfHostMORIsNull() throws CloudException, InternalException {
        new Expectations(Vm.class) {
            {vm.retrieveObjectList(vsphereMock, "vmFolder", null, vmPSpec);
                result = virtualMachines;
            }
            {vm.getResourcePools(anyBoolean);
                result = rootResourcePools;
            }
            {vm.cloneVmTask((ManagedObjectReference) any, (ManagedObjectReference) any, anyString, (VirtualMachineCloneSpec) any);
                result = task;
                times = 0;
            }
        };

        new Expectations(VsphereMethod.class) {
            {method.getOperationComplete((ManagedObjectReference) any, (TimePeriod) any, anyInt);
                result = true;
                times = 0;
            }
            {method.getTaskResult();
                result = cloneResult;
                times = 0;
            }
        };

        new NonStrictExpectations() {
            {vsphereMock.getDataCenterServices();
                result = dcMock;
            }
            {dcMock.listVMFolders();
                result = vmFolders;
            }
            {dcMock.getDataCenter(anyString);
                result = datacenter;
            }
            {dcMock.listResourcePools(anyString);
                result = new ArrayList<ResourcePool>();
            }
            { vsphereMock.getComputeServices();
                result = vsphereComputeMock;
            }
            { vsphereComputeMock.getAffinityGroupSupport();
                result = vsphereAGMock;
            }
            { vsphereAGMock.list((AffinityGroupFilterOptions) any);
                result = new ArrayList<AffinityGroup>();
            }
        };

        vm.clone("vm-211", "domain-c45", "myNewVm", "clone vm test", false);
    }

    @Test(expected = InternalException.class)
    public void cloneVirtualMachineShouldThrowExceptionIfResourcePoolMORIsNull() throws CloudException, InternalException {
        new Expectations(Vm.class) {
            {vm.retrieveObjectList(vsphereMock, "vmFolder", null, vmPSpec);
                result = virtualMachines;
            }
            {vm.getResourcePools(anyBoolean);
                result = rootResourcePools;
                result = new ArrayList<ResourcePool>();
            }
            {vm.cloneVmTask((ManagedObjectReference) any, (ManagedObjectReference) any, anyString, (VirtualMachineCloneSpec) any);
                times = 0;
            }
        };

        new Expectations(VsphereMethod.class) {
            {method.getOperationComplete((ManagedObjectReference) any, (TimePeriod) any, anyInt);
                times = 0;
            }
            {method.getTaskResult();
                times = 0;
            }
        };

        new NonStrictExpectations() {
            {vsphereMock.getDataCenterServices();
                result = dcMock;
            }
            {dcMock.listVMFolders();
                result = vmFolders;
            }
            {dcMock.getDataCenter(anyString);
                result = datacenter;
            }
            {dcMock.listResourcePools(anyString);
                result = new ArrayList<ResourcePool>();
            }
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

        vm.clone("vm-211", "domain-c45", "myNewVm", "clone vm test", false);
    }

    @Test(expected = CloudException.class)
    public void cloneVirtualMachineShouldThrowExceptionIfOperationIsNotSuccessful() throws CloudException, InternalException {
        new Expectations(Vm.class) {
            {vm.retrieveObjectList(vsphereMock, "vmFolder", null, vmPSpec);
                result = virtualMachines;
            }
            {vm.getResourcePools(anyBoolean);
                result = rootResourcePools;
            }
            {vm.cloneVmTask((ManagedObjectReference) any, (ManagedObjectReference) any, anyString, (VirtualMachineCloneSpec) any);
                result = task;
            }
        };

        new Expectations(VsphereMethod.class) {
            {method.getOperationComplete((ManagedObjectReference) any, (TimePeriod) any, anyInt);
                result = false;
            }
            {method.getTaskResult();
                result = cloneResult;
                times = 0;
            }
            {method.getTaskError().getVal();
                result = "Clone task failed";
            }
        };

        new NonStrictExpectations() {
            {vsphereMock.getDataCenterServices();
                result = dcMock;
            }
            {dcMock.listVMFolders();
                result = vmFolders;
            }
            {dcMock.getDataCenter(anyString);
                result = datacenter;
            }
            {dcMock.listResourcePools(anyString);
                result = new ArrayList<ResourcePool>();
            }
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

        vm.clone("vm-211", "domain-c45", "myNewVm", "clone vm test", false);
    }
}
