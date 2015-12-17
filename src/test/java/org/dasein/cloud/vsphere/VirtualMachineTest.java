package org.dasein.cloud.vsphere;

import com.vmware.vim25.*;
import mockit.Expectations;
import mockit.Mocked;
import mockit.NonStrictExpectations;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.map.SerializationConfig;
import org.dasein.cloud.*;
import org.dasein.cloud.compute.*;
import org.dasein.cloud.dc.DataCenter;
import org.dasein.cloud.dc.Folder;
import org.dasein.cloud.dc.ResourcePool;
import org.dasein.cloud.network.VLAN;
import org.dasein.cloud.util.Cache;
import org.dasein.cloud.util.CacheLevel;
import org.dasein.cloud.vsphere.compute.HostSupport;
import org.dasein.cloud.vsphere.compute.Vm;
import org.dasein.cloud.vsphere.compute.VsphereCompute;
import org.dasein.cloud.vsphere.network.VSphereNetwork;
import org.dasein.cloud.vsphere.network.VSphereNetworkServices;
import org.dasein.util.uom.time.Minute;
import org.dasein.util.uom.time.TimePeriod;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * User: daniellemayne
 * Date: 03/11/2015
 * Time: 08:46
 */
@RunWith(JUnit4.class)
public class VirtualMachineTest extends VsphereTestBase {
    static private RetrieveResult virtualMachines;
    static private RetrieveResult virtualMachineAllTemplate;
    static private RetrieveResult virtualMachinePostAlterVMSize;
    static private RetrieveResult virtualMachinePostClone;
    static private RetrieveResult virtualMachinesPostTerminate;
    static private RetrieveResult launchVmTemplates;
    static private RetrieveResult launchVmTemplatesNoConfig;
    static private RetrieveResult virtualMachinesPostLaunch;

    static private RetrieveResult resourcePools;

    static private ResourcePool[] daseinRootResourcePools;
    static private ResourcePool[] daseinResourcePools;
    static private Folder[] vmFolders;
    static private DataCenter daseinDatacenter;
    static private AffinityGroup[] daseinHosts;
    static private PropertyChange cloneResult;
    static private PropertyChange launchResult;
    static private PropertyChange launchWinVmResult;
    static private ManagedObjectReference task;
    static private VMLaunchOptions winLaunchOptions;
    static private VMLaunchOptions winLaunchOptionsNoOwner;
    static private VMLaunchOptions winLaunchOptionsNoOrgName;
    static private VMLaunchOptions winLaunchOptionsNoSerial;
    static private VLAN[] daseinNetworks;

    private Vm vm = null;
    private VsphereMethod method = null;
    private List<PropertySpec> vmPSpec = null;
    private List<PropertySpec> launchVmPSpec = null;
    static private Cache<ResourcePool> rpCache = null;

    private List<PropertySpec> rpPSpec = null;
    private List<SelectionSpec> rpSSpec = null;

    @Mocked
    DataCenters dcMock;
    @Mocked
    VsphereCompute vsphereComputeMock;
    @Mocked
    HostSupport vsphereAGMock;
    @Mocked
    VSphereNetworkServices vsphereNetworkMock;
    @Mocked
    VSphereNetwork netMock;

    @BeforeClass
    static public void setUpFixtures() {
        ObjectManagement om = new ObjectManagement();
        resourcePools = om.readJsonFile("src/test/resources/VirtualMachine/resourcePools.json", RetrieveResult.class);

        daseinRootResourcePools = om.readJsonFile("src/test/resources/VirtualMachine/daseinRootResourcePools.json", ResourcePool[].class);
        daseinResourcePools = om.readJsonFile("src/test/resources/VirtualMachine/daseinResourcePools.json", ResourcePool[].class);
        vmFolders = om.readJsonFile("src/test/resources/VirtualMachine/vmFolders.json", Folder[].class);
        daseinDatacenter = om.readJsonFile("src/test/resources/VirtualMachine/daseinDatacenter.json", DataCenter.class);
        daseinHosts = om.readJsonFile("src/test/resources/VirtualMachine/daseinHosts.json", AffinityGroup[].class);
        cloneResult = om.readJsonFile("src/test/resources/VirtualMachine/cloneResult.json", PropertyChange.class);
        launchResult = om.readJsonFile("src/test/resources/VirtualMachine/launchResult.json", PropertyChange.class);
        launchWinVmResult = om.readJsonFile("src/test/resources/VirtualMachine/launchWinVmResult.json", PropertyChange.class);
        task = om.readJsonFile("src/test/resources/VirtualMachine/task.json", ManagedObjectReference.class);
        winLaunchOptions = om.readJsonFile("src/test/resources/VirtualMachine/LaunchCustomisation/vmLaunchOptionsWinCustomisation.json", VMLaunchOptions.class);
        winLaunchOptionsNoOwner = om.readJsonFile("src/test/resources/VirtualMachine/LaunchCustomisation/vmLaunchOptionsWinCustomisationNoOwner.json", VMLaunchOptions.class);
        winLaunchOptionsNoOrgName = om.readJsonFile("src/test/resources/VirtualMachine/LaunchCustomisation/vmLaunchOptionsWinCustomisationNoOrg.json", VMLaunchOptions.class);
        winLaunchOptionsNoSerial = om.readJsonFile("src/test/resources/VirtualMachine/LaunchCustomisation/vmLaunchOptionsWinCustomisationNoSerial.json", VMLaunchOptions.class);
        daseinNetworks = om.readJsonFile("src/test/resources/VirtualMachine/daseinNetworks.json", VLAN[].class);

        om.mapper.enableDefaultTypingAsProperty(ObjectMapper.DefaultTyping.NON_FINAL, "type");
        om.mapper.configure(SerializationConfig.Feature.FAIL_ON_EMPTY_BEANS, false);
        virtualMachines = om.readJsonFile("src/test/resources/VirtualMachine/virtualMachines.json", RetrieveResult.class);
        virtualMachineAllTemplate = om.readJsonFile("src/test/resources/VirtualMachine/virtualMachinesAllTemplate.json", RetrieveResult.class);
        virtualMachinePostAlterVMSize = om.readJsonFile("src/test/resources/VirtualMachine/virtualMachinesPostAlterVMSize.json", RetrieveResult.class);
        virtualMachinePostClone = om.readJsonFile("src/test/resources/VirtualMachine/virtualMachinesPostClone.json", RetrieveResult.class);
        virtualMachinesPostTerminate = om.readJsonFile("src/test/resources/VirtualMachine/virtualMachinesPostTerminate.json", RetrieveResult.class);
        launchVmTemplates = om.readJsonFile("src/test/resources/VirtualMachine/launchVmTemplates.json", RetrieveResult.class);
        launchVmTemplatesNoConfig = om.readJsonFile("src/test/resources/VirtualMachine/vmTemplatesNoConfigProperty.json", RetrieveResult.class);
        virtualMachinesPostLaunch = om.readJsonFile("src/test/resources/VirtualMachine/virtualMachinesPostLaunch.json", RetrieveResult.class);
    }

    @Before
    public void setUp() throws Exception {
        super.setUp();
        vm = new Vm(vsphereMock);
        method = new VsphereMethod(vsphereMock);
        vmPSpec = vm.getVirtualMachinePSpec();
        launchVmPSpec = vm.getLaunchVirtualMachinePSpec();
        rpCache = Cache.getInstance(vsphereMock, "resourcePools", ResourcePool.class, CacheLevel.REGION_ACCOUNT, new TimePeriod<Minute>(15, TimePeriod.MINUTE));

        rpPSpec = vm.getResourcePoolPropertySpec();
        rpSSpec = vm.getResourcePoolSelectionSpec();

    }

    @Test
    public void listVirtualMachines() throws CloudException, InternalException {
        new Expectations(Vm.class) {
            {vm.retrieveObjectList(vsphereMock, "vmFolder", null, vmPSpec);
                result = virtualMachines;
            }
            {vm.getResourcePools(anyBoolean);
                result = daseinRootResourcePools;
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
                result = daseinDatacenter;
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
                result = daseinRootResourcePools;
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
                result = daseinDatacenter;
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
                result = daseinRootResourcePools;
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
                result = daseinRootResourcePools;
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
    public void listVirtualMachinesShouldReturnEmptyListIfCloudReturnsAllTemplateObjects() throws CloudException, InternalException {
        new Expectations(Vm.class) {
            {vm.retrieveObjectList(vsphereMock, "vmFolder", null, vmPSpec);
                result = virtualMachineAllTemplate;
            }

            {vm.getResourcePools(anyBoolean);
                result = daseinRootResourcePools;
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

    @Test(expected = InternalException.class)
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
                result = daseinResourcePools;
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
                result = daseinResourcePools;
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
                result = daseinResourcePools;
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
                result = daseinRootResourcePools;
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
                result = daseinDatacenter;
            }
        };

        VirtualMachine virtualMachine = vm.alterVirtualMachineSize("vm-211", "2", "2048");
        assertNotNull("Null object returned for valid alterVM operation", virtualMachine);
        assertEquals("vm-211", virtualMachine.getProviderVirtualMachineId());
        assertEquals("2:2048", virtualMachine.getProductId());
    }

    @Test(expected = ResourceNotFoundException.class)
    public void alterVirtualMachineSizeShouldThrowExceptionIfVmIsNull() throws CloudException, InternalException {
        new Expectations(Vm.class) {
            {vm.retrieveObjectList(vsphereMock, "vmFolder", null, vmPSpec);
                result = virtualMachines;
            }
            {vm.getResourcePools(anyBoolean);
                result = daseinRootResourcePools;
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
                result = daseinDatacenter;
            }
        };

        vm.alterVirtualMachineSize("MyFakeVm", "2", "2048");
    }

    @Test(expected = InternalException.class)
    public void alterVirtualMachineSizeShouldThrowExceptionIfCpuCountAndRamInMBIsNull() throws CloudException, InternalException {
        new Expectations(Vm.class) {
            {vm.retrieveObjectList(vsphereMock, "vmFolder", null, vmPSpec);
                result = virtualMachines;
            }
            {vm.getResourcePools(anyBoolean);
                result = daseinRootResourcePools;
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
                result = daseinDatacenter;
            }
        };

        vm.alterVirtualMachineSize("vm-211", null, null);
    }

    @Test(expected = GeneralCloudException.class)
    public void alterVirtualMachineSizeShouldThrowExceptionIfOperationIsNotSuccesssful() throws CloudException, InternalException {
        new Expectations(Vm.class) {
            {vm.retrieveObjectList(vsphereMock, "vmFolder", null, vmPSpec);
                result = virtualMachines;
            }
            {vm.getResourcePools(anyBoolean);
                result = daseinRootResourcePools;
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
                result = daseinDatacenter;
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
                result = daseinRootResourcePools;
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
                result = daseinDatacenter;
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

    @Test(expected = ResourceNotFoundException.class)
    public void cloneVirtualMachineShouldThrowExceptionIfVmIsNull() throws CloudException, InternalException {
        new Expectations(Vm.class) {
            {vm.retrieveObjectList(vsphereMock, "vmFolder", null, vmPSpec);
                result = virtualMachines;
            }
            {vm.getResourcePools(anyBoolean);
                result = daseinRootResourcePools;
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
                result = daseinDatacenter;
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

    @Test(expected = ResourceNotFoundException.class)
    public void cloneVirtualMachineShouldThrowExceptionIfHostMORIsNull() throws CloudException, InternalException {
        new Expectations(Vm.class) {
            {vm.retrieveObjectList(vsphereMock, "vmFolder", null, vmPSpec);
                result = virtualMachines;
            }
            {vm.getResourcePools(anyBoolean);
                result = daseinRootResourcePools;
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
                result = daseinDatacenter;
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

    @Test(expected = ResourceNotFoundException.class)
    public void cloneVirtualMachineShouldThrowExceptionIfResourcePoolMORIsNull() throws CloudException, InternalException {
        new Expectations(Vm.class) {
            {vm.retrieveObjectList(vsphereMock, "vmFolder", null, vmPSpec);
                result = virtualMachines;
            }
            {vm.getResourcePools(anyBoolean);
                result = daseinRootResourcePools;
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
                result = daseinDatacenter;
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

    @Test(expected = GeneralCloudException.class)
    public void cloneVirtualMachineShouldThrowExceptionIfOperationIsNotSuccessful() throws CloudException, InternalException {
        new Expectations(Vm.class) {
            {vm.retrieveObjectList(vsphereMock, "vmFolder", null, vmPSpec);
                result = virtualMachines;
            }
            {vm.getResourcePools(anyBoolean);
                result = daseinRootResourcePools;
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
                result = daseinDatacenter;
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

    @Test
    public void rebootVirtualMachine() throws CloudException, InternalException {
        new Expectations(Vm.class) {
            {vm.retrieveObjectList(vsphereMock, "vmFolder", null, vmPSpec);
                result = virtualMachines;
            }
            {vm.getResourcePools(anyBoolean);
                result = daseinRootResourcePools;
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
                result = daseinDatacenter;
            }
        };

        vm.reboot("vm-207");
    }

    @Test(expected = ResourceNotFoundException.class)
    public void rebootVirtualMachineShouldThrowExceptionIfVmIsNull() throws CloudException, InternalException {
        new Expectations(Vm.class) {
            {vm.retrieveObjectList(vsphereMock, "vmFolder", null, vmPSpec);
                result = virtualMachines;
            }
            {vm.getResourcePools(anyBoolean);
                result = daseinRootResourcePools;
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
                result = daseinDatacenter;
            }
        };

        vm.reboot("MyFakeVm");
    }

    @Test(expected = InternalException.class)
    public void rebootVirtualMachineShouldThrowExceptionIfVmIsNotRunning() throws CloudException, InternalException {
        new Expectations(Vm.class) {
            {vm.retrieveObjectList(vsphereMock, "vmFolder", null, vmPSpec);
                result = virtualMachines;
            }
            {vm.getResourcePools(anyBoolean);
                result = daseinRootResourcePools;
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
                result = daseinDatacenter;
            }
        };

        vm.reboot("vm-211");
    }

    @Test(expected = GeneralCloudException.class)
    public void rebootVirtualMachineShouldThrowCloudExceptionIfRuntimeFaultFaultMsgThrown() throws CloudException, InternalException, RuntimeFaultFaultMsg, InvalidStateFaultMsg, TaskInProgressFaultMsg, ToolsUnavailableFaultMsg {
        new Expectations(Vm.class) {
            {vm.retrieveObjectList(vsphereMock, "vmFolder", null, vmPSpec);
                result = virtualMachines;
            }
            {vm.getResourcePools(anyBoolean);
                result = daseinRootResourcePools;
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
                result = daseinDatacenter;
            }
            {vimPortMock.rebootGuest((ManagedObjectReference) any);
                result = new RuntimeFaultFaultMsg("Reboot exception", new RuntimeFault());
            }
        };

        vm.reboot("vm-207");
    }

    @Test(expected = InvalidStateException.class)
    public void rebootVirtualMachineShouldThrowInvalidStateExceptionIfInvalidStateFaultMsgThrown() throws CloudException, InternalException, RuntimeFaultFaultMsg, InvalidStateFaultMsg, TaskInProgressFaultMsg, ToolsUnavailableFaultMsg {
        new Expectations(Vm.class) {
            {vm.retrieveObjectList(vsphereMock, "vmFolder", null, vmPSpec);
                result = virtualMachines;
            }
            {vm.getResourcePools(anyBoolean);
                result = daseinRootResourcePools;
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
                result = daseinDatacenter;
            }
            {vimPortMock.rebootGuest((ManagedObjectReference) any);
                result = new InvalidStateFaultMsg("Reboot exception", new InvalidState());
            }
        };

        vm.reboot("vm-207");
    }

    @Test(expected = TaskInProgressException.class)
    public void rebootVirtualMachineShouldThrowCloudExceptionIfTaskInProgressFaultMsgThrown() throws CloudException, InternalException, RuntimeFaultFaultMsg, InvalidStateFaultMsg, TaskInProgressFaultMsg, ToolsUnavailableFaultMsg {
        new Expectations(Vm.class) {
            {vm.retrieveObjectList(vsphereMock, "vmFolder", null, vmPSpec);
                result = virtualMachines;
            }
            {vm.getResourcePools(anyBoolean);
                result = daseinRootResourcePools;
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
                result = daseinDatacenter;
            }
            {vimPortMock.rebootGuest((ManagedObjectReference) any);
                result = new TaskInProgressFaultMsg("Reboot exception", new TaskInProgress());
            }
        };

        vm.reboot("vm-207");
    }

    @Test(expected = GeneralCloudException.class)
    public void rebootVirtualMachineShouldThrowCloudExceptionIfToolsUnavailableFaultMsgThrown() throws CloudException, InternalException, RuntimeFaultFaultMsg, InvalidStateFaultMsg, TaskInProgressFaultMsg, ToolsUnavailableFaultMsg {
        new Expectations(Vm.class) {
            {vm.retrieveObjectList(vsphereMock, "vmFolder", null, vmPSpec);
                result = virtualMachines;
            }
            {vm.getResourcePools(anyBoolean);
                result = daseinRootResourcePools;
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
                result = daseinDatacenter;
            }
            {vimPortMock.rebootGuest((ManagedObjectReference) any);
                result = new ToolsUnavailableFaultMsg("Reboot exception", new ToolsUnavailable());
            }
        };

        vm.reboot("vm-207");
    }

    @Test
    public void resumeVirtualMachine() throws CloudException, InternalException {
        new Expectations(Vm.class) {
            {vm.retrieveObjectList(vsphereMock, "vmFolder", null, vmPSpec);
                result = virtualMachines;
            }
            {vm.getResourcePools(anyBoolean);
                result = daseinRootResourcePools;
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
                result = daseinDatacenter;
            }
        };

        vm.resume("vm-211");
    }

    @Test(expected = ResourceNotFoundException.class)
    public void resumeVirtualMachineShouldThrowExceptionIfVmIsNull() throws CloudException, InternalException {
        new Expectations(Vm.class) {
            {vm.retrieveObjectList(vsphereMock, "vmFolder", null, vmPSpec);
                result = virtualMachines;
            }
            {vm.getResourcePools(anyBoolean);
                result = daseinRootResourcePools;
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
                result = daseinDatacenter;
            }
        };

        vm.resume("MyFakeVm");
    }

    @Test(expected = InternalException.class)
    public void resumeVirtualMachineShouldThrowExceptionIfVmIsRunning() throws CloudException, InternalException {
        new Expectations(Vm.class) {
            {vm.retrieveObjectList(vsphereMock, "vmFolder", null, vmPSpec);
                result = virtualMachines;
            }
            {vm.getResourcePools(anyBoolean);
                result = daseinRootResourcePools;
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
                result = daseinDatacenter;
            }
        };

        vm.resume("vm-207");
    }

    @Test(expected = GeneralCloudException.class)
    public void resumeVirtualMachineShouldThrowCloudExceptionIfRuntimeFaultFaultMsgThrown() throws CloudException, InternalException, RuntimeFaultFaultMsg, InvalidStateFaultMsg, TaskInProgressFaultMsg, FileFaultFaultMsg, InsufficientResourcesFaultFaultMsg, VmConfigFaultFaultMsg {
        new Expectations(Vm.class) {
            {vm.retrieveObjectList(vsphereMock, "vmFolder", null, vmPSpec);
                result = virtualMachines;
            }
            {vm.getResourcePools(anyBoolean);
                result = daseinRootResourcePools;
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
                result = daseinDatacenter;
            }
            {vimPortMock.powerOnVMTask((ManagedObjectReference) any, (ManagedObjectReference) any);
                result = new RuntimeFaultFaultMsg("Resume exception", new RuntimeFault());
            }
        };

        vm.resume("vm-211");
    }

    @Test(expected = InvalidStateException.class)
    public void resumeVirtualMachineShouldThrowInvalidStateExceptionIfInvalidStateFaultMsgThrown() throws CloudException, InternalException, RuntimeFaultFaultMsg, InvalidStateFaultMsg, TaskInProgressFaultMsg, FileFaultFaultMsg, InsufficientResourcesFaultFaultMsg, VmConfigFaultFaultMsg {
        new Expectations(Vm.class) {
            {vm.retrieveObjectList(vsphereMock, "vmFolder", null, vmPSpec);
                result = virtualMachines;
            }
            {vm.getResourcePools(anyBoolean);
                result = daseinRootResourcePools;
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
                result = daseinDatacenter;
            }
            {vimPortMock.powerOnVMTask((ManagedObjectReference) any, (ManagedObjectReference) any);
                result = new InvalidStateFaultMsg("Resume exception", new InvalidState());
            }
        };

        vm.resume("vm-211");
    }

    @Test(expected = TaskInProgressException.class)
    public void resumeVirtualMachineShouldThrowCloudExceptionIfTaskInProgressFaultMsgThrown() throws CloudException, InternalException, RuntimeFaultFaultMsg, InvalidStateFaultMsg, TaskInProgressFaultMsg, FileFaultFaultMsg, InsufficientResourcesFaultFaultMsg, VmConfigFaultFaultMsg {
        new Expectations(Vm.class) {
            {vm.retrieveObjectList(vsphereMock, "vmFolder", null, vmPSpec);
                result = virtualMachines;
            }
            {vm.getResourcePools(anyBoolean);
                result = daseinRootResourcePools;
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
                result = daseinDatacenter;
            }
            {vimPortMock.powerOnVMTask((ManagedObjectReference) any, (ManagedObjectReference) any);
                result = new TaskInProgressFaultMsg("Resume exception", new TaskInProgress());
            }
        };

        vm.resume("vm-211");
    }

    @Test(expected = GeneralCloudException.class)
    public void resumeVirtualMachineShouldThrowCloudExceptionIfFileFaultFaultMsgThrown() throws CloudException, InternalException, RuntimeFaultFaultMsg, InvalidStateFaultMsg, TaskInProgressFaultMsg, FileFaultFaultMsg, InsufficientResourcesFaultFaultMsg, VmConfigFaultFaultMsg {
        new Expectations(Vm.class) {
            {vm.retrieveObjectList(vsphereMock, "vmFolder", null, vmPSpec);
                result = virtualMachines;
            }
            {vm.getResourcePools(anyBoolean);
                result = daseinRootResourcePools;
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
                result = daseinDatacenter;
            }
            {vimPortMock.powerOnVMTask((ManagedObjectReference) any, (ManagedObjectReference) any);
                result = new FileFaultFaultMsg("Resume exception", new FileFault());
            }
        };

        vm.resume("vm-211");
    }

    @Test(expected = GeneralCloudException.class)
    public void resumeVirtualMachineShouldThrowCloudExceptionIfInsufficientResourcesFaultMsgThrown() throws CloudException, InternalException, RuntimeFaultFaultMsg, InvalidStateFaultMsg, TaskInProgressFaultMsg, FileFaultFaultMsg, InsufficientResourcesFaultFaultMsg, VmConfigFaultFaultMsg {
        new Expectations(Vm.class) {
            {vm.retrieveObjectList(vsphereMock, "vmFolder", null, vmPSpec);
                result = virtualMachines;
            }
            {vm.getResourcePools(anyBoolean);
                result = daseinRootResourcePools;
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
                result = daseinDatacenter;
            }
            {vimPortMock.powerOnVMTask((ManagedObjectReference) any, (ManagedObjectReference) any);
                result = new InsufficientResourcesFaultFaultMsg("Resume exception", new InsufficientResourcesFault());
            }
        };

        vm.resume("vm-211");
    }

    @Test(expected = GeneralCloudException.class)
    public void resumeVirtualMachineShouldThrowCloudExceptionIfVmConfigFaultMsgThrown() throws CloudException, InternalException, RuntimeFaultFaultMsg, InvalidStateFaultMsg, TaskInProgressFaultMsg, FileFaultFaultMsg, InsufficientResourcesFaultFaultMsg, VmConfigFaultFaultMsg {
        new Expectations(Vm.class) {
            {vm.retrieveObjectList(vsphereMock, "vmFolder", null, vmPSpec);
                result = virtualMachines;
            }
            {vm.getResourcePools(anyBoolean);
                result = daseinRootResourcePools;
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
                result = daseinDatacenter;
            }
            {vimPortMock.powerOnVMTask((ManagedObjectReference) any, (ManagedObjectReference) any);
                result = new VmConfigFaultFaultMsg("Resume exception", new VmConfigFault());
            }
        };

        vm.resume("vm-211");
    }

    @Test
    public void startVirtualMachine() throws CloudException, InternalException {
        new Expectations(Vm.class) {
            {vm.retrieveObjectList(vsphereMock, "vmFolder", null, vmPSpec);
                result = virtualMachines;
            }
            {vm.getResourcePools(anyBoolean);
                result = daseinRootResourcePools;
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
                result = daseinDatacenter;
            }
        };

        vm.start("vm-211");
    }

    @Test(expected = ResourceNotFoundException.class)
    public void startVirtualMachineShouldThrowExceptionIfVmIsNull() throws CloudException, InternalException {
        new Expectations(Vm.class) {
            {vm.retrieveObjectList(vsphereMock, "vmFolder", null, vmPSpec);
                result = virtualMachines;
            }
            {vm.getResourcePools(anyBoolean);
                result = daseinRootResourcePools;
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
                result = daseinDatacenter;
            }
        };

        vm.start("MyFakeVm");
    }

    @Test(expected = InternalException.class)
    public void startVirtualMachineShouldThrowExceptionIfVmIsRunning() throws CloudException, InternalException {
        new Expectations(Vm.class) {
            {vm.retrieveObjectList(vsphereMock, "vmFolder", null, vmPSpec);
                result = virtualMachines;
            }
            {vm.getResourcePools(anyBoolean);
                result = daseinRootResourcePools;
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
                result = daseinDatacenter;
            }
        };

        vm.start("vm-207");
    }

    @Test(expected = GeneralCloudException.class)
    public void startVirtualMachineShouldThrowCloudExceptionIfRuntimeFaultFaultMsgThrown() throws CloudException, InternalException, RuntimeFaultFaultMsg, InvalidStateFaultMsg, TaskInProgressFaultMsg, FileFaultFaultMsg, InsufficientResourcesFaultFaultMsg, VmConfigFaultFaultMsg {
        new Expectations(Vm.class) {
            {vm.retrieveObjectList(vsphereMock, "vmFolder", null, vmPSpec);
                result = virtualMachines;
            }
            {vm.getResourcePools(anyBoolean);
                result = daseinRootResourcePools;
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
                result = daseinDatacenter;
            }
            {vimPortMock.powerOnVMTask((ManagedObjectReference) any, (ManagedObjectReference) any);
                result = new RuntimeFaultFaultMsg("Start exception", new RuntimeFault());
            }
        };

        vm.start("vm-211");
    }

    @Test(expected = InvalidStateException.class)
    public void startVirtualMachineShouldThrowInvalidStateExceptionIfInvalidStateFaultMsgThrown() throws CloudException, InternalException, RuntimeFaultFaultMsg, InvalidStateFaultMsg, TaskInProgressFaultMsg, FileFaultFaultMsg, InsufficientResourcesFaultFaultMsg, VmConfigFaultFaultMsg {
        new Expectations(Vm.class) {
            {vm.retrieveObjectList(vsphereMock, "vmFolder", null, vmPSpec);
                result = virtualMachines;
            }
            {vm.getResourcePools(anyBoolean);
                result = daseinRootResourcePools;
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
                result = daseinDatacenter;
            }
            {vimPortMock.powerOnVMTask((ManagedObjectReference) any, (ManagedObjectReference) any);
                result = new InvalidStateFaultMsg("Start exception", new InvalidState());
            }
        };

        vm.start("vm-211");
    }

    @Test(expected = TaskInProgressException.class)
    public void startVirtualMachineShouldThrowCloudExceptionIfTaskInProgressFaultMsgThrown() throws CloudException, InternalException, RuntimeFaultFaultMsg, InvalidStateFaultMsg, TaskInProgressFaultMsg, FileFaultFaultMsg, InsufficientResourcesFaultFaultMsg, VmConfigFaultFaultMsg {
        new Expectations(Vm.class) {
            {vm.retrieveObjectList(vsphereMock, "vmFolder", null, vmPSpec);
                result = virtualMachines;
            }
            {vm.getResourcePools(anyBoolean);
                result = daseinRootResourcePools;
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
                result = daseinDatacenter;
            }
            {vimPortMock.powerOnVMTask((ManagedObjectReference) any, (ManagedObjectReference) any);
                result = new TaskInProgressFaultMsg("Start exception", new TaskInProgress());
            }
        };

        vm.start("vm-211");
    }

    @Test(expected = GeneralCloudException.class)
    public void startVirtualMachineShouldThrowCloudExceptionIfFileFaultFaultMsgThrown() throws CloudException, InternalException, RuntimeFaultFaultMsg, InvalidStateFaultMsg, TaskInProgressFaultMsg, FileFaultFaultMsg, InsufficientResourcesFaultFaultMsg, VmConfigFaultFaultMsg {
        new Expectations(Vm.class) {
            {vm.retrieveObjectList(vsphereMock, "vmFolder", null, vmPSpec);
                result = virtualMachines;
            }
            {vm.getResourcePools(anyBoolean);
                result = daseinRootResourcePools;
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
                result = daseinDatacenter;
            }
            {vimPortMock.powerOnVMTask((ManagedObjectReference) any, (ManagedObjectReference) any);
                result = new FileFaultFaultMsg("Start exception", new FileFault());
            }
        };

        vm.start("vm-211");
    }

    @Test(expected = GeneralCloudException.class)
    public void startVirtualMachineShouldThrowCloudExceptionIfInsufficientResourcesFaultMsgThrown() throws CloudException, InternalException, RuntimeFaultFaultMsg, InvalidStateFaultMsg, TaskInProgressFaultMsg, FileFaultFaultMsg, InsufficientResourcesFaultFaultMsg, VmConfigFaultFaultMsg {
        new Expectations(Vm.class) {
            {vm.retrieveObjectList(vsphereMock, "vmFolder", null, vmPSpec);
                result = virtualMachines;
            }
            {vm.getResourcePools(anyBoolean);
                result = daseinRootResourcePools;
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
                result = daseinDatacenter;
            }
            {vimPortMock.powerOnVMTask((ManagedObjectReference) any, (ManagedObjectReference) any);
                result = new InsufficientResourcesFaultFaultMsg("Start exception", new InsufficientResourcesFault());
            }
        };

        vm.start("vm-211");
    }

    @Test(expected = GeneralCloudException.class)
    public void startVirtualMachineShouldThrowCloudExceptionIfVmConfigFaultMsgThrown() throws CloudException, InternalException, RuntimeFaultFaultMsg, InvalidStateFaultMsg, TaskInProgressFaultMsg, FileFaultFaultMsg, InsufficientResourcesFaultFaultMsg, VmConfigFaultFaultMsg {
        new Expectations(Vm.class) {
            {vm.retrieveObjectList(vsphereMock, "vmFolder", null, vmPSpec);
                result = virtualMachines;
            }
            {vm.getResourcePools(anyBoolean);
                result = daseinRootResourcePools;
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
                result = daseinDatacenter;
            }
            {vimPortMock.powerOnVMTask((ManagedObjectReference) any, (ManagedObjectReference) any);
                result = new VmConfigFaultFaultMsg("Start exception", new VmConfigFault());
            }
        };

        vm.start("vm-211");
    }

    @Test
    public void stopVirtualMachine_Shutdown() throws CloudException, InternalException, RuntimeFaultFaultMsg, InvalidStateFaultMsg, TaskInProgressFaultMsg, ToolsUnavailableFaultMsg {
        new Expectations(Vm.class) {
            {vm.retrieveObjectList(vsphereMock, "vmFolder", null, vmPSpec);
                result = virtualMachines;
            }
            {vm.getResourcePools(anyBoolean);
                result = daseinRootResourcePools;
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
                result = daseinDatacenter;
            }
            {vimPortMock.powerOffVMTask((ManagedObjectReference) any);
                times = 0;
            }
            {vimPortMock.shutdownGuest((ManagedObjectReference) any);
                times = 1;
            }
        };

        vm.stop("vm-207", false);
    }

    @Test
    public void stopVirtualMachine_PowerOff() throws CloudException, InternalException, RuntimeFaultFaultMsg, InvalidStateFaultMsg, TaskInProgressFaultMsg, ToolsUnavailableFaultMsg {
        new Expectations(Vm.class) {
            {vm.retrieveObjectList(vsphereMock, "vmFolder", null, vmPSpec);
                result = virtualMachines;
            }
            {vm.getResourcePools(anyBoolean);
                result = daseinRootResourcePools;
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
                result = daseinDatacenter;
            }
            {vimPortMock.powerOffVMTask((ManagedObjectReference) any);
                times = 1;
            }
            {vimPortMock.shutdownGuest((ManagedObjectReference) any);
                times = 0;
            }
        };

        vm.stop("vm-207", true);
    }

    @Test(expected = ResourceNotFoundException.class)
    public void stopVirtualMachineShouldThrowExceptionIfVmIsNull() throws CloudException, InternalException {
        new Expectations(Vm.class) {
            {vm.retrieveObjectList(vsphereMock, "vmFolder", null, vmPSpec);
                result = virtualMachines;
            }
            {vm.getResourcePools(anyBoolean);
                result = daseinRootResourcePools;
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
                result = daseinDatacenter;
            }
        };

        vm.stop("MyFakeVm");
    }

    @Test(expected = InternalException.class)
    public void stopVirtualMachineShouldThrowExceptionIfVmIsStopped() throws CloudException, InternalException {
        new Expectations(Vm.class) {
            {vm.retrieveObjectList(vsphereMock, "vmFolder", null, vmPSpec);
                result = virtualMachines;
            }
            {vm.getResourcePools(anyBoolean);
                result = daseinRootResourcePools;
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
                result = daseinDatacenter;
            }
        };

        vm.stop("vm-211");
    }

    @Test(expected = GeneralCloudException.class)
    public void stopVirtualMachineShouldThrowCloudExceptionIfRuntimeFaultFaultMsgThrown() throws CloudException, InternalException, RuntimeFaultFaultMsg, InvalidStateFaultMsg, TaskInProgressFaultMsg {
        new Expectations(Vm.class) {
            {vm.retrieveObjectList(vsphereMock, "vmFolder", null, vmPSpec);
                result = virtualMachines;
            }
            {vm.getResourcePools(anyBoolean);
                result = daseinRootResourcePools;
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
                result = daseinDatacenter;
            }
            {vimPortMock.powerOffVMTask((ManagedObjectReference) any);
                result = new RuntimeFaultFaultMsg("Stop exception", new RuntimeFault());
            }
        };

        vm.stop("vm-207", true);
    }

    @Test(expected = InvalidStateException.class)
    public void stopVirtualMachineShouldThrowInvalidStateExceptionIfInvalidStateFaultMsgThrown() throws CloudException, InternalException, RuntimeFaultFaultMsg, InvalidStateFaultMsg, TaskInProgressFaultMsg {
        new Expectations(Vm.class) {
            {vm.retrieveObjectList(vsphereMock, "vmFolder", null, vmPSpec);
                result = virtualMachines;
            }
            {vm.getResourcePools(anyBoolean);
                result = daseinRootResourcePools;
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
                result = daseinDatacenter;
            }
            {vimPortMock.powerOffVMTask((ManagedObjectReference) any);
                result = new InvalidStateFaultMsg("Stop exception", new InvalidState());
            }
        };

        vm.stop("vm-207", true);
    }

    @Test(expected = TaskInProgressException.class)
    public void stopVirtualMachineShouldThrowCloudExceptionIfTaskInProgressFaultMsgThrown() throws CloudException, InternalException, RuntimeFaultFaultMsg, InvalidStateFaultMsg, TaskInProgressFaultMsg {
        new Expectations(Vm.class) {
            {vm.retrieveObjectList(vsphereMock, "vmFolder", null, vmPSpec);
                result = virtualMachines;
            }
            {vm.getResourcePools(anyBoolean);
                result = daseinRootResourcePools;
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
                result = daseinDatacenter;
            }
            {vimPortMock.powerOffVMTask((ManagedObjectReference) any);
                result = new TaskInProgressFaultMsg("Stop exception", new TaskInProgress());
            }
        };

        vm.stop("vm-207", true);
    }

    @Test(expected = GeneralCloudException.class)
    public void stopVirtualMachineShouldThrowCloudExceptionIfToolsUnavailableFaultMsgThrown() throws CloudException, InternalException, RuntimeFaultFaultMsg, InvalidStateFaultMsg, TaskInProgressFaultMsg, ToolsUnavailableFaultMsg {
        new Expectations(Vm.class) {
            {vm.retrieveObjectList(vsphereMock, "vmFolder", null, vmPSpec);
                result = virtualMachines;
            }
            {vm.getResourcePools(anyBoolean);
                result = daseinRootResourcePools;
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
                result = daseinDatacenter;
            }
            {vimPortMock.shutdownGuest((ManagedObjectReference) any);
                result = new ToolsUnavailableFaultMsg("Stop exception", new ToolsUnavailable());
            }
        };

        vm.stop("vm-207", false);
    }

    @Test
    public void suspendVirtualMachine() throws CloudException, InternalException {
        new Expectations(Vm.class) {
            {vm.retrieveObjectList(vsphereMock, "vmFolder", null, vmPSpec);
                result = virtualMachines;
            }
            {vm.getResourcePools(anyBoolean);
                result = daseinRootResourcePools;
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
                result = daseinDatacenter;
            }
        };

        vm.suspend("vm-207");
    }

    @Test(expected = ResourceNotFoundException.class)
    public void suspendVirtualMachineShouldThrowExceptionIfVmIsNull() throws CloudException, InternalException {
        new Expectations(Vm.class) {
            {vm.retrieveObjectList(vsphereMock, "vmFolder", null, vmPSpec);
                result = virtualMachines;
            }
            {vm.getResourcePools(anyBoolean);
                result = daseinRootResourcePools;
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
                result = daseinDatacenter;
            }
        };

        vm.suspend("MyFakeVm");
    }

    @Test(expected = InternalException.class)
    public void suspendVirtualMachineShouldThrowExceptionIfVmIsNotRunning() throws CloudException, InternalException {
        new Expectations(Vm.class) {
            {vm.retrieveObjectList(vsphereMock, "vmFolder", null, vmPSpec);
                result = virtualMachines;
            }
            {vm.getResourcePools(anyBoolean);
                result = daseinRootResourcePools;
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
                result = daseinDatacenter;
            }
        };

        vm.suspend("vm-211");
    }

    @Test(expected = GeneralCloudException.class)
    public void suspendVirtualMachineShouldThrowCloudExceptionIfRuntimeFaultFaultMsgThrown() throws CloudException, InternalException, RuntimeFaultFaultMsg, InvalidStateFaultMsg, TaskInProgressFaultMsg {
        new Expectations(Vm.class) {
            {vm.retrieveObjectList(vsphereMock, "vmFolder", null, vmPSpec);
                result = virtualMachines;
            }
            {vm.getResourcePools(anyBoolean);
                result = daseinRootResourcePools;
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
                result = daseinDatacenter;
            }
            {vimPortMock.suspendVMTask((ManagedObjectReference) any);
                result = new RuntimeFaultFaultMsg("Suspend exception", new RuntimeFault());
            }
        };

        vm.suspend("vm-207");
    }

    @Test(expected = InvalidStateException.class)
    public void suspendVirtualMachineShouldThrowInvalidStateExceptionIfInvalidStateFaultMsgThrown() throws CloudException, InternalException, RuntimeFaultFaultMsg, InvalidStateFaultMsg, TaskInProgressFaultMsg {
        new Expectations(Vm.class) {
            {vm.retrieveObjectList(vsphereMock, "vmFolder", null, vmPSpec);
                result = virtualMachines;
            }
            {vm.getResourcePools(anyBoolean);
                result = daseinRootResourcePools;
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
                result = daseinDatacenter;
            }
            {vimPortMock.suspendVMTask((ManagedObjectReference) any);
                result = new InvalidStateFaultMsg("Suspend exception", new InvalidState());
            }
        };

        vm.suspend("vm-207");
    }

    @Test(expected = TaskInProgressException.class)
    public void suspendVirtualMachineShouldThrowCloudExceptionIfTaskInProgressFaultMsgThrown() throws CloudException, InternalException, RuntimeFaultFaultMsg, InvalidStateFaultMsg, TaskInProgressFaultMsg {
        new Expectations(Vm.class) {
            {vm.retrieveObjectList(vsphereMock, "vmFolder", null, vmPSpec);
                result = virtualMachines;
            }
            {vm.getResourcePools(anyBoolean);
                result = daseinRootResourcePools;
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
                result = daseinDatacenter;
            }
            {vimPortMock.suspendVMTask((ManagedObjectReference) any);
                result = new TaskInProgressFaultMsg("Suspend exception", new TaskInProgress());
            }
        };

        vm.suspend("vm-207");
    }

    @Test
    public void terminateVirtualMachine() throws CloudException, InternalException {
        new Expectations(Vm.class) {
            {vm.retrieveObjectList(vsphereMock, "vmFolder", null, vmPSpec);
                result = virtualMachines;
                result = virtualMachinesPostTerminate;
            }
            {vm.getResourcePools(anyBoolean);
                result = daseinRootResourcePools;
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
                result = daseinDatacenter;
            }
        };

        vm.terminate("vm-211", "terminate test");
        VirtualMachine virtualMachine = vm.getVirtualMachine("vm-211");
        assertNull("Vm has been deleted but still returned", virtualMachine);
    }

    @Test
    public void terminateRunningVirtualMachine() throws CloudException, InternalException, RuntimeFaultFaultMsg, InvalidStateFaultMsg, TaskInProgressFaultMsg {
        new Expectations(Vm.class) {
            {vm.retrieveObjectList(vsphereMock, "vmFolder", null, vmPSpec);
                result = virtualMachines;
            }
            {vm.getResourcePools(anyBoolean);
                result = daseinRootResourcePools;
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
                result = daseinDatacenter;
            }
            {vimPortMock.powerOffVMTask((ManagedObjectReference) any);
                result = new ManagedObjectReference();
                times = 1;
            }
        };

        new Expectations(VsphereMethod.class) {
            {method.getOperationComplete((ManagedObjectReference) any, (TimePeriod) any, anyInt);
                result = true;
                times = 1;
            }
        };

        vm.terminate("vm-207", "terminate test");
    }

    public void terminateVirtualMachineShouldDoNothingIfVmIsNull() throws CloudException, InternalException, RuntimeFaultFaultMsg, InvalidStateFaultMsg, TaskInProgressFaultMsg, VimFaultFaultMsg {
        new Expectations(Vm.class) {
            {vm.retrieveObjectList(vsphereMock, "vmFolder", null, vmPSpec);
                result = virtualMachines;
            }
            {vm.getResourcePools(anyBoolean);
                result = daseinRootResourcePools;
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
                result = daseinDatacenter;
            }
            {vimPortMock.destroyTask((ManagedObjectReference) any);
                times = 0;
            }
        };

        vm.terminate("MyFakeVm", "terminate test");
    }

    @Test(expected = GeneralCloudException.class)
    public void terminateVirtualMachineShouldThrowExceptionIfStopOperationIsUnsuccessful() throws CloudException, InternalException, RuntimeFaultFaultMsg, InvalidStateFaultMsg, TaskInProgressFaultMsg {
        new Expectations(Vm.class) {
            {vm.retrieveObjectList(vsphereMock, "vmFolder", null, vmPSpec);
                result = virtualMachines;
            }
            {vm.getResourcePools(anyBoolean);
                result = daseinRootResourcePools;
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
                result = daseinDatacenter;
            }
            {vimPortMock.powerOffVMTask((ManagedObjectReference) any);
                result = new ManagedObjectReference();
                times = 1;
            }
        };

        new Expectations(VsphereMethod.class) {
            {method.getOperationComplete((ManagedObjectReference) any, (TimePeriod) any, anyInt);
                result = false;
                times = 1;
            }
            {method.getTaskError().getVal();
                result = ("Stop vm before terminate failed");
            }
        };

        vm.terminate("vm-207", "terminate test");
    }

    @Test(expected = GeneralCloudException.class)
    public void terminateVirtualMachineShouldThrowCloudExceptionIfRuntimeFaultFaultMsgThrown() throws CloudException, InternalException, RuntimeFaultFaultMsg, InvalidStateFaultMsg, TaskInProgressFaultMsg, VimFaultFaultMsg {
        new Expectations(Vm.class) {
            {vm.retrieveObjectList(vsphereMock, "vmFolder", null, vmPSpec);
                result = virtualMachines;
            }
            {vm.getResourcePools(anyBoolean);
                result = daseinRootResourcePools;
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
                result = daseinDatacenter;
            }
            {vimPortMock.destroyTask((ManagedObjectReference) any);
                result = new RuntimeFaultFaultMsg("Terminate exception", new RuntimeFault());
            }
        };

        vm.terminate("vm-211", "terminate test");
    }

    @Test(expected = InvalidStateException.class)
    public void terminateVirtualMachineShouldThrowInvalidStateExceptionIfInvalidStateFaultMsgThrown() throws CloudException, InternalException, RuntimeFaultFaultMsg, InvalidStateFaultMsg, TaskInProgressFaultMsg, VimFaultFaultMsg {
        new Expectations(Vm.class) {
            {vm.retrieveObjectList(vsphereMock, "vmFolder", null, vmPSpec);
                result = virtualMachines;
            }
            {vm.getResourcePools(anyBoolean);
                result = daseinRootResourcePools;
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
                result = daseinDatacenter;
            }
            {vimPortMock.destroyTask((ManagedObjectReference) any);
                result = new InvalidStateFaultMsg("Terminate exception", new InvalidState());
            }
        };

        vm.terminate("vm-211", "terminate test");
    }

    @Test(expected = TaskInProgressException.class)
    public void terminateVirtualMachineShouldThrowCloudExceptionIfTaskInProgressFaultMsgThrown() throws CloudException, InternalException, RuntimeFaultFaultMsg, InvalidStateFaultMsg, TaskInProgressFaultMsg, VimFaultFaultMsg {
        new Expectations(Vm.class) {
            {vm.retrieveObjectList(vsphereMock, "vmFolder", null, vmPSpec);
                result = virtualMachines;
            }
            {vm.getResourcePools(anyBoolean);
                result = daseinRootResourcePools;
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
                result = daseinDatacenter;
            }
            {vimPortMock.destroyTask((ManagedObjectReference) any);
                result = new TaskInProgressFaultMsg("Terminate exception", new TaskInProgress());
            }
        };

        vm.terminate("vm-211", "terminate test");
    }

    @Test(expected = GeneralCloudException.class)
    public void terminateVirtualMachineShouldThrowCloudExceptionIfVimFaultFaultMsgThrown() throws CloudException, InternalException, RuntimeFaultFaultMsg, InvalidStateFaultMsg, TaskInProgressFaultMsg, VimFaultFaultMsg {
        new Expectations(Vm.class) {
            {vm.retrieveObjectList(vsphereMock, "vmFolder", null, vmPSpec);
                result = virtualMachines;
            }
            {vm.getResourcePools(anyBoolean);
                result = daseinRootResourcePools;
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
                result = daseinDatacenter;
            }
            {vimPortMock.destroyTask((ManagedObjectReference) any);
                result = new VimFaultFaultMsg("Terminate exception", new VimFault());
            }
        };

        vm.terminate("vm-211", "terminate test");
    }

    @Test
    public void getResourcePools() throws CloudException, InternalException {
        new Expectations(Vm.class) {
            {vm.retrieveObjectList(vsphereMock, "hostFolder", rpSSpec, rpPSpec);
                result = resourcePools;
            }
        };

        Iterable<ResourcePool> rps = vm.getResourcePools(false);
        assertNotNull("There should always be at least one root resource pool", rps);
        ResourcePool rp = rps.iterator().next();
        assertNotNull("There should always be at least one root resource pool", rp);
        assertEquals("resgroup-35", rp.getProvideResourcePoolId());
        assertEquals("domain-c34", rp.getDataCenterId());

        int count = 0;
        for (ResourcePool r : rps) {
            count++;
        }
        assertEquals("Incorrect number of resource pools returned", 2, count);
    }

    @Test
    public void launchBasicVm() throws CloudException, InternalException {
        new Expectations(Vm.class) {
            {vm.retrieveObjectList(vsphereMock, "vmFolder", null, launchVmPSpec);
                result = launchVmTemplates;
                times = 1;
            }
            {vm.retrieveObjectList(vsphereMock, "vmFolder", null, vmPSpec);
                result = virtualMachinesPostLaunch;
                minTimes = 1;
            }
            {vm.getResourcePools(anyBoolean);
                result = daseinRootResourcePools;
            }
            {vm.cloneVmTask((ManagedObjectReference) any, (ManagedObjectReference) any, anyString, (VirtualMachineCloneSpec) any);
                result = task;
            }
            {vm.reconfigVMTask((ManagedObjectReference) any, (VirtualMachineConfigSpec) any);
                result = task;
            }
            {vm.start(anyString);
            }
        };

        new Expectations(VsphereMethod.class) {
            {method.getOperationComplete((ManagedObjectReference) any, (TimePeriod) any, anyInt);
                result = true;
                times = 2;
            }
            {method.getTaskResult();
                result = launchResult;
            }
        };

        new NonStrictExpectations() {
            {vsphereMock.getApiMajorVersion();
                result = 6;
            }
            {vsphereMock.getDataCenterServices();
                result = dcMock;
            }
            {dcMock.listDataCenters(anyString);
                result = daseinDatacenter;
            }
            {dcMock.listVMFolders();
                result = vmFolders;
            }
            {dcMock.getDataCenter(anyString);
                result = daseinDatacenter;
            }
        };

        VMLaunchOptions options = VMLaunchOptions.getInstance("1:1024", "vm-276", "testvm-stateful-1447150661369", "Test VM for stateful integration tests for Dasein Cloud");
        VirtualMachine v = vm.launch(options);
        assertNotNull(v);
        assertEquals("vm-284", v.getProviderVirtualMachineId());
        assertEquals("testvm-stateful-1447150661369", v.getName());
        assertEquals(Platform.UBUNTU, v.getPlatform());
        assertEquals(Architecture.I64, v.getArchitecture());
        assertEquals("Ubuntu Linux (64-bit)", v.getDescription());
        assertEquals("1:1024", v.getProductId());
        assertEquals("host-49", v.getAffinityGroupId());
        assertEquals("resgroup-46", v.getResourcePoolId());
        assertEquals("vm-276", v.getProviderMachineImageId());
        assertEquals("network-77", v.getProviderVlanId());
        assertNull(v.getPrivateDnsAddress());
        assertNull(v.getProviderAssignedIpAddressId());
        assertEquals(VmState.RUNNING, v.getCurrentState());
        assertTrue(v.isRebootable());
        assertEquals("TESTACCOUNTNO", v.getProviderOwnerId());
        assertEquals("domain-c45", v.getProviderDataCenterId());
        assertEquals("datacenter-2", v.getProviderRegionId());
        assertEquals("vm", v.getTag("vmFolder"));
        assertEquals("group-v3", v.getTag("vmFolderId"));
        assertEquals("datastore-62", v.getTag("datastore0"));
    }

    @Test
    public void launchVirtualMachineForAPIVersionLessThan6ShouldNotCallReconfigOrStartTasks() throws CloudException, InternalException {
        new Expectations(Vm.class) {
            {vm.retrieveObjectList(vsphereMock, "vmFolder", null, launchVmPSpec);
                result = launchVmTemplates;
                times = 1;
            }
            {vm.retrieveObjectList(vsphereMock, "vmFolder", null, vmPSpec);
                result = virtualMachinesPostLaunch;
                minTimes = 1;
            }
            {vm.getResourcePools(anyBoolean);
                result = daseinRootResourcePools;
            }
            {vm.cloneVmTask((ManagedObjectReference) any, (ManagedObjectReference) any, anyString, (VirtualMachineCloneSpec) any);
                result = task;
            }
            {vm.reconfigVMTask((ManagedObjectReference) any, (VirtualMachineConfigSpec) any);
                times = 0;
            }
            {vm.start(anyString);
                times = 0;
            }
        };

        new Expectations(VsphereMethod.class) {
            {method.getOperationComplete((ManagedObjectReference) any, (TimePeriod) any, anyInt);
                result = true;
                times = 1;
            }
            {method.getTaskResult();
                result = launchResult;
            }
        };

        new NonStrictExpectations() {
            {vsphereMock.getApiMajorVersion();
                result = 5;
            }
            {vsphereMock.getDataCenterServices();
                result = dcMock;
            }
            {dcMock.listDataCenters(anyString);
                result = daseinDatacenter;
            }
            {dcMock.listVMFolders();
                result = vmFolders;
            }
            {dcMock.getDataCenter(anyString);
                result = daseinDatacenter;
            }
        };

        VMLaunchOptions options = VMLaunchOptions.getInstance("1:1024", "vm-276", "testvm-stateful-1447150661369", "Test VM for stateful integration tests for Dasein Cloud");
        VirtualMachine v = vm.launch(options);
        assertNotNull(v);
        assertEquals("vm-284", v.getProviderVirtualMachineId());
        assertEquals("testvm-stateful-1447150661369", v.getName());
        assertEquals(Platform.UBUNTU, v.getPlatform());
        assertEquals(Architecture.I64, v.getArchitecture());
        assertEquals("Ubuntu Linux (64-bit)", v.getDescription());
        assertEquals("1:1024", v.getProductId());
        assertEquals("host-49", v.getAffinityGroupId());
        assertEquals("resgroup-46", v.getResourcePoolId());
        assertEquals("vm-276", v.getProviderMachineImageId());
        assertEquals("network-77", v.getProviderVlanId());
        assertNull(v.getPrivateDnsAddress());
        assertNull(v.getProviderAssignedIpAddressId());
        assertEquals(VmState.RUNNING, v.getCurrentState());
        assertTrue(v.isRebootable());
        assertEquals("TESTACCOUNTNO", v.getProviderOwnerId());
        assertEquals("domain-c45", v.getProviderDataCenterId());
        assertEquals("datacenter-2", v.getProviderRegionId());
        assertEquals("vm", v.getTag("vmFolder"));
        assertEquals("group-v3", v.getTag("vmFolderId"));
        assertEquals("datastore-62", v.getTag("datastore0"));
    }

    @Test
    public void launchFullyCustomisedWindowsVm() throws CloudException, InternalException {
        new Expectations(Vm.class) {
            {vm.retrieveObjectList(vsphereMock, "vmFolder", null, launchVmPSpec);
                result = launchVmTemplates;
                times = 1;
            }
            {vm.retrieveObjectList(vsphereMock, "vmFolder", null, vmPSpec);
                result = virtualMachinesPostLaunch;
                minTimes = 1;
            }
            {vm.getResourcePools(anyBoolean);
                result = daseinRootResourcePools;
            }
            {vm.cloneVmTask((ManagedObjectReference) any, (ManagedObjectReference) any, anyString, (VirtualMachineCloneSpec) any);
                result = task;
            }
            {vm.reconfigVMTask((ManagedObjectReference) any, (VirtualMachineConfigSpec) any);
                result = task;
            }
            {vm.start(anyString);
            }
        };

        new Expectations(VsphereMethod.class) {
            {method.getOperationComplete((ManagedObjectReference) any, (TimePeriod) any, anyInt);
                result = true;
                times = 2;
            }
            {method.getTaskResult();
                result = launchWinVmResult;
            }
        };

        new NonStrictExpectations() {
            {vsphereMock.getApiMajorVersion();
                result = 6;
            }
            {vsphereMock.getDataCenterServices();
                result = dcMock;
            }
            {dcMock.listDataCenters(anyString);
                result = daseinDatacenter;
            }
            {dcMock.listVMFolders();
                result = vmFolders;
            }
            {dcMock.getDataCenter(anyString);
                result = daseinDatacenter;
            }
            {vsphereMock.getNetworkServices();
                result = vsphereNetworkMock;
            }
            {vsphereNetworkMock.getVlanSupport();
                result = netMock;
            }
            {netMock.listVlans();
                result = daseinNetworks;
            }
        };

        VMLaunchOptions options = winLaunchOptions;
        VirtualMachine v = vm.launch(options);
        assertNotNull(v);
        assertEquals("vm-279", v.getProviderVirtualMachineId());
        assertEquals("testvm-stateful-1443524218811", v.getName());
        assertEquals(Platform.WINDOWS, v.getPlatform());
        assertEquals(Architecture.I64, v.getArchitecture());
        assertEquals("Microsoft Windows Server 2012 (64-bit)", v.getDescription());
        assertEquals("2:2048", v.getProductId());
        assertEquals("host-47", v.getAffinityGroupId());
        assertEquals("resgroup-46", v.getResourcePoolId());
        assertEquals("vm-87", v.getProviderMachineImageId());
        assertEquals("network-77", v.getProviderVlanId());
        assertEquals("testvm-stateful-1443524218811", v.getPrivateDnsAddress());
        assertEquals("192.168.7.16",  v.getProviderAssignedIpAddressId());
        assertEquals(VmState.RUNNING, v.getCurrentState());
        assertTrue(v.isRebootable());
        assertEquals("TESTACCOUNTNO", v.getProviderOwnerId());
        assertEquals("domain-c45", v.getProviderDataCenterId());
        assertEquals("datacenter-2", v.getProviderRegionId());
        assertEquals("Discovered virtual machine", v.getTag("vmFolder"));
        assertEquals("group-v133", v.getTag("vmFolderId"));
        assertEquals("datastore-61", v.getTag("datastore0"));
    }

    @Test
    public void launchCustomisedWindowsVmShouldSucceedIfSerialNumberIsNull() throws CloudException, InternalException {
        new Expectations(Vm.class) {
            {vm.retrieveObjectList(vsphereMock, "vmFolder", null, launchVmPSpec);
                result = launchVmTemplates;
                times = 1;
            }
            {vm.retrieveObjectList(vsphereMock, "vmFolder", null, vmPSpec);
                result = virtualMachinesPostLaunch;
                minTimes = 1;
            }
            {vm.getResourcePools(anyBoolean);
                result = daseinRootResourcePools;
            }
            {vm.cloneVmTask((ManagedObjectReference) any, (ManagedObjectReference) any, anyString, (VirtualMachineCloneSpec) any);
                result = task;
            }
            {vm.reconfigVMTask((ManagedObjectReference) any, (VirtualMachineConfigSpec) any);
                result = task;
            }
            {vm.start(anyString);
            }
        };

        new Expectations(VsphereMethod.class) {
            {method.getOperationComplete((ManagedObjectReference) any, (TimePeriod) any, anyInt);
                result = true;
                times = 2;
            }
            {method.getTaskResult();
                result = launchWinVmResult;
            }
        };

        new NonStrictExpectations() {
            {vsphereMock.getApiMajorVersion();
                result = 6;
            }
            {vsphereMock.getDataCenterServices();
                result = dcMock;
            }
            {dcMock.listDataCenters(anyString);
                result = daseinDatacenter;
            }
            {dcMock.listVMFolders();
                result = vmFolders;
            }
            {dcMock.getDataCenter(anyString);
                result = daseinDatacenter;
            }
            {vsphereMock.getNetworkServices();
                result = vsphereNetworkMock;
            }
            {vsphereNetworkMock.getVlanSupport();
                result = netMock;
            }
            {netMock.listVlans();
                result = daseinNetworks;
            }
        };

        VMLaunchOptions options = winLaunchOptionsNoSerial;
        VirtualMachine v = vm.launch(options);
        assertNotNull(v);
        assertEquals("vm-279", v.getProviderVirtualMachineId());
        assertEquals("testvm-stateful-1443524218811", v.getName());
        assertEquals(Platform.WINDOWS, v.getPlatform());
        assertEquals(Architecture.I64, v.getArchitecture());
        assertEquals("Microsoft Windows Server 2012 (64-bit)", v.getDescription());
        assertEquals("2:2048", v.getProductId());
        assertEquals("host-47", v.getAffinityGroupId());
        assertEquals("resgroup-46", v.getResourcePoolId());
        assertEquals("vm-87", v.getProviderMachineImageId());
        assertEquals("network-77", v.getProviderVlanId());
        assertEquals("testvm-stateful-1443524218811", v.getPrivateDnsAddress());
        assertEquals("192.168.7.16",  v.getProviderAssignedIpAddressId());
        assertEquals(VmState.RUNNING, v.getCurrentState());
        assertTrue(v.isRebootable());
        assertEquals("TESTACCOUNTNO", v.getProviderOwnerId());
        assertEquals("domain-c45", v.getProviderDataCenterId());
        assertEquals("datacenter-2", v.getProviderRegionId());
        assertEquals("Discovered virtual machine", v.getTag("vmFolder"));
        assertEquals("group-v133", v.getTag("vmFolderId"));
        assertEquals("datastore-61", v.getTag("datastore0"));
    }

    @Test
    public void launchVirtualMachineShouldReturnNullIfConfigPropertyIsMissingFromTemplateList() throws CloudException, InternalException {
        new Expectations(Vm.class) {
            {vm.retrieveObjectList(vsphereMock, "vmFolder", null, launchVmPSpec);
                result = launchVmTemplatesNoConfig;
            }
        };

        vm.launch(VMLaunchOptions.getInstance("1:1024", "vm-276", "testvm-stateful-1447150661369", "Test VM for stateful integration tests for Dasein Cloud"));
    }

    @Test(expected = InternalException.class)
    public void launchVirtualMachineShouldThrowExceptionIfRegionIsNull() throws CloudException, InternalException {
        new NonStrictExpectations() {
            {providerContextMock.getRegionId();
                result = null;
            }
        };

        vm.launch(VMLaunchOptions.getInstance("blah", "blah", "blah", "blah"));
    }

    @Test(expected = ResourceNotFoundException.class)
    public void launchVirtualMachineShouldThrowExceptionIfMachineImageIdIsInvalid() throws CloudException, InternalException {
        new Expectations(Vm.class) {
            {vm.retrieveObjectList(vsphereMock, "vmFolder", null, launchVmPSpec);
                result = launchVmTemplates;
            }

        };

        vm.launch(VMLaunchOptions.getInstance("blah", "MyFakeImage", "blah", "blah"));
    }

    @Test(expected = GeneralCloudException.class)
    public void launchVirtualMachineShouldThrowExceptionIfCloneTaskFails() throws CloudException, InternalException {
        new Expectations(Vm.class) {
            {vm.retrieveObjectList(vsphereMock, "vmFolder", null, launchVmPSpec);
                result = launchVmTemplates;
            }
            {vm.getResourcePools(anyBoolean);
                result = daseinRootResourcePools;
            }
            {vm.cloneVmTask((ManagedObjectReference) any, (ManagedObjectReference) any, anyString, (VirtualMachineCloneSpec) any);
                result = task;
            }
            {vm.reconfigVMTask((ManagedObjectReference) any, (VirtualMachineConfigSpec) any);
                times = 0;
            }
            {vm.start(anyString);
                times = 0;
            }
        };

        new Expectations(VsphereMethod.class) {
            {method.getOperationComplete((ManagedObjectReference) any, (TimePeriod) any, anyInt);
                result = false;
            }
            {method.getTaskResult();
                times = 0;
            }
            {method.getTaskError().getVal();
                result = "Clone during launch failed";
            }
        };

        new NonStrictExpectations() {
            {vsphereMock.getApiMajorVersion();
                result = 6;
            }
            {vsphereMock.getDataCenterServices();
                result = dcMock;
            }
            {dcMock.listVMFolders();
                result = vmFolders;
            }
        };

        VMLaunchOptions options = VMLaunchOptions.getInstance("1:1024", "vm-276", "testvm-stateful-1447150661369", "Test VM for stateful integration tests for Dasein Cloud");
        options.inDataCenter("domain-c45");
        vm.launch(options);
    }

    @Test(expected = GeneralCloudException.class)
    public void launchVirtualMachineShouldThrowExceptionIfApiVersion6AndReconfigTaskFails() throws CloudException, InternalException {
        new Expectations(Vm.class) {
            {vm.retrieveObjectList(vsphereMock, "vmFolder", null, launchVmPSpec);
                result = launchVmTemplates;
            }
            {vm.getResourcePools(anyBoolean);
                result = daseinRootResourcePools;
            }
            {vm.cloneVmTask((ManagedObjectReference) any, (ManagedObjectReference) any, anyString, (VirtualMachineCloneSpec) any);
                result = task;
            }
            {vm.reconfigVMTask((ManagedObjectReference) any, (VirtualMachineConfigSpec) any);
                result = task;
            }
            {vm.start(anyString);
                times = 0;
            }
        };

        new Expectations(VsphereMethod.class) {
            {method.getOperationComplete((ManagedObjectReference) any, (TimePeriod) any, anyInt);
                result = true;
                result = false;
            }
            {method.getTaskResult();
                result = launchResult;
            }
            {method.getTaskError().getVal();
                result = "Reconfig during launch failed";
            }
        };

        new NonStrictExpectations() {
            {vsphereMock.getApiMajorVersion();
                result = 6;
            }
            {vsphereMock.getDataCenterServices();
                result = dcMock;
            }
            {dcMock.listVMFolders();
                result = vmFolders;
            }
        };

        VMLaunchOptions options = VMLaunchOptions.getInstance("1:1024", "vm-276", "testvm-stateful-1447150661369", "Test VM for stateful integration tests for Dasein Cloud");
        options.inDataCenter("domain-c45");
        vm.launch(options);
    }

    //test will run for 20 minutes looking for the new vm so
    //update the timeout to a more reasonable value if you want to run this test
    @Ignore
    @Test(expected = ResourceNotFoundException.class)
    public void launchVirtualMachineShouldThrowExceptionIfNewVmIsNotFound() throws CloudException, InternalException {
        new Expectations(Vm.class) {
            {vm.retrieveObjectList(vsphereMock, "vmFolder", null, launchVmPSpec);
                result = launchVmTemplates;
                minTimes = 2;
            }
            {vm.getResourcePools(anyBoolean);
                result = daseinRootResourcePools;
            }
            {vm.cloneVmTask((ManagedObjectReference) any, (ManagedObjectReference) any, anyString, (VirtualMachineCloneSpec) any);
                result = task;
            }
            {vm.reconfigVMTask((ManagedObjectReference) any, (VirtualMachineConfigSpec) any);
                result = task;
            }
            {vm.start(anyString);
            }
        };

        new Expectations(VsphereMethod.class) {
            {method.getOperationComplete((ManagedObjectReference) any, (TimePeriod) any, anyInt);
                result = true;
                times = 2;
            }
            {method.getTaskResult();
                result = launchResult;
            }
        };

        new NonStrictExpectations() {
            {vsphereMock.getApiMajorVersion();
                result = 6;
            }
            {vsphereMock.getDataCenterServices();
                result = dcMock;
            }
            {dcMock.listDataCenters(anyString);
                result = daseinDatacenter;
            }
            {dcMock.listVMFolders();
                result = vmFolders;
            }
            {dcMock.getDataCenter(anyString);
                result = daseinDatacenter;
            }
        };

        VMLaunchOptions options = VMLaunchOptions.getInstance("1:1024", "vm-276", "testvm-stateful-1447150661369", "Test VM for stateful integration tests for Dasein Cloud");
        options.inDataCenter("domain-c45");
        vm.launch(options);
    }

    @Test(expected = ResourceNotFoundException.class)
    public void launchVirtualMachinesShouldThrowExceptionIfCloudReturnsNullObject() throws CloudException, InternalException {
        new Expectations(Vm.class) {
            {vm.retrieveObjectList(vsphereMock, "vmFolder", null, launchVmPSpec);
                result = null;
            }
        };

        vm.launch(VMLaunchOptions.getInstance("blah", "blah", "blah", "blah"));
    }

    @Test(expected = ResourceNotFoundException.class)
    public void launchVirtualMachinesShouldThrowExceptionIfCloudReturnsEmptyObject() throws CloudException, InternalException {
        new Expectations(Vm.class) {
            {vm.retrieveObjectList(vsphereMock, "vmFolder", null, launchVmPSpec);
                result = new RetrieveResult();
            }
        };

        vm.launch(VMLaunchOptions.getInstance("blah", "blah", "blah", "blah"));
    }

    @Test(expected = GeneralCloudException.class)
    public void launchCustomisedWindowsVmShouldThrowExceptionIfOwnerPropertyIsMissing() throws CloudException, InternalException, CustomizationFaultFaultMsg, FileFaultFaultMsg, InsufficientResourcesFaultFaultMsg,
            InvalidDatastoreFaultMsg, InvalidStateFaultMsg, MigrationFaultFaultMsg, RuntimeFaultFaultMsg,
            TaskInProgressFaultMsg, VmConfigFaultFaultMsg {

        new Expectations(Vm.class) {
            {vm.retrieveObjectList(vsphereMock, "vmFolder", null, launchVmPSpec);
                result = launchVmTemplates;
            }
            {vm.getResourcePools(anyBoolean);
                times = 0;
            }
            {vimPortMock.cloneVMTask((ManagedObjectReference) any, (ManagedObjectReference) any, anyString, (VirtualMachineCloneSpec) any);
                result = new GeneralCloudException("Missing param: owner", CloudErrorType.INVALID_USER_DATA);
            }
            {vm.reconfigVMTask((ManagedObjectReference) any, (VirtualMachineConfigSpec) any);
                result = task;
                times = 0;
            }
            {vm.start(anyString);
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
            {vsphereMock.getApiMajorVersion();
                result = 6;
            }
            {vsphereMock.getDataCenterServices();
                result = dcMock;
            }
            {dcMock.listDataCenters(anyString);
                result = daseinDatacenter;
            }
            {dcMock.listVMFolders();
                result = vmFolders;
            }
            {dcMock.getDataCenter(anyString);
                result = daseinDatacenter;
            }
            {vsphereMock.getNetworkServices();
                result = vsphereNetworkMock;
            }
            {vsphereNetworkMock.getVlanSupport();
                result = netMock;
            }
            {netMock.listVlans();
                result = daseinNetworks;
            }
        };

        VMLaunchOptions options = winLaunchOptionsNoOwner;
        vm.launch(options);

    }

    @Test(expected = GeneralCloudException.class)
    public void launchCustomisedWindowsVmShouldThrowExceptionIfOrgNamePropertyIsMissing() throws CloudException, InternalException, CustomizationFaultFaultMsg, FileFaultFaultMsg, InsufficientResourcesFaultFaultMsg,
            InvalidDatastoreFaultMsg, InvalidStateFaultMsg, MigrationFaultFaultMsg, RuntimeFaultFaultMsg,
            TaskInProgressFaultMsg, VmConfigFaultFaultMsg {

        new Expectations(Vm.class) {
            {vm.retrieveObjectList(vsphereMock, "vmFolder", null, launchVmPSpec);
                result = launchVmTemplates;
            }
            {vm.getResourcePools(anyBoolean);
                times = 0;
            }
            {vimPortMock.cloneVMTask((ManagedObjectReference) any, (ManagedObjectReference) any, anyString, (VirtualMachineCloneSpec) any);
                result = new GeneralCloudException("Missing param: org", CloudErrorType.INVALID_USER_DATA);
            }
            {vm.reconfigVMTask((ManagedObjectReference) any, (VirtualMachineConfigSpec) any);
                result = task;
                times = 0;
            }
            {vm.start(anyString);
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
            {vsphereMock.getApiMajorVersion();
                result = 6;
            }
            {vsphereMock.getDataCenterServices();
                result = dcMock;
            }
            {dcMock.listDataCenters(anyString);
                result = daseinDatacenter;
            }
            {dcMock.listVMFolders();
                result = vmFolders;
            }
            {dcMock.getDataCenter(anyString);
                result = daseinDatacenter;
            }
            {vsphereMock.getNetworkServices();
                result = vsphereNetworkMock;
            }
            {vsphereNetworkMock.getVlanSupport();
                result = netMock;
            }
            {netMock.listVlans();
                result = daseinNetworks;
            }
        };

        VMLaunchOptions options = winLaunchOptionsNoOrgName;
        vm.launch(options);
    }

    @Test(expected = GeneralCloudException.class)
    public void reconfigVmTaskShouldThrowCloudExceptionIfCloudReturnsConcurrentAccessFaultMsg() throws CloudException, InternalException,
            ConcurrentAccessFaultMsg, DuplicateNameFaultMsg, FileFaultFaultMsg, InsufficientResourcesFaultFaultMsg, InvalidDatastoreFaultMsg,
            InvalidNameFaultMsg, InvalidStateFaultMsg, RuntimeFaultFaultMsg, TaskInProgressFaultMsg, VmConfigFaultFaultMsg{
        new Expectations(Vm.class) {
            {vimPortMock.reconfigVMTask((ManagedObjectReference) any, (VirtualMachineConfigSpec) any);
                result = new ConcurrentAccessFaultMsg("Reconfig fault", new ConcurrentAccess());
            }
        };
        vm.reconfigVMTask(new ManagedObjectReference(), new VirtualMachineConfigSpec());
    }

    @Test(expected = GeneralCloudException.class)
    public void reconfigVmTaskShouldThrowInvalidRequestExceptionIfCloudReturnsDuplicateNameFaultMsg() throws CloudException, InternalException,
            ConcurrentAccessFaultMsg, DuplicateNameFaultMsg, FileFaultFaultMsg, InsufficientResourcesFaultFaultMsg, InvalidDatastoreFaultMsg,
            InvalidNameFaultMsg, InvalidStateFaultMsg, RuntimeFaultFaultMsg, TaskInProgressFaultMsg, VmConfigFaultFaultMsg{
        new Expectations(Vm.class) {
            {vimPortMock.reconfigVMTask((ManagedObjectReference) any, (VirtualMachineConfigSpec) any);
                result = new DuplicateNameFaultMsg("Reconfig fault", new DuplicateName());
            }
        };
        vm.reconfigVMTask(new ManagedObjectReference(), new VirtualMachineConfigSpec());
    }

    @Test(expected = GeneralCloudException.class)
    public void reconfigVmTaskShouldThrowCloudExceptionIfCloudReturnsFileFaultFaultMsg() throws CloudException, InternalException,
            ConcurrentAccessFaultMsg, DuplicateNameFaultMsg, FileFaultFaultMsg, InsufficientResourcesFaultFaultMsg, InvalidDatastoreFaultMsg,
            InvalidNameFaultMsg, InvalidStateFaultMsg, RuntimeFaultFaultMsg, TaskInProgressFaultMsg, VmConfigFaultFaultMsg{
        new Expectations(Vm.class) {
            {vimPortMock.reconfigVMTask((ManagedObjectReference) any, (VirtualMachineConfigSpec) any);
                result = new FileFaultFaultMsg("Reconfig fault", new FileFault());
            }
        };
        vm.reconfigVMTask(new ManagedObjectReference(), new VirtualMachineConfigSpec());
    }

    @Test(expected = GeneralCloudException.class)
    public void reconfigVmTaskShouldThrowCloudExceptionIfCloudReturnsInsufficientResourcesFaultFaultMsg() throws CloudException, InternalException,
            ConcurrentAccessFaultMsg, DuplicateNameFaultMsg, FileFaultFaultMsg, InsufficientResourcesFaultFaultMsg, InvalidDatastoreFaultMsg,
            InvalidNameFaultMsg, InvalidStateFaultMsg, RuntimeFaultFaultMsg, TaskInProgressFaultMsg, VmConfigFaultFaultMsg{
        new Expectations(Vm.class) {
            {vimPortMock.reconfigVMTask((ManagedObjectReference) any, (VirtualMachineConfigSpec) any);
                result = new InsufficientResourcesFaultFaultMsg("Reconfig fault", new InsufficientResourcesFault());
            }
        };
        vm.reconfigVMTask(new ManagedObjectReference(), new VirtualMachineConfigSpec());
    }

    @Test(expected = GeneralCloudException.class)
    public void reconfigVmTaskShouldThrowCloudExceptionIfCloudReturnsInvalidDatastoreFaultMsg() throws CloudException, InternalException,
            ConcurrentAccessFaultMsg, DuplicateNameFaultMsg, FileFaultFaultMsg, InsufficientResourcesFaultFaultMsg, InvalidDatastoreFaultMsg,
            InvalidNameFaultMsg, InvalidStateFaultMsg, RuntimeFaultFaultMsg, TaskInProgressFaultMsg, VmConfigFaultFaultMsg{
        new Expectations(Vm.class) {
            {vimPortMock.reconfigVMTask((ManagedObjectReference) any, (VirtualMachineConfigSpec) any);
                result = new InvalidDatastoreFaultMsg("Reconfig fault", new InvalidDatastore());
            }
        };
        vm.reconfigVMTask(new ManagedObjectReference(), new VirtualMachineConfigSpec());
    }

    @Test(expected = GeneralCloudException.class)
    public void reconfigVmTaskShouldThrowInvalidRequestExceptionIfCloudReturnsInvalidNameFaultMsg() throws CloudException, InternalException,
            ConcurrentAccessFaultMsg, DuplicateNameFaultMsg, FileFaultFaultMsg, InsufficientResourcesFaultFaultMsg, InvalidDatastoreFaultMsg,
            InvalidNameFaultMsg, InvalidStateFaultMsg, RuntimeFaultFaultMsg, TaskInProgressFaultMsg, VmConfigFaultFaultMsg{
        new Expectations(Vm.class) {
            {vimPortMock.reconfigVMTask((ManagedObjectReference) any, (VirtualMachineConfigSpec) any);
                result = new InvalidNameFaultMsg("Reconfig fault", new InvalidName());
            }
        };
        vm.reconfigVMTask(new ManagedObjectReference(), new VirtualMachineConfigSpec());
    }

    @Test(expected = InvalidStateException.class)
    public void reconfigVmTaskShouldThrowInvalidStateExceptionIfCloudReturnsInvalidStateFaultMsg() throws CloudException, InternalException,
            ConcurrentAccessFaultMsg, DuplicateNameFaultMsg, FileFaultFaultMsg, InsufficientResourcesFaultFaultMsg, InvalidDatastoreFaultMsg,
            InvalidNameFaultMsg, InvalidStateFaultMsg, RuntimeFaultFaultMsg, TaskInProgressFaultMsg, VmConfigFaultFaultMsg{
        new Expectations(Vm.class) {
            {vimPortMock.reconfigVMTask((ManagedObjectReference) any, (VirtualMachineConfigSpec) any);
                result = new InvalidStateFaultMsg("Reconfig fault", new InvalidState());
            }
        };
        vm.reconfigVMTask(new ManagedObjectReference(), new VirtualMachineConfigSpec());
    }

    @Test(expected = GeneralCloudException.class)
    public void reconfigVmTaskShouldThrowCloudExceptionIfCloudReturnsRuntimeFaultFaultMsg() throws CloudException, InternalException,
            ConcurrentAccessFaultMsg, DuplicateNameFaultMsg, FileFaultFaultMsg, InsufficientResourcesFaultFaultMsg, InvalidDatastoreFaultMsg,
            InvalidNameFaultMsg, InvalidStateFaultMsg, RuntimeFaultFaultMsg, TaskInProgressFaultMsg, VmConfigFaultFaultMsg{
        new Expectations(Vm.class) {
            {vimPortMock.reconfigVMTask((ManagedObjectReference) any, (VirtualMachineConfigSpec) any);
                result = new RuntimeFaultFaultMsg("Reconfig fault", new RuntimeFault());
            }
        };
        vm.reconfigVMTask(new ManagedObjectReference(), new VirtualMachineConfigSpec());
    }

    @Test(expected = TaskInProgressException.class)
    public void reconfigVmTaskShouldThrowCloudExceptionIfCloudReturnsTaskInProgressFaultMsg() throws CloudException, InternalException,
            ConcurrentAccessFaultMsg, DuplicateNameFaultMsg, FileFaultFaultMsg, InsufficientResourcesFaultFaultMsg, InvalidDatastoreFaultMsg,
            InvalidNameFaultMsg, InvalidStateFaultMsg, RuntimeFaultFaultMsg, TaskInProgressFaultMsg, VmConfigFaultFaultMsg{
        new Expectations(Vm.class) {
            {vimPortMock.reconfigVMTask((ManagedObjectReference) any, (VirtualMachineConfigSpec) any);
                result = new TaskInProgressFaultMsg("Reconfig fault", new TaskInProgress());
            }
        };
        vm.reconfigVMTask(new ManagedObjectReference(), new VirtualMachineConfigSpec());
    }

    @Test(expected = GeneralCloudException.class)
    public void reconfigVmTaskShouldThrowCloudExceptionIfCloudReturnVmConfigFaultFaultMsg() throws CloudException, InternalException,
            ConcurrentAccessFaultMsg, DuplicateNameFaultMsg, FileFaultFaultMsg, InsufficientResourcesFaultFaultMsg, InvalidDatastoreFaultMsg,
            InvalidNameFaultMsg, InvalidStateFaultMsg, RuntimeFaultFaultMsg, TaskInProgressFaultMsg, VmConfigFaultFaultMsg{
        new Expectations(Vm.class) {
            {vimPortMock.reconfigVMTask((ManagedObjectReference) any, (VirtualMachineConfigSpec) any);
                result = new VmConfigFaultFaultMsg("Reconfig fault", new VmConfigFault());
            }
        };
        vm.reconfigVMTask(new ManagedObjectReference(), new VirtualMachineConfigSpec());
    }

    @Test(expected = GeneralCloudException.class)
    public void cloneVmTaskShouldThrowCloudExceptionIfCloudReturnFileFaultFaultMsg() throws CloudException, InternalException,
            FileFaultFaultMsg, CustomizationFaultFaultMsg, InsufficientResourcesFaultFaultMsg, InvalidDatastoreFaultMsg,
            InvalidStateFaultMsg, MigrationFaultFaultMsg, RuntimeFaultFaultMsg, TaskInProgressFaultMsg, VmConfigFaultFaultMsg {
        new Expectations(Vm.class) {
            {vimPortMock.cloneVMTask((ManagedObjectReference) any, (ManagedObjectReference) any, anyString, (VirtualMachineCloneSpec) any);
                result = new FileFaultFaultMsg("Clone fault", new FileFault());
            }
        };
        vm.cloneVmTask(new ManagedObjectReference(), new ManagedObjectReference(), "", new VirtualMachineCloneSpec());
    }

    @Test(expected = GeneralCloudException.class)
    public void cloneVmTaskShouldThrowCloudExceptionIfCloudReturnCustomisationFaultFaultMsg() throws CloudException, InternalException,
            FileFaultFaultMsg, CustomizationFaultFaultMsg, InsufficientResourcesFaultFaultMsg, InvalidDatastoreFaultMsg,
            InvalidStateFaultMsg, MigrationFaultFaultMsg, RuntimeFaultFaultMsg, TaskInProgressFaultMsg, VmConfigFaultFaultMsg {
        new Expectations(Vm.class) {
            {vimPortMock.cloneVMTask((ManagedObjectReference) any, (ManagedObjectReference) any, anyString, (VirtualMachineCloneSpec) any);
                result = new CustomizationFaultFaultMsg("Clone fault", new CustomizationFault());
            }
        };
        vm.cloneVmTask(new ManagedObjectReference(), new ManagedObjectReference(), "", new VirtualMachineCloneSpec());
    }

    @Test(expected = GeneralCloudException.class)
    public void cloneVmTaskShouldThrowCloudExceptionIfCloudReturnInsufficientResourcesFaultFaultMsg() throws CloudException, InternalException,
            FileFaultFaultMsg, CustomizationFaultFaultMsg, InsufficientResourcesFaultFaultMsg, InvalidDatastoreFaultMsg,
            InvalidStateFaultMsg, MigrationFaultFaultMsg, RuntimeFaultFaultMsg, TaskInProgressFaultMsg, VmConfigFaultFaultMsg {
        new Expectations(Vm.class) {
            {vimPortMock.cloneVMTask((ManagedObjectReference) any, (ManagedObjectReference) any, anyString, (VirtualMachineCloneSpec) any);
                result = new InsufficientResourcesFaultFaultMsg("Clone fault", new InsufficientResourcesFault());
            }
        };
        vm.cloneVmTask(new ManagedObjectReference(), new ManagedObjectReference(), "", new VirtualMachineCloneSpec());
    }

    @Test(expected = GeneralCloudException.class)
    public void cloneVmTaskShouldThrowCloudExceptionIfCloudReturnInvalidDatastoreFaultMsg() throws CloudException, InternalException,
            FileFaultFaultMsg, CustomizationFaultFaultMsg, InsufficientResourcesFaultFaultMsg, InvalidDatastoreFaultMsg,
            InvalidStateFaultMsg, MigrationFaultFaultMsg, RuntimeFaultFaultMsg, TaskInProgressFaultMsg, VmConfigFaultFaultMsg {
        new Expectations(Vm.class) {
            {vimPortMock.cloneVMTask((ManagedObjectReference) any, (ManagedObjectReference) any, anyString, (VirtualMachineCloneSpec) any);
                result = new InvalidDatastoreFaultMsg("Clone fault", new InvalidDatastore());
            }
        };
        vm.cloneVmTask(new ManagedObjectReference(), new ManagedObjectReference(), "", new VirtualMachineCloneSpec());
    }

    @Test(expected = InvalidStateException.class)
    public void cloneVmTaskShouldThrowInvalidStateExceptionIfCloudReturnInvalidStateFaultMsg() throws CloudException, InternalException,
            FileFaultFaultMsg, CustomizationFaultFaultMsg, InsufficientResourcesFaultFaultMsg, InvalidDatastoreFaultMsg,
            InvalidStateFaultMsg, MigrationFaultFaultMsg, RuntimeFaultFaultMsg, TaskInProgressFaultMsg, VmConfigFaultFaultMsg {
        new Expectations(Vm.class) {
            {vimPortMock.cloneVMTask((ManagedObjectReference) any, (ManagedObjectReference) any, anyString, (VirtualMachineCloneSpec) any);
                result = new InvalidStateFaultMsg("Clone fault", new InvalidState());
            }
        };
        vm.cloneVmTask(new ManagedObjectReference(), new ManagedObjectReference(), "", new VirtualMachineCloneSpec());
    }

    @Test(expected = GeneralCloudException.class)
    public void cloneVmTaskShouldThrowCloudExceptionIfCloudReturnMigrationFaultFaultMsg() throws CloudException, InternalException,
            FileFaultFaultMsg, CustomizationFaultFaultMsg, InsufficientResourcesFaultFaultMsg, InvalidDatastoreFaultMsg,
            InvalidStateFaultMsg, MigrationFaultFaultMsg, RuntimeFaultFaultMsg, TaskInProgressFaultMsg, VmConfigFaultFaultMsg {
        new Expectations(Vm.class) {
            {vimPortMock.cloneVMTask((ManagedObjectReference) any, (ManagedObjectReference) any, anyString, (VirtualMachineCloneSpec) any);
                result = new MigrationFaultFaultMsg("Clone fault", new MigrationFault());
            }
        };
        vm.cloneVmTask(new ManagedObjectReference(), new ManagedObjectReference(), "", new VirtualMachineCloneSpec());
    }

    @Test(expected = GeneralCloudException.class)
    public void cloneVmTaskShouldThrowCloudExceptionIfCloudReturnRuntimeFaultFaultMsg() throws CloudException, InternalException,
            FileFaultFaultMsg, CustomizationFaultFaultMsg, InsufficientResourcesFaultFaultMsg, InvalidDatastoreFaultMsg,
            InvalidStateFaultMsg, MigrationFaultFaultMsg, RuntimeFaultFaultMsg, TaskInProgressFaultMsg, VmConfigFaultFaultMsg {
        new Expectations(Vm.class) {
            {vimPortMock.cloneVMTask((ManagedObjectReference) any, (ManagedObjectReference) any, anyString, (VirtualMachineCloneSpec) any);
                result = new RuntimeFaultFaultMsg("Clone fault", new RuntimeFault());
            }
        };
        vm.cloneVmTask(new ManagedObjectReference(), new ManagedObjectReference(), "", new VirtualMachineCloneSpec());
    }

    @Test(expected = TaskInProgressException.class)
    public void cloneVmTaskShouldThrowCloudExceptionIfCloudReturnTaskInProgressFaultMsg() throws CloudException, InternalException,
            FileFaultFaultMsg, CustomizationFaultFaultMsg, InsufficientResourcesFaultFaultMsg, InvalidDatastoreFaultMsg,
            InvalidStateFaultMsg, MigrationFaultFaultMsg, RuntimeFaultFaultMsg, TaskInProgressFaultMsg, VmConfigFaultFaultMsg {
        new Expectations(Vm.class) {
            {vimPortMock.cloneVMTask((ManagedObjectReference) any, (ManagedObjectReference) any, anyString, (VirtualMachineCloneSpec) any);
                result = new TaskInProgressFaultMsg("Clone fault", new TaskInProgress());
            }
        };
        vm.cloneVmTask(new ManagedObjectReference(), new ManagedObjectReference(), "", new VirtualMachineCloneSpec());
    }

    @Test(expected = GeneralCloudException.class)
    public void cloneVmTaskShouldThrowCloudExceptionIfCloudReturnVmConfigFaultFaultMsg() throws CloudException, InternalException,
            FileFaultFaultMsg, CustomizationFaultFaultMsg, InsufficientResourcesFaultFaultMsg, InvalidDatastoreFaultMsg,
            InvalidStateFaultMsg, MigrationFaultFaultMsg, RuntimeFaultFaultMsg, TaskInProgressFaultMsg, VmConfigFaultFaultMsg {
        new Expectations(Vm.class) {
            {vimPortMock.cloneVMTask((ManagedObjectReference) any, (ManagedObjectReference) any, anyString, (VirtualMachineCloneSpec) any);
                result = new VmConfigFaultFaultMsg("Clone fault", new VmConfigFault());
            }
        };
        vm.cloneVmTask(new ManagedObjectReference(), new ManagedObjectReference(), "", new VirtualMachineCloneSpec());
    }
}
