package org.dasein.cloud.vsphere.compute;

import com.vmware.vim25.*;
import org.apache.log4j.Logger;
import org.dasein.cloud.CloudException;
import org.dasein.cloud.InternalException;
import org.dasein.cloud.ProviderContext;
import org.dasein.cloud.compute.*;
import org.dasein.cloud.dc.DataCenter;
import org.dasein.cloud.dc.Folder;
import org.dasein.cloud.dc.ResourcePool;
import org.dasein.cloud.network.RawAddress;
import org.dasein.cloud.util.APITrace;
import org.dasein.cloud.util.Cache;
import org.dasein.cloud.util.CacheLevel;
import org.dasein.cloud.vsphere.*;
import org.dasein.cloud.vsphere.capabilities.VmCapabilities;
import org.dasein.util.CalendarWrapper;
import org.dasein.util.uom.storage.Gigabyte;
import org.dasein.util.uom.storage.Megabyte;
import org.dasein.util.uom.storage.Storage;
import org.dasein.util.uom.time.Day;
import org.dasein.util.uom.time.Minute;
import org.dasein.util.uom.time.Second;
import org.dasein.util.uom.time.TimePeriod;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.xml.datatype.XMLGregorianCalendar;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.*;


public class Vm extends AbstractVMSupport<Vsphere> {
    static private final Logger logger = Vsphere.getLogger(Vm.class);
    Vsphere provider = null;
    private List<PropertySpec> virtualMachinePSpec;
    private List<PropertySpec> rpPSpecs;
    private List<SelectionSpec> rpSSpecs;
    private DataCenters dc;

    protected Vm(Vsphere provider) {
        super(provider);
        this.provider = provider;
        dc = provider.getDataCenterServices();
    }

    @Nonnull
    @Override
    public VirtualMachine alterVirtualMachineSize(@Nonnull String virtualMachineId, @Nullable String cpuCount, @Nullable String ramInMB) throws InternalException, CloudException {
        APITrace.begin(getProvider(), "Vm.alterVirtualMachineSize");
        try {
            VirtualMachine vm = getVirtualMachine(virtualMachineId);
            if( vm == null ) {
                throw new CloudException("Unable to find vm with id " + virtualMachineId);
            }

            if( cpuCount == null && ramInMB == null ) {
                throw new CloudException("No cpu count or ram change provided");
            }
            int cpuCountVal;
            long memoryVal;


            VirtualMachineConfigSpec spec = new VirtualMachineConfigSpec();
            if( ramInMB != null ) {
                memoryVal = Long.parseLong(ramInMB);
                spec.setMemoryMB(memoryVal);
            }
            if( cpuCount != null ) {
                cpuCountVal = Integer.parseInt(cpuCount);
                spec.setNumCPUs(cpuCountVal);
                spec.setCpuHotAddEnabled(true);
                spec.setNumCoresPerSocket(cpuCountVal);
            }

            ManagedObjectReference vmRef = new ManagedObjectReference();
            vmRef.setValue(virtualMachineId);
            vmRef.setType("VirtualMachine");

            CloudException lastError;
            ManagedObjectReference taskMor = reconfigVMTask(vmRef, spec);
            VsphereMethod method = new VsphereMethod(provider);
            TimePeriod interval = new TimePeriod<Second>(15, TimePeriod.SECOND);

            if( taskMor != null && !method.getOperationComplete(taskMor, interval, 4) ) {
                long timeout = System.currentTimeMillis() + ( CalendarWrapper.MINUTE * 20L );

                while( System.currentTimeMillis() < timeout ) {
                    try {
                        Thread.sleep(10000L);
                    }
                    catch( InterruptedException ignore ) {
                    }

                    for( VirtualMachine s : listVirtualMachines() ) {
                        if( s.getProviderVirtualMachineId().equals(virtualMachineId) ) {
                            return s;
                        }
                    }
                }
                lastError = new CloudException("Unable to identify updated server.");
            }
            else {
                lastError = new CloudException("Failed to update VM: " + method.getTaskError().getVal());
            }
            if( lastError != null ) {
                throw lastError;
            }
            throw new CloudException("No server and no error");
        }
        finally {
            APITrace.end();
        }
    }

    @Nonnull
    @Override
    public VirtualMachine clone(@Nonnull String vmId, @Nonnull String intoDcId, @Nonnull String name, @Nonnull String description, boolean powerOn, @Nullable String... firewallIds) throws InternalException, CloudException {
        //todo WIP
        return super.clone(vmId, intoDcId, name, description, powerOn, firewallIds);
    }

    public RetrieveResult retrieveObjectList(Vsphere provider, @Nonnull String baseFolder, @Nullable List<SelectionSpec> selectionSpecsArr, @Nonnull List<PropertySpec> pSpecs) throws InternalException, CloudException {
        VsphereInventoryNavigation nav = new VsphereInventoryNavigation();
        return nav.retrieveObjectList(provider, baseFolder, selectionSpecsArr, pSpecs);
    }

    public List<PropertySpec> getVirtualMachinePSpec() {
        virtualMachinePSpec = VsphereTraversalSpec.createPropertySpec(virtualMachinePSpec, "VirtualMachine", false, "runtime", "config", "parent", "resourcePool", "guest");
        return virtualMachinePSpec;
    }

    public List<SelectionSpec> getResourcePoolSelectionSpec() {
        if (rpSSpecs == null) {
            rpSSpecs = new ArrayList<SelectionSpec>();
            // Recurse through all ResourcePools
            SelectionSpec sSpec = new SelectionSpec();
            sSpec.setName("rpToRp");

            TraversalSpec rpToRp = new TraversalSpec();
            rpToRp.setType("ResourcePool");
            rpToRp.setPath("resourcePool");
            rpToRp.setSkip(Boolean.FALSE);
            rpToRp.setName("rpToRp");
            rpToRp.getSelectSet().add(sSpec);

            TraversalSpec crToRp = new TraversalSpec();
            crToRp.setType("ComputeResource");
            crToRp.setPath("resourcePool");
            crToRp.setSkip(Boolean.FALSE);
            crToRp.setName("crToRp");
            crToRp.getSelectSet().add(sSpec);

            rpSSpecs.add(sSpec);
            rpSSpecs.add(rpToRp);
            rpSSpecs.add(crToRp);
        }
        return rpSSpecs;
    }

    public List<PropertySpec> getResourcePoolPropertySpec() {
        rpPSpecs = VsphereTraversalSpec.createPropertySpec(rpPSpecs, "ResourcePool", false, "owner");
        return rpPSpecs;
    }

    @Override
    public @Nullable VirtualMachineProduct getProduct(@Nonnull String productId) throws InternalException, CloudException {
        APITrace.begin(getProvider(), "Vm.getProduct(String)");
        try {
            for( VirtualMachineProduct product : listProducts("ignoreme", null) ) {
                if( product.getProviderProductId().equals(productId) ) {
                    return product;
                }
            }

            //Product is non-standard so build a new one
            String[] parts = productId.split(":");
            VirtualMachineProduct product = new VirtualMachineProduct();
            product.setCpuCount(Integer.parseInt(parts[0]));
            product.setRamSize(new Storage<Megabyte>(Integer.parseInt(parts[1]), Storage.MEGABYTE));
            product.setDescription("Custom product " + parts[0] + " CPU, " + parts[1] + " RAM");
            product.setName(parts[0] + " CPU/" + parts[1] + " MB RAM");
            product.setRootVolumeSize(new Storage<Gigabyte>(1, Storage.GIGABYTE));
            product.setProviderProductId(parts[0] + ":" + parts[1]);
            return product;

        }
        finally {
            APITrace.end();
        }
    }

    private @Nonnull VirtualMachineProduct getProduct(@Nonnull VirtualHardware hardware) throws InternalException, CloudException {
        APITrace.begin(getProvider(), "Vm.getProduct(VirtualHardware)");
        VirtualMachineProduct product = getProduct(hardware.getNumCPU() + ":" + hardware.getMemoryMB());

        if( product == null ) {
            int cpu = hardware.getNumCPU();
            int ram = hardware.getMemoryMB();
            int disk = 1;

            product = new VirtualMachineProduct();
            product.setCpuCount(cpu);
            product.setDescription("Custom product " + cpu + " CPU, " + ram + " RAM");
            product.setName(cpu + " CPU/" + ram + " MB RAM");
            product.setRootVolumeSize(new Storage<Gigabyte>(disk, Storage.GIGABYTE));
            product.setProviderProductId(cpu + ":" + ram);
        }
        return product;
    }

    @Nonnull
    @Override
    public Iterable<VirtualMachineProduct> listProducts(
            /** ignored **/ @Nonnull String machineImageId,
            @Nullable VirtualMachineProductFilterOptions options) throws InternalException, CloudException {

        APITrace.begin(getProvider(), "Vm.listProducts(String, VirtualMachineProductFilterOptions)");
        try {
            // get resource pools from cache or live
            Cache<ResourcePool> cache = Cache.getInstance(
                    getProvider(), "resourcePools", ResourcePool.class, CacheLevel.REGION_ACCOUNT,
                    new TimePeriod<Minute>(15, TimePeriod.MINUTE));
            List<ResourcePool> rps = (ArrayList<ResourcePool>) cache.get(getContext());

            if( rps == null ) {
                rps = new ArrayList<ResourcePool>();

                Collection<ResourcePool> pools = getProvider().getDataCenterServices().listResourcePools(null);
                rps.addAll(pools);
                cache.put(getContext(), rps);
            }

            List<VirtualMachineProduct> results = new ArrayList<VirtualMachineProduct>();
            Iterable<VirtualMachineProduct> jsonProducts = listProductsJson();
            // first add all matching products from vmproducts.json
            for( VirtualMachineProduct product : jsonProducts ) {
                if( options == null || options.matches(product) ) {
                    results.add(product);
                }
            }

            // second add same products but augmented with the resource pool info, ordered by pool name
            for( org.dasein.cloud.dc.ResourcePool pool : rps ) {
                for( VirtualMachineProduct product : jsonProducts ) {
                    VirtualMachineProduct tmp = new VirtualMachineProduct();
                    tmp.setName("Pool " + pool.getName() + "/" + product.getName());
                    tmp.setProviderProductId(pool.getProvideResourcePoolId() + ":" + product.getProviderProductId());
                    tmp.setRootVolumeSize(product.getRootVolumeSize());
                    tmp.setCpuCount(product.getCpuCount());
                    tmp.setDescription(product.getDescription());
                    tmp.setRamSize(product.getRamSize());
                    tmp.setStandardHourlyRate(product.getStandardHourlyRate());

                    if( options == null || options.matches(product) ) {
                        results.add(tmp);
                    }
                }
            }
            return results;
        }
        finally {
            APITrace.end();
        }
    }

    /**
     * Load products list from vmproducts.json filtered by architecture if specified in the options.
     * Uses a cache for one day.
     * @return list of products
     * @throws InternalException
     */
    private @Nonnull Iterable<VirtualMachineProduct> listProductsJson() throws InternalException {
        APITrace.begin(getProvider(), "VM.listProducts");
        try {
            Cache<VirtualMachineProduct> cache = Cache.getInstance(getProvider(), "products", VirtualMachineProduct.class, CacheLevel.REGION_ACCOUNT, new TimePeriod<Day>(1, TimePeriod.DAY));
            Iterable<VirtualMachineProduct> products = cache.get(getContext());

            if( products != null && products.iterator().hasNext() ) {
                return products;
            }

            List<VirtualMachineProduct> list = new ArrayList<VirtualMachineProduct>();

            try {
                InputStream input = AbstractVMSupport.class.getResourceAsStream("/org/dasein/cloud/vsphere/vmproducts.json");

                if( input == null ) {
                    return Collections.emptyList();
                }
                BufferedReader reader = new BufferedReader(new InputStreamReader(input));
                StringBuilder json = new StringBuilder();
                String line;

                while( ( line = reader.readLine() ) != null ) {
                    json.append(line);
                    json.append("\n");
                }
                JSONArray arr = new JSONArray(json.toString());
                JSONObject toCache = null;

                for( int i = 0; i < arr.length(); i++ ) {
                    JSONObject productSet = arr.getJSONObject(i);
                    String cloud, provider;

                    if( productSet.has("cloud") ) {
                        cloud = productSet.getString("cloud");
                    }
                    else {
                        continue;
                    }
                    if( productSet.has("provider") ) {
                        provider = productSet.getString("provider");
                    }
                    else {
                        continue;
                    }
                    if( !productSet.has("products") ) {
                        continue;
                    }
                    if( toCache == null || ( provider.equals("vSphere") && cloud.equals("vSphere") ) ) {
                        toCache = productSet;
                    }
                    if( provider.equalsIgnoreCase(getProvider().getProviderName()) && cloud.equalsIgnoreCase(getProvider().getCloudName()) ) {
                        toCache = productSet;
                        break;
                    }
                }
                if( toCache == null ) {
                    return Collections.emptyList();
                }
                JSONArray plist = toCache.getJSONArray("products");

                for( int i = 0; i < plist.length(); i++ ) {
                    JSONObject product = plist.getJSONObject(i);
                    boolean supported = true;
                    if( product.has("excludesRegions") ) {
                        JSONArray regions = product.getJSONArray("excludesRegions");

                        for( int j = 0; j < regions.length(); j++ ) {
                            String r = regions.getString(j);

                            if( r.equals(getContext().getRegionId()) ) {
                                supported = false;
                                break;
                            }
                        }
                    }
                    if( supported ) {
                        list.add(toProduct(product));
                    }
                }
                // save the products to cache
                cache.put(getContext(), list);
            } catch( IOException e ) {
                throw new InternalException(e);
            } catch( JSONException e ) {
                throw new InternalException(e);
            }
            return list;
        } finally {
            APITrace.end();
        }

    }

    private @Nullable
    VirtualMachineProduct toProduct( @Nonnull JSONObject json ) throws InternalException {
        VirtualMachineProduct prd = new VirtualMachineProduct();

        try {
            if( json.has("id") ) {
                prd.setProviderProductId(json.getString("id"));
            }
            else {
                return null;
            }
            if( json.has("name") ) {
                prd.setName(json.getString("name"));
            }
            else {
                prd.setName(prd.getProviderProductId());
            }
            if( json.has("description") ) {
                prd.setDescription(json.getString("description"));
            }
            else {
                prd.setDescription(prd.getName());
            }
            if( json.has("cpuCount") ) {
                prd.setCpuCount(json.getInt("cpuCount"));
            }
            else {
                prd.setCpuCount(1);
            }
            if( json.has("rootVolumeSizeInGb") ) {
                prd.setRootVolumeSize(new Storage<Gigabyte>(json.getInt("rootVolumeSizeInGb"), Storage.GIGABYTE));
            }
            else {
                prd.setRootVolumeSize(new Storage<Gigabyte>(1, Storage.GIGABYTE));
            }
            if( json.has("ramSizeInMb") ) {
                prd.setRamSize(new Storage<Megabyte>(json.getInt("ramSizeInMb"), Storage.MEGABYTE));
            }
            else {
                prd.setRamSize(new Storage<Megabyte>(512, Storage.MEGABYTE));
            }
            if( json.has("standardHourlyRates") ) {
                JSONArray rates = json.getJSONArray("standardHourlyRates");

                for( int i = 0; i < rates.length(); i++ ) {
                    JSONObject rate = rates.getJSONObject(i);

                    if( rate.has("rate") ) {
                        prd.setStandardHourlyRate(( float ) rate.getDouble("rate"));
                    }
                }
            }
        } catch( JSONException e ) {
            throw new InternalException(e);
        }
        return prd;
    }

    @Nonnull
    @Override
    public Iterable<VirtualMachine> listVirtualMachines() throws InternalException, CloudException {
        APITrace.begin(provider, "Vm.listVirtualMachines");
        try {
            List<VirtualMachine> list = new ArrayList<VirtualMachine>();
            ProviderContext ctx = provider.getContext();
            if (ctx == null) {
                throw new NoContextException();
            }
            if (ctx.getRegionId() == null) {
                throw new CloudException("Region id is not set");
            }

            //get attached volumes
            List<PropertySpec> pSpecs = getVirtualMachinePSpec();
            RetrieveResult listobcont = retrieveObjectList(provider, "vmFolder", null, pSpecs);

            if (listobcont != null) {
                Iterable<ResourcePool> rps = getAllResourcePoolsIncludingRoot();//return all resourcePools
                Iterable<Folder> vmFolders = dc.listVMFolders();
                List<ObjectContent> objectContents = listobcont.getObjects();
                for (ObjectContent oc : objectContents) {
                    boolean isTemplate = false;

                    ManagedObjectReference vmRef = oc.getObj();
                    String vmId = vmRef.getValue();

                    List<DynamicProperty> dps = oc.getPropSet();
                    VirtualMachineConfigInfo vmInfo = null;
                    ManagedObjectReference rpRef = null, parentRef = null;
                    String dataCenterId = null, vmFolderName = null;
                    GuestInfo guestInfo = null;
                    VirtualMachineRuntimeInfo vmRuntimeInfo = null;
                    label:
                    for (DynamicProperty dp : dps) {
                        switch (dp.getName()) {
                            case "config":
                                vmInfo = (VirtualMachineConfigInfo) dp.getVal();
                                if (vmInfo.isTemplate()) {
                                    isTemplate = true;
                                    break label;
                                }
                                break;
                            case "resourcePool":
                                rpRef = (ManagedObjectReference) dp.getVal();
                                String resourcePoolId = rpRef.getValue();
                                for (ResourcePool rp : rps) {
                                    if (rp.getProvideResourcePoolId().equals(resourcePoolId)) {
                                        dataCenterId = rp.getDataCenterId();
                                        break;
                                    }
                                }
                                break;
                            case "guest":
                                guestInfo = (GuestInfo) dp.getVal();
                                break;
                            case "runtime":
                                vmRuntimeInfo = (VirtualMachineRuntimeInfo) dp.getVal();
                                break;
                            case "parent":
                                parentRef = (ManagedObjectReference) dp.getVal();
                                for (Folder vmFolder : vmFolders) {
                                    if (vmFolder.getId().equals(parentRef.getValue())) {
                                        vmFolderName = vmFolder.getName();
                                        break;
                                    }
                                }
                                break;
                        }
                    }
                    if (!isTemplate) {
                        VirtualMachine vm = toVirtualMachine(vmId, vmInfo, guestInfo, vmRuntimeInfo);
                        if (vm != null) {
                            if (dataCenterId != null) {
                                DataCenter ourDC = getProvider().getDataCenterServices().getDataCenter(dataCenterId);
                                if (ourDC != null) {
                                    vm.setProviderDataCenterId(dataCenterId);
                                    vm.setProviderRegionId(ourDC.getRegionId());
                                } else if (dataCenterId.equals(getContext().getRegionId())) {
                                    // env doesn't have clusters?
                                    vm.setProviderDataCenterId(dataCenterId + "-a");
                                    vm.setProviderRegionId(dataCenterId);
                                }
                                if (vm.getProviderDataCenterId() != null) {
                                    vm.setTag("vmFolder", vmFolderName);
                                    vm.setResourcePoolId(rpRef.getValue());
                                    list.add(vm);
                                }
                            }
                        }
                    }
                }
            }
            return list;
        }
        finally {
            APITrace.end();
        }
    }

    @Override
    public void reboot(@Nonnull String vmId) throws CloudException, InternalException {
        APITrace.begin(provider, "Vm.reboot");
        try {
            VirtualMachine vm = getVirtualMachine(vmId);
            if (vm == null) {
                throw new CloudException("Unable to find vm with id "+vmId);
            }

            ManagedObjectReference vmRef = new ManagedObjectReference();
            vmRef.setType("VirtualMachine");
            vmRef.setValue(vmId);

            VsphereConnection vsphereConnection = provider.getServiceInstance();
            VimPortType vimPortType = vsphereConnection.getVimPort();
            try {
                vimPortType.rebootGuest(vmRef);
            } catch (InvalidStateFaultMsg invalidStateFaultMsg) {
                throw new CloudException("InvalidStateFaultMsg when rebooting vm", invalidStateFaultMsg);
            } catch (RuntimeFaultFaultMsg runtimeFaultFaultMsg) {
                throw new CloudException("RuntimeFaultFaultMsg when rebooting vm", runtimeFaultFaultMsg);
            } catch (TaskInProgressFaultMsg taskInProgressFaultMsg) {
                throw new CloudException("TaskInProgressFaultMsg when rebooting vm", taskInProgressFaultMsg);
            } catch (ToolsUnavailableFaultMsg toolsUnavailableFaultMsg) {
                throw new CloudException("ToolsUnavailableFaultMsg when rebooting vm", toolsUnavailableFaultMsg);
            }

        }
        finally {
            APITrace.end();
        }
    }

    @Override
    public void resume(@Nonnull String vmId) throws CloudException, InternalException {
        APITrace.begin(provider, "Vm.resume");
        try {
            VirtualMachine vm = getVirtualMachine(vmId);
            if (vm == null) {
                throw new CloudException("Unable to find vm with id "+vmId);
            }

            ManagedObjectReference vmRef = new ManagedObjectReference();
            vmRef.setType("VirtualMachine");
            vmRef.setValue(vmId);

            ManagedObjectReference hostRef = new ManagedObjectReference();
            vmRef.setType("HostSystem");
            vmRef.setValue(vm.getAffinityGroupId());

            VsphereConnection vsphereConnection = provider.getServiceInstance();
            VimPortType vimPortType = vsphereConnection.getVimPort();
            try {
                vimPortType.powerOnVMTask(vmRef, hostRef);
            } catch (FileFaultFaultMsg fileFaultFaultMsg) {
                throw new CloudException("FileFaultFaultMsg when resuming vm", fileFaultFaultMsg);
            } catch (InsufficientResourcesFaultFaultMsg insufficientResourcesFaultFaultMsg) {
                throw new CloudException("InsufficientResourcesFaultFaultMsg when resuming vm", insufficientResourcesFaultFaultMsg);
            } catch (InvalidStateFaultMsg invalidStateFaultMsg) {
                throw new CloudException("InvalidStateFaultMsg when resuming vm", invalidStateFaultMsg);
            } catch (RuntimeFaultFaultMsg runtimeFaultFaultMsg) {
                throw new CloudException("RuntimeFaultFaultMsg when resuming vm", runtimeFaultFaultMsg);
            } catch (TaskInProgressFaultMsg taskInProgressFaultMsg) {
                throw new CloudException("TaskInProgressFaultMsg when resuming vm", taskInProgressFaultMsg);
            } catch (VmConfigFaultFaultMsg vmConfigFaultFaultMsg) {
                throw new CloudException("VmConfigFaultFaultMsg when resuming vm", vmConfigFaultFaultMsg);
            }

        }
        finally {
            APITrace.end();
        }
    }

    @Override
    public void start(@Nonnull String vmId) throws InternalException, CloudException {
        APITrace.begin(provider, "Vm.start");
        try {
            VirtualMachine vm = getVirtualMachine(vmId);
            if (vm == null) {
                throw new CloudException("Unable to find vm with id "+vmId);
            }
            ManagedObjectReference vmRef = new ManagedObjectReference();
            vmRef.setValue(vmId);
            vmRef.setType("VirtualMachine");

            String hostId = vm.getAffinityGroupId();
            if (hostId != null) {
                ManagedObjectReference hostRef = new ManagedObjectReference();
                hostRef.setType("HostSystem");
                hostRef.setValue(hostId);

                VsphereConnection vsphereConnection = provider.getServiceInstance();
                VimPortType vimPortType = vsphereConnection.getVimPort();

                try {
                    vimPortType.powerOnVMTask(vmRef, hostRef);
                } catch (FileFaultFaultMsg fileFaultFaultMsg) {
                    throw new CloudException("FileFaultFaultMsg when starting vm", fileFaultFaultMsg);
                } catch (InsufficientResourcesFaultFaultMsg insufficientResourcesFaultFaultMsg) {
                    throw new CloudException("InsufficientResourcesFaultFaultMsg when starting vm", insufficientResourcesFaultFaultMsg);
                } catch (InvalidStateFaultMsg invalidStateFaultMsg) {
                    throw new CloudException("InvalidStateFaultMsg when starting vm", invalidStateFaultMsg);
                } catch (RuntimeFaultFaultMsg runtimeFaultFaultMsg) {
                    throw new CloudException("RuntimeFaultFaultMsg when starting vm", runtimeFaultFaultMsg);
                } catch (TaskInProgressFaultMsg taskInProgressFaultMsg) {
                    throw new CloudException("TaskInProgressFaultMsg when starting vm", taskInProgressFaultMsg);
                } catch (VmConfigFaultFaultMsg vmConfigFaultFaultMsg) {
                    throw new CloudException("VmConfigFaultFaultMsg when starting vm", vmConfigFaultFaultMsg);
                }
            }
        }
        finally {
            APITrace.end();
        }
    }

    @Override
    public void stop(@Nonnull String vmId, boolean force) throws InternalException, CloudException {
        APITrace.begin(provider, "Vm.stop");
        try {
            VirtualMachine vm = getVirtualMachine(vmId);
            if (vm == null) {
                throw new CloudException("Unable to find vm with id "+vmId);
            }
            ManagedObjectReference vmRef = new ManagedObjectReference();
            vmRef.setValue(vmId);
            vmRef.setType("VirtualMachine");

            VsphereConnection vsphereConnection = provider.getServiceInstance();
            VimPortType vimPortType = vsphereConnection.getVimPort();
            try {
                if (force) {
                    vimPortType.powerOffVMTask(vmRef);
                }
                else {
                    vimPortType.shutdownGuest(vmRef);
                }
            } catch (InvalidStateFaultMsg invalidStateFaultMsg) {
                throw new CloudException("InvalidStateFaultMsg when stopping vm", invalidStateFaultMsg);
            } catch (RuntimeFaultFaultMsg runtimeFaultFaultMsg) {
                throw new CloudException("RuntimeFaultFaultMsg when stopping vm", runtimeFaultFaultMsg);
            } catch (TaskInProgressFaultMsg taskInProgressFaultMsg) {
                throw new CloudException("TaskInProgressFaultMsg when stopping vm", taskInProgressFaultMsg);
            } catch (ToolsUnavailableFaultMsg toolsUnavailableFaultMsg) {
                throw new CloudException("ToolsUnavailableFaultMsg when stopping vm", toolsUnavailableFaultMsg);
            }
        }
        finally {
            APITrace.end();
        }
    }

    @Override
    public void suspend(@Nonnull String vmId) throws CloudException, InternalException {
        APITrace.begin(provider, "Vm.suspend");
        try {
            VirtualMachine vm = getVirtualMachine(vmId);
            if (vm == null) {
                throw new CloudException("Unable to find vm with id "+vmId);
            }

            ManagedObjectReference vmRef = new ManagedObjectReference();
            vmRef.setType("VirtualMachine");
            vmRef.setValue(vmId);

            VsphereConnection vsphereConnection = provider.getServiceInstance();
            VimPortType vimPortType = vsphereConnection.getVimPort();
            try {
                vimPortType.suspendVMTask(vmRef);
            } catch (InvalidStateFaultMsg invalidStateFaultMsg) {
                throw new CloudException("InvalidStateFaultMsg when suspending vm", invalidStateFaultMsg);
            } catch (RuntimeFaultFaultMsg runtimeFaultFaultMsg) {
                throw new CloudException("RuntimeFaultFaultMsg when suspending vm", runtimeFaultFaultMsg);
            } catch (TaskInProgressFaultMsg taskInProgressFaultMsg) {
                throw new CloudException("TaskInProgressFaultMsg when suspending vm", taskInProgressFaultMsg);
            }
        }
        finally {
            APITrace.end();
        }
    }

    private transient volatile VmCapabilities capabilities;

    @Nonnull
    @Override
    public VirtualMachineCapabilities getCapabilities() throws InternalException, CloudException {
        if( capabilities == null ) {
            capabilities = new VmCapabilities(getProvider());
        }
        return capabilities;
    }

    @Override
    public boolean isSubscribed() throws CloudException, InternalException {
        return true;
    }

    @Nonnull
    @Override
    public VirtualMachine launch(@Nonnull VMLaunchOptions withLaunchOptions) throws CloudException, InternalException {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public void terminate(@Nonnull String vmId, String explanation) throws InternalException, CloudException {
        APITrace.begin(provider, "Vm.suspend");
        try {
            VirtualMachine vm = getVirtualMachine(vmId);
            if (vm == null) {
                throw new CloudException("Unable to find vm with id "+vmId);
            }

            ManagedObjectReference vmRef = new ManagedObjectReference();
            vmRef.setType("VirtualMachine");
            vmRef.setValue(vmId);

            VsphereConnection vsphereConnection = provider.getServiceInstance();
            VimPortType vimPortType = vsphereConnection.getVimPort();

            try {
                if (!vm.getCurrentState().equals(VmState.STOPPED)) {
                    ManagedObjectReference taskMor = vimPortType.powerOffVMTask(vmRef);
                    VsphereMethod method = new VsphereMethod(provider);
                    TimePeriod interval = new TimePeriod<Second>(15, TimePeriod.SECOND);
                    if (!method.getOperationComplete(taskMor, interval, 4)) {
                        throw new CloudException("Error stopping vm prior to termination: "+method.getTaskError().getVal());
                    }
                }
                vimPortType.destroyTask(vmRef);
            } catch (InvalidStateFaultMsg invalidStateFaultMsg) {
                throw new CloudException("InvalidStateFaultMsg when terminating vm", invalidStateFaultMsg);
            } catch (RuntimeFaultFaultMsg runtimeFaultFaultMsg) {
                throw new CloudException("RuntimeFaultFaultMsg when terminating vm", runtimeFaultFaultMsg);
            } catch (TaskInProgressFaultMsg taskInProgressFaultMsg) {
                throw new CloudException("TaskInProgressFaultMsg when terminating vm", taskInProgressFaultMsg);
            } catch (VimFaultFaultMsg vimFaultFaultMsg) {
                throw new CloudException("VimFaultFaultMsg when terminating vm", vimFaultFaultMsg);
            }
        }
        finally {
            APITrace.end();
        }
        
    }

    public ManagedObjectReference reconfigVMTask(ManagedObjectReference vmRef, VirtualMachineConfigSpec spec) throws CloudException, InternalException {
        VsphereConnection vsphereConnection = provider.getServiceInstance();
        VimPortType vimPort = vsphereConnection.getVimPort();
        try {
            return vimPort.reconfigVMTask(vmRef, spec);
        } catch (ConcurrentAccessFaultMsg concurrentAccessFaultMsg) {
            throw new CloudException("ConcurrentAccessFaultMsg when altering vm", concurrentAccessFaultMsg);
        } catch (DuplicateNameFaultMsg duplicateNameFaultMsg) {
            throw new CloudException("DuplicateNameFaultMsg when altering vm", duplicateNameFaultMsg);
        } catch (FileFaultFaultMsg fileFaultFaultMsg) {
            throw new CloudException("FileFaultFaultMsg when altering vm", fileFaultFaultMsg);
        } catch (InsufficientResourcesFaultFaultMsg insufficientResourcesFaultFaultMsg) {
            throw new CloudException("InsufficientResourcesFaultFaultMsg when altering vm", insufficientResourcesFaultFaultMsg);
        } catch (InvalidDatastoreFaultMsg invalidDatastoreFaultMsg) {
            throw new CloudException("InvalidDatastoreFaultMsg when altering vm", invalidDatastoreFaultMsg);
        } catch (InvalidNameFaultMsg invalidNameFaultMsg) {
            throw new CloudException("InvalidNameFaultMsg when altering vm", invalidNameFaultMsg);
        } catch (InvalidStateFaultMsg invalidStateFaultMsg) {
            throw new CloudException("InvalidStateFaultMsg when altering vm", invalidStateFaultMsg);
        } catch (RuntimeFaultFaultMsg runtimeFaultFaultMsg) {
            throw new CloudException("RuntimeFaultFaultMsg when altering vm", runtimeFaultFaultMsg);
        } catch (TaskInProgressFaultMsg taskInProgressFaultMsg) {
            throw new CloudException("TaskInProgressFaultMsg when altering vm", taskInProgressFaultMsg);
        } catch (VmConfigFaultFaultMsg vmConfigFaultFaultMsg) {
            throw new CloudException("VmConfigFaultFaultMsg when altering vm", vmConfigFaultFaultMsg);
        }
    }

    private @Nullable VirtualMachine toVirtualMachine(String vmId, VirtualMachineConfigInfo vmInfo, GuestInfo guest, VirtualMachineRuntimeInfo runtime) throws InternalException, CloudException {
        if( vmInfo == null ) {
            return null;
        }
        Map<String, String> properties = new HashMap<String, String>();
        List<VirtualMachineConfigInfoDatastoreUrlPair> datastoreUrlList = vmInfo.getDatastoreUrl();
        for( int i = 0; i < datastoreUrlList.size(); i++ ) {
            properties.put("datastore" + i, datastoreUrlList.get(i).getName());
        }

        VirtualMachineGuestOsIdentifier os = VirtualMachineGuestOsIdentifier.fromValue(vmInfo.getGuestId());
        VirtualMachine server = new VirtualMachine();

        server.setName(vmInfo.getName());
        server.setPlatform(Platform.guess(vmInfo.getGuestFullName()));
        server.setProviderVirtualMachineId(vmId);
        server.setPersistent(true);
        server.setImagable(true);
        server.setClonable(true);
        server.setArchitecture(getArchitecture(os));
        server.setDescription(vmInfo.getGuestFullName());
        server.setProductId(getProduct(vmInfo.getHardware()).getProviderProductId());
        server.setAffinityGroupId(runtime.getHost().getValue());
        String imageId = vmInfo.getAnnotation();

        if( imageId != null && imageId.length() > 0 && !imageId.contains(" ") ) {
            server.setProviderMachineImageId(imageId);
        }
        else {
            server.setProviderMachineImageId(getContext().getAccountNumber() + "-unknown");
        }

        if ( vmInfo.getHardware().getDevice() != null && vmInfo.getHardware().getDevice().size() > 0 ) {
            List<VirtualDevice> virtualDevices = vmInfo.getHardware().getDevice();
            for(VirtualDevice virtualDevice : virtualDevices) {
                if( virtualDevice instanceof VirtualEthernetCard ) {
                    VirtualEthernetCard veCard = ( VirtualEthernetCard ) virtualDevice;
                    if( veCard.getBacking() instanceof VirtualEthernetCardNetworkBackingInfo ) {
                        VirtualEthernetCardNetworkBackingInfo nicBacking = (VirtualEthernetCardNetworkBackingInfo) veCard.getBacking();
                        String net = nicBacking.getNetwork().getValue();
                        if ( net != null ) {
                            if( server.getProviderVlanId() == null ) {
                                server.setProviderVlanId(net);
                            }
                        }
                    }
                    else if ( veCard.getBacking() instanceof VirtualEthernetCardDistributedVirtualPortBackingInfo ) {
                        VirtualEthernetCardDistributedVirtualPortBackingInfo nicBacking = (VirtualEthernetCardDistributedVirtualPortBackingInfo) veCard.getBacking();
                        String net = nicBacking.getPort().getPortgroupKey();
                        if ( net != null ) {
                            if (server.getProviderVlanId() == null ) {
                                server.setProviderVlanId(net);
                            }
                        }
                    }
                }
            }
        }

        if( guest != null ) {
            if( guest.getHostName() != null ) {
                server.setPrivateDnsAddress(guest.getHostName());
            }
            if( guest.getIpAddress() != null ) {
                server.setProviderAssignedIpAddressId(guest.getIpAddress());
            }
            List<GuestNicInfo> nicInfoArray = guest.getNet();
            if( nicInfoArray != null && nicInfoArray.size() > 0 ) {
                List<RawAddress> pubIps = new ArrayList<RawAddress>();
                List<RawAddress> privIps = new ArrayList<RawAddress>();
                for( GuestNicInfo nicInfo : nicInfoArray ) {
                    List<String> ipAddresses = nicInfo.getIpAddress();
                    if( ipAddresses != null ) {
                        for( String ip : ipAddresses ) {
                            if( ip != null ) {
                                if( isPublicIpAddress(ip) ) {
                                    pubIps.add(new RawAddress(ip));
                                }
                                else {
                                    privIps.add(new RawAddress(ip));
                                }
                            }
                        }

                    }
                }
                if( privIps.size() > 0 ) {
                    RawAddress[] rawPriv = privIps.toArray(new RawAddress[privIps.size()]);
                    server.setPrivateAddresses(rawPriv);
                }
                if( pubIps.size() > 0 ) {
                    RawAddress[] rawPub = pubIps.toArray(new RawAddress[pubIps.size()]);
                    server.setPublicAddresses(rawPub);
                }
            }
        }

        if( runtime != null ) {
            VirtualMachinePowerState state = runtime.getPowerState();

            if( server.getCurrentState() == null ) {
                switch( state ) {
                    case SUSPENDED:
                        server.setCurrentState(VmState.SUSPENDED);
                        break;
                    case POWERED_OFF:
                        server.setCurrentState(VmState.STOPPED);
                        break;
                    case POWERED_ON:
                        server.setCurrentState(VmState.RUNNING);
                        server.setRebootable(true);
                        break;
                }
            }
            XMLGregorianCalendar suspend = runtime.getSuspendTime();
            XMLGregorianCalendar time = runtime.getBootTime();

            if( suspend == null || suspend.toGregorianCalendar().getTimeInMillis() < 1L ) {
                server.setLastPauseTimestamp(-1L);
            }
            else {
                server.setLastPauseTimestamp(suspend.toGregorianCalendar().getTimeInMillis());
                server.setCreationTimestamp(server.getLastPauseTimestamp());
            }
            if( time == null || time.toGregorianCalendar().getTimeInMillis() < 1L ) {
                server.setLastBootTimestamp(0L);
            }
            else {
                server.setLastBootTimestamp(time.toGregorianCalendar().getTimeInMillis());
                server.setCreationTimestamp(server.getLastBootTimestamp());
            }
        }
        server.setProviderOwnerId(getContext().getAccountNumber());
        server.setTags(properties);
        return server;
    }

    @Nonnull
    public List<ResourcePool> getAllResourcePoolsIncludingRoot() throws InternalException, CloudException {
        try {
            List<ResourcePool> resourcePools = new ArrayList<ResourcePool>();

            List<SelectionSpec> selectionSpecsArr = getResourcePoolSelectionSpec();
            List<PropertySpec> pSpecs = getResourcePoolPropertySpec();

            RetrieveResult listobcont = retrieveObjectList(provider, "hostFolder", selectionSpecsArr, pSpecs);

            if (listobcont != null) {
                for (ObjectContent oc : listobcont.getObjects()) {
                    ManagedObjectReference rpRef = oc.getObj();
                    String rpId = rpRef.getValue();
                    String owner = null;
                    List<DynamicProperty> dps = oc.getPropSet();
                    if (dps != null) {
                        for (DynamicProperty dp : dps) {
                            if (dp.getName().equals("owner")) {
                                ManagedObjectReference clusterRef = (ManagedObjectReference) dp.getVal();
                                owner = clusterRef.getValue();
                            }
                        }
                    }
                    if (owner != null) {
                        ResourcePool resourcePool = new ResourcePool();
                        resourcePool.setDataCenterId(owner);
                        resourcePool.setProvideResourcePoolId(rpId);
                        resourcePools.add(resourcePool);
                    }
                }
            }
            return resourcePools;
        }
        finally {
            APITrace.end();
        }
    }

    private @Nullable String getHost(@Nonnull String dc, @Nonnull VirtualMachineRuntimeInfo vmRuntimeInfo) throws InternalException, CloudException {
        APITrace.begin(getProvider(), "getHostForVM");
        try {
            ManagedObjectReference vmHost = vmRuntimeInfo.getHost();

            HostSupport affinityGroupSupport = getProvider().getComputeServices().getAffinityGroupSupport();
            Iterable<AffinityGroup> hostSystems = affinityGroupSupport.list(AffinityGroupFilterOptions.getInstance().withDataCenterId(dc));
            for( AffinityGroup host : hostSystems ) {
                if( vmHost.getValue().equals(host.getAffinityGroupId()) ) {
                    return host.getAffinityGroupName();
                }
            }
            return null;
        }
        finally {
            APITrace.end();
        }
    }

    @Nonnull Architecture getArchitecture(@Nonnull VirtualMachineGuestOsIdentifier os) {
        if( os.name().contains("64") ) {
            return Architecture.I64;
        }
        else {
            return Architecture.I32;
        }
    }

    private boolean isPublicIpAddress(@Nonnull String ipv4Address) {
        if( ipv4Address.startsWith("10.") || ipv4Address.startsWith("192.168") || ipv4Address.startsWith("169.254") ) {
            return false;
        }
        else if( ipv4Address.startsWith("172.") ) {
            String[] parts = ipv4Address.split("\\.");

            if( parts.length != 4 ) {
                return true;
            }
            int x = Integer.parseInt(parts[1]);

            if( x >= 16 && x <= 31 ) {
                return false;
            }
        }
        return true;
    }
}
