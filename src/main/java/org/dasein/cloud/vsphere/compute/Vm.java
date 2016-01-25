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

package org.dasein.cloud.vsphere.compute;

import com.vmware.vim25.*;
import org.apache.log4j.Logger;
import org.dasein.cloud.*;
import org.dasein.cloud.compute.*;
import org.dasein.cloud.dc.DataCenter;
import org.dasein.cloud.dc.Folder;
import org.dasein.cloud.dc.ResourcePool;
import org.dasein.cloud.network.RawAddress;
import org.dasein.cloud.network.VLAN;
import org.dasein.cloud.util.APITrace;
import org.dasein.cloud.util.Cache;
import org.dasein.cloud.util.CacheLevel;
import org.dasein.cloud.vsphere.*;
import org.dasein.cloud.vsphere.capabilities.VmCapabilities;
import org.dasein.cloud.vsphere.network.VSphereNetwork;
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
    private List<PropertySpec> virtualMachinePSpec;
    private List<PropertySpec> launchVirtualMachinePSpec;
    private List<PropertySpec> rpPSpecs;
    private List<SelectionSpec> rpSSpecs;
    private DataCenters dc;

    public Vm(Vsphere provider) {
        super(provider);
        dc = provider.getDataCenterServices();
    }

    public RetrieveResult retrieveObjectList(Vsphere provider, @Nonnull String baseFolder, @Nullable List<SelectionSpec> selectionSpecsArr, @Nonnull List<PropertySpec> pSpecs) throws InternalException, CloudException {
        VsphereInventoryNavigation nav = new VsphereInventoryNavigation();
        return nav.retrieveObjectList(provider, baseFolder, selectionSpecsArr, pSpecs);
    }

    public List<PropertySpec> getVirtualMachinePSpec() {
        virtualMachinePSpec = VsphereTraversalSpec.createPropertySpec(virtualMachinePSpec, "VirtualMachine", false, "runtime", "config", "parent", "resourcePool", "guest", "datastore");
        return virtualMachinePSpec;
    }

    public List<PropertySpec> getLaunchVirtualMachinePSpec() {
        launchVirtualMachinePSpec = VsphereTraversalSpec.createPropertySpec(launchVirtualMachinePSpec, "VirtualMachine", false, "config", "customValue");
        return launchVirtualMachinePSpec;
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
        rpPSpecs = VsphereTraversalSpec.createPropertySpec(rpPSpecs, "ResourcePool", false, "owner", "parent");
        return rpPSpecs;
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
    public boolean isSubscribed() {
        return true;
    }

    @Nonnull
    @Override
    public VirtualMachine alterVirtualMachineSize(@Nonnull String virtualMachineId, @Nullable String cpuCount, @Nullable String ramInMB) throws InternalException, CloudException {
        APITrace.begin(getProvider(), "Vm.alterVirtualMachineSize");
        try {
            VirtualMachine vm = getVirtualMachine(virtualMachineId);
            if( vm == null ) {
                throw new ResourceNotFoundException("Vm", virtualMachineId);
            }

            if( cpuCount == null && ramInMB == null ) {
                //todo
                //should we have a new exception for errors caused by user/client provided data?
                throw new InternalException("No cpu count or ram change provided");
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
            VsphereMethod method = new VsphereMethod(getProvider());
            TimePeriod interval = new TimePeriod<Second>(30, TimePeriod.SECOND);

            if( taskMor != null && method.getOperationComplete(taskMor, interval, 10) ) {
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
                //todo is ResourceNotFoundException appropriate here?
                lastError = new ResourceNotFoundException("Updated server", virtualMachineId);
            }
            else {
                lastError = new GeneralCloudException("Failed to update VM: " + method.getTaskError().getVal(), CloudErrorType.GENERAL);
            }
            throw lastError;
        }
        finally {
            APITrace.end();
        }
    }

    @Nonnull
    @Override
    public VirtualMachine clone(@Nonnull String vmId, @Nonnull String intoDcId, @Nonnull String name, @Nonnull String description, boolean powerOn, @Nullable String... firewallIds) throws InternalException, CloudException {
        APITrace.begin(getProvider(), "Vm.clone");
        try {
            VirtualMachine vm = getVirtualMachine(vmId);
            if (vm == null) {
                throw new ResourceNotFoundException("vm", vmId);
            }

            ManagedObjectReference vmRef = new ManagedObjectReference();
            vmRef.setType("VirtualMachine");
            vmRef.setValue(vmId);

            ManagedObjectReference rpRef = null;
            Collection<ResourcePool> rpList = dc.listResourcePools(intoDcId);
            for (ResourcePool rp : rpList) {
                if (rp.isAvailable()) {
                    rpRef = new ManagedObjectReference();
                    rpRef.setValue(rp.getProvideResourcePoolId());
                    rpRef.setType("ResourcePool");
                    break;
                }
            }
            if (rpRef == null) {
                rpList = getResourcePools(true);
                for (ResourcePool rp : rpList) {
                    if (rp.getDataCenterId().equals(intoDcId)) {
                        rpRef = new ManagedObjectReference();
                        rpRef.setValue(rp.getProvideResourcePoolId());
                        rpRef.setType("ResourcePool");
                        break;
                    }
                }
            }

            ManagedObjectReference vmFolder = new ManagedObjectReference();
            vmFolder.setType("Folder");
            vmFolder.setValue(vm.getTag("vmFolderId").toString());

            VirtualMachineCloneSpec spec = new VirtualMachineCloneSpec();
            VirtualMachineRelocateSpec location = new VirtualMachineRelocateSpec();

            ManagedObjectReference host = null;
            Iterable<AffinityGroup> agList = getProvider().getComputeServices().getAffinityGroupSupport().list(AffinityGroupFilterOptions.getInstance().withDataCenterId(intoDcId));
            for (AffinityGroup ag : agList) {
                if (ag.getTag("status").toString().equalsIgnoreCase("green")) {
                    host = new ManagedObjectReference();
                    host.setType("HostSystem");
                    host.setValue(ag.getAffinityGroupId());
                    break;
                }
            }

            if (host != null && rpRef != null) {
                location.setHost(host);
                location.setPool(rpRef);
                spec.setLocation(location);
                spec.setPowerOn(powerOn);
                spec.setTemplate(false);

                ManagedObjectReference taskMor = null;
                taskMor = cloneVmTask(vmRef, vmFolder, name, spec);

                VsphereMethod method = new VsphereMethod(getProvider());
                TimePeriod interval = new TimePeriod<Second>(30, TimePeriod.SECOND);
                if (method.getOperationComplete(taskMor, interval, 20)) {
                    PropertyChange pChange = method.getTaskResult();
                    ManagedObjectReference newVmRef = (ManagedObjectReference) pChange.getVal();

                    return getVirtualMachine(newVmRef.getValue());
                }
                throw new GeneralCloudException("Failed to create VM: " + method.getTaskError().getVal(), CloudErrorType.GENERAL);
            }
            throw new ResourceNotFoundException("Unable to create vm because available Host and/or resource pool for datacenter", intoDcId);
        }
        finally {
            APITrace.end();
        }
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
            product.setDescription("Custom product - " + parts[0] + " CPU, " + parts[1] + "MB RAM");
            product.setName(parts[0] + " CPU/" + parts[1] + "MB RAM");
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
            product.setDescription("Custom product - " + cpu + " CPU, " + ram + "MB RAM");
            product.setName(cpu + " CPU/" + ram + "MB RAM");
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

    @Nonnull
    @Override
    public Iterable<VirtualMachineProduct> listAllProducts() throws InternalException, CloudException {
        return listProducts("ignore", VirtualMachineProductFilterOptions.getInstance());
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
    public VirtualMachine launch(@Nonnull VMLaunchOptions options) throws CloudException, InternalException {
        APITrace.begin(getProvider(), "Vm.launch");
        try {
            ProviderContext ctx = getProvider().getContext();

            if (ctx.getRegionId() == null) {
                throw new InternalException("Unable to launch vm as no region was set for this request");
            }

            List<PropertySpec> pSpecs = getLaunchVirtualMachinePSpec();
            RetrieveResult listobcont = retrieveObjectList(getProvider(), "vmFolder", null, pSpecs);

            boolean foundTemplate = false;
            VirtualMachineConfigInfo templateConfigInfo = null;
            List<CustomFieldValue> templateCustomValue = null;
            ManagedObjectReference templateRef = null;
            if (listobcont != null) {
                for (ObjectContent oc : listobcont.getObjects()) {
                    templateRef = oc.getObj();
                    if (templateRef.getValue().equals(options.getMachineImageId())) {
                        foundTemplate = true;
                        List<DynamicProperty> dps = oc.getPropSet();
                        for (DynamicProperty dp : dps) {
                            switch (dp.getName()) {
                                case "config":
                                    templateConfigInfo = (VirtualMachineConfigInfo) dp.getVal();
                                    break;
                                case "customValue":
                                    ArrayOfCustomFieldValue ar = (ArrayOfCustomFieldValue) dp.getVal();
                                    templateCustomValue = ar.getCustomFieldValue();
                                    break;
                            }
                        }
                        break;
                    }
                }
            }

            if (!foundTemplate) {
                throw new ResourceNotFoundException("Template", options.getMachineImageId());
            }
            if (templateConfigInfo != null) {
                int apiMajorVersion = getProvider().getApiMajorVersion();
                String hostName = validateName(options.getHostName());
                String dataCenterId = options.getDataCenterId();
                if (dataCenterId == null) {
                    String rid = ctx.getRegionId();
                    dataCenterId = getProvider().getDataCenterServices().listDataCenters(rid).iterator().next().getProviderDataCenterId();
                }

                String resourceProductStr = options.getStandardProductId();
                String[] items = resourceProductStr.split(":");
                if (items.length == 3) {
                    options.withResourcePoolId(items[0]);
                }
                ManagedObjectReference rpRef = new ManagedObjectReference();
                rpRef.setType("ResourcePool");
                if (options.getResourcePoolId() == null) {
                    Collection<ResourcePool> pools = getResourcePools(true);
                    for (ResourcePool pool : pools) {
                        if (pool.getDataCenterId().equals(dataCenterId)) {
                            rpRef.setValue(pool.getProvideResourcePoolId());
                            break;
                        }
                    }
                }
                else {
                    rpRef.setValue(options.getResourcePoolId());
                }

                ManagedObjectReference vmFolder = new ManagedObjectReference();
                vmFolder.setType("Folder");
                if (options.getVmFolderId() != null) {
                    vmFolder.setValue(options.getVmFolderId());
                }
                else {
                    //find the root vm folder
                    Collection<Folder> folders = dc.listVMFolders();
                    for (Folder folder : folders) {
                        if (folder.getParent() == null) {
                            vmFolder.setValue(folder.getId());
                            break;
                        }
                    }
                }

                VirtualMachineRelocateSpec location = new VirtualMachineRelocateSpec();
                if (options.getAffinityGroupId() != null) {
                    ManagedObjectReference hostRef = new ManagedObjectReference();
                    hostRef.setValue(options.getAffinityGroupId());
                    hostRef.setType("HostSystem");
                    location.setHost(hostRef);
                }
                if (options.getStoragePoolId() != null) {
                    String locationId = options.getStoragePoolId();
                    ManagedObjectReference dsRef = new ManagedObjectReference();
                    dsRef.setType("Datastore");
                    dsRef.setValue(locationId);
                    location.setDatastore(dsRef);
                }
                location.setPool(rpRef);

                VirtualMachineConfigSpec config = new VirtualMachineConfigSpec();
                String[] vmInfo = options.getStandardProductId().split(":");
                int cpuCount;
                long memory;
                if (vmInfo.length == 2) {
                    cpuCount = Integer.parseInt(vmInfo[0]);
                    memory = Long.parseLong(vmInfo[1]);
                } else {
                    cpuCount = Integer.parseInt(vmInfo[1]);
                    memory = Long.parseLong(vmInfo[2]);
                }

                config.setName(hostName);
                config.setAnnotation(options.getMachineImageId());
                config.setMemoryMB(memory);
                config.setNumCPUs(cpuCount);
                config.setNumCoresPerSocket(cpuCount);

                // record all networks we will end up with so that we can configure NICs correctly
                List<String> resultingNetworks = new ArrayList<String>();
                //networking section
                //borrowed heavily from https://github.com/jedi4ever/jvspherecontrol
                String vlan = options.getVlanId();
                int count = 0;
                if (vlan != null) {

                    // we don't need to do network config if the selected network
                    // is part of the template config anyway
                    VSphereNetwork vlanSupport = getProvider().getNetworkServices().getVlanSupport();

                    Iterable<VLAN> accessibleNetworks = vlanSupport.listVlans();
                    boolean addNetwork = true;
                    List<VirtualDeviceConfigSpec> machineSpecs = new ArrayList<VirtualDeviceConfigSpec>();
                    List<VirtualDevice> virtualDevices = templateConfigInfo.getHardware().getDevice();
                    VLAN targetVlan = null;
                    boolean isFirstNic = true;
                    for (VirtualDevice virtualDevice : virtualDevices) {
                        if (virtualDevice instanceof VirtualEthernetCard) {
                            VirtualEthernetCard veCard = (VirtualEthernetCard) virtualDevice;
                            if (veCard.getBacking() instanceof VirtualEthernetCardNetworkBackingInfo) {
                                boolean nicDeleted = false;
                                VirtualEthernetCardNetworkBackingInfo nicBacking = (VirtualEthernetCardNetworkBackingInfo) veCard.getBacking();
                                if (vlan.equals(nicBacking.getNetwork().getValue()) && isFirstNic) {
                                    addNetwork = false;
                                } else {
                                    for (VLAN accessibleNetwork : accessibleNetworks) {
                                        if (accessibleNetwork.getProviderVlanId().equals(nicBacking.getNetwork().getValue())) {
                                            VirtualDeviceConfigSpec nicSpec = new VirtualDeviceConfigSpec();
                                            nicSpec.setOperation(VirtualDeviceConfigSpecOperation.REMOVE);

                                            nicSpec.setDevice(veCard);
                                            machineSpecs.add(nicSpec);
                                            nicDeleted = true;

                                            if (accessibleNetwork.getProviderVlanId().equals(vlan)) {
                                                targetVlan = accessibleNetwork;
                                            }
                                        } else if (accessibleNetwork.getProviderVlanId().equals(vlan)) {
                                            targetVlan = accessibleNetwork;
                                        }
                                        if (nicDeleted && targetVlan != null) {
                                            break;
                                        }
                                    }
                                }
                                if (!nicDeleted) {
                                    resultingNetworks.add(nicBacking.getNetwork().getValue());
                                }
                            } else if (veCard.getBacking() instanceof VirtualEthernetCardDistributedVirtualPortBackingInfo) {
                                boolean nicDeleted = false;
                                VirtualEthernetCardDistributedVirtualPortBackingInfo nicBacking = (VirtualEthernetCardDistributedVirtualPortBackingInfo) veCard.getBacking();
                                if (vlan.equals(nicBacking.getPort().getPortgroupKey()) && isFirstNic) {
                                    addNetwork = false;
                                } else {
                                    for (VLAN accessibleNetwork : accessibleNetworks) {
                                        if (accessibleNetwork.getProviderVlanId().equals(nicBacking.getPort().getPortgroupKey())) {
                                            VirtualDeviceConfigSpec nicSpec = new VirtualDeviceConfigSpec();
                                            nicSpec.setOperation(VirtualDeviceConfigSpecOperation.REMOVE);

                                            nicSpec.setDevice(veCard);
                                            machineSpecs.add(nicSpec);
                                            nicDeleted = true;

                                            if (accessibleNetwork.getProviderVlanId().equals(vlan)) {
                                                targetVlan = accessibleNetwork;
                                            }
                                        } else if (accessibleNetwork.getProviderVlanId().equals(vlan)) {
                                            targetVlan = accessibleNetwork;
                                        }
                                        if (nicDeleted && targetVlan != null) {
                                            break;
                                        }
                                    }
                                }
                                if (!nicDeleted) {
                                    resultingNetworks.add(nicBacking.getPort().getPortgroupKey());
                                }
                            }
                            isFirstNic = false;
                        }
                    }

                    if (addNetwork && targetVlan != null) {
                        VirtualDeviceConfigSpec nicSpec = new VirtualDeviceConfigSpec();
                        nicSpec.setOperation(VirtualDeviceConfigSpecOperation.ADD);

                        VirtualEthernetCard nic = new VirtualVmxnet3();
                        nic.setConnectable(new VirtualDeviceConnectInfo());
                        nic.getConnectable().setConnected(true);
                        nic.getConnectable().setStartConnected(true);

                        Description info = new Description();
                        info.setLabel(targetVlan.getName());
                        if (targetVlan.getProviderVlanId().startsWith("network")) {
                            info.setSummary("Nic for network " + targetVlan.getName());

                            VirtualEthernetCardNetworkBackingInfo nicBacking = new VirtualEthernetCardNetworkBackingInfo();
                            nicBacking.setDeviceName(targetVlan.getName());

                            nic.setAddressType("generated");
                            nic.setBacking(nicBacking);
                            nic.setKey(0);
                        } else {
                            info.setSummary("Nic for DVS " + targetVlan.getName());

                            VirtualEthernetCardDistributedVirtualPortBackingInfo nicBacking = new VirtualEthernetCardDistributedVirtualPortBackingInfo();
                            DistributedVirtualSwitchPortConnection connection = new DistributedVirtualSwitchPortConnection();
                            connection.setPortgroupKey(targetVlan.getProviderVlanId());
                            connection.setSwitchUuid(targetVlan.getTag("switch.uuid"));
                            nicBacking.setPort(connection);
                            nic.setAddressType("generated");
                            nic.setBacking(nicBacking);
                            nic.setKey(0);
                        }
                        nicSpec.setDevice(nic);

                        machineSpecs.add(nicSpec);
                        resultingNetworks.add(vlan);
                    }
                    if (!machineSpecs.isEmpty()) {
                        if (apiMajorVersion >= 6) {
                            location.getDeviceChange().addAll(machineSpecs);
                        }
                        else {
                            config.getDeviceChange().addAll(machineSpecs);
                        }
                    }
                    // end networking section
                }

                boolean isCustomised = false;
                if (options.getPrivateIp() != null) {
                    isCustomised = true;
                    logger.debug("isCustomised");
                } else {
                    logger.debug("notCustomised");
                }
                CustomizationSpec customizationSpec = new CustomizationSpec();
                if (isCustomised) {
                    String templatePlatform = templateConfigInfo.getGuestFullName();
                    if (templatePlatform == null) {
                        templatePlatform = templateConfigInfo.getName();
                    }
                    Platform platform = Platform.guess(templatePlatform.toLowerCase());
                    if (platform.isLinux()) {

                        CustomizationLinuxPrep lPrep = new CustomizationLinuxPrep();
                        lPrep.setDomain(options.getDnsDomain());
                        lPrep.setHostName(new CustomizationVirtualMachineName());
                        customizationSpec.setIdentity(lPrep);
                    } else if (platform.isWindows()) {
                        CustomizationSysprep sysprep = new CustomizationSysprep();

                        CustomizationGuiUnattended guiCust = new CustomizationGuiUnattended();
                        guiCust.setAutoLogon(false);
                        guiCust.setAutoLogonCount(0);
                        CustomizationPassword password = new CustomizationPassword();
                        password.setPlainText(true);
                        password.setValue(options.getBootstrapPassword());
                        guiCust.setPassword(password);
                        //log.debug("Windows pass for "+hostName+": "+password.getValue());

                        sysprep.setGuiUnattended(guiCust);

                        CustomizationIdentification identification = new CustomizationIdentification();
                        identification.setJoinWorkgroup(options.getWinWorkgroupName());
                        sysprep.setIdentification(identification);

                        CustomizationUserData userData = new CustomizationUserData();
                        userData.setComputerName(new CustomizationVirtualMachineName());
                        userData.setFullName(options.getWinOwnerName());
                        userData.setOrgName(options.getWinOrgName());
                        String serial = options.getWinProductSerialNum();
                        logger.debug("Found win license key: " + serial);
                        logger.debug("Guest os version: " + templateConfigInfo.getGuestFullName());
                        String guestOS = templateConfigInfo.getGuestFullName();
                        if (serial == null || serial.length() <= 0) {
                            logger.warn("Product license key not specified in launch options. Trying to get default.");
                            for (CustomFieldValue value : templateCustomValue) {
                                if (value instanceof CustomFieldStringValue) {
                                    CustomFieldStringValue string = (CustomFieldStringValue) value;
                                    if (string.getValue().contains("2k12r2")) {
                                        guestOS = "Windows Server 2012 R2 Server Standard";
                                        logger.debug("Found custom value specifying " + string.getValue());
                                        break;
                                    }
                                }
                            }
                            serial = getWindowsProductLicenseForOSEdition(guestOS);
                            logger.debug("License key found for guest OS version: " + serial);
                        } else {
                            logger.debug("Using the user provided key: " + serial);
                        }
                        userData.setProductId(serial);
                        sysprep.setUserData(userData);

                        customizationSpec.setIdentity(sysprep);
                    } else {
                        logger.error("Guest customisation could not take place as platform is not linux or windows: " + platform);
                        isCustomised = false;
                    }

                    if (isCustomised) {
                        CustomizationGlobalIPSettings globalIPSettings = new CustomizationGlobalIPSettings();
                        globalIPSettings.getDnsServerList().addAll(Arrays.asList(options.getDnsServerList()));
                        globalIPSettings.getDnsSuffixList().addAll(Arrays.asList(options.getDnsSuffixList()));
                        customizationSpec.setGlobalIPSettings(globalIPSettings);

                        CustomizationAdapterMapping adapterMap = new CustomizationAdapterMapping();
                        CustomizationIPSettings adapter = new CustomizationIPSettings();
                        adapter.setDnsDomain(options.getDnsDomain());
                        adapter.getDnsServerList().addAll(Arrays.asList(options.getDnsServerList()));
                        adapter.getGateway().addAll(Arrays.asList(options.getGatewayList()));
                        CustomizationFixedIp fixedIp = new CustomizationFixedIp();
                        fixedIp.setIpAddress(options.getPrivateIp());
                        adapter.setIp(fixedIp);
                        if (options.getMetaData().containsKey("vSphereNetMaskNothingToSeeHere")) {
                            String netmask = (String) options.getMetaData().get("vSphereNetMaskNothingToSeeHere");
                            adapter.setSubnetMask(netmask);
                            logger.debug("custom subnet mask: " + netmask);
                        } else {
                            adapter.setSubnetMask("255.255.252.0");
                            logger.debug("default subnet mask");
                        }

                        adapterMap.setAdapter(adapter);
                        customizationSpec.getNicSettingMap().addAll(Arrays.asList(adapterMap));
                    }
                }

                VirtualMachineCloneSpec spec = new VirtualMachineCloneSpec();
                spec.setLocation(location);
                spec.setTemplate(false);
                if (isCustomised) {
                    spec.setCustomization(customizationSpec);
                }
                if (apiMajorVersion >= 6) {
                    spec.setPowerOn(false);
                }
                else {
                    spec.setPowerOn(true);
                    spec.setConfig(config);
                }

                CloudException lastError = null;
                ManagedObjectReference taskMor = null;
                taskMor = cloneVmTask(templateRef, vmFolder, hostName, spec);

                VsphereMethod method = new VsphereMethod(getProvider());
                TimePeriod interval = new TimePeriod<Second>(30, TimePeriod.SECOND);
                if (method.getOperationComplete(taskMor, interval, 20)) {
                    PropertyChange pChange = method.getTaskResult();
                    ManagedObjectReference newVmRef = (ManagedObjectReference) pChange.getVal();

                    if (apiMajorVersion >= 6) {
                        //reconfig vm call as of vsphere api v6.0
                        taskMor = reconfigVMTask(newVmRef, config);
                        if (method.getOperationComplete(taskMor, interval, 20)) {
                            try {
                                Thread.sleep(10000L);
                            } catch (InterruptedException ignore) {
                            }

                            start(newVmRef.getValue());
                        }
                        else {
                            throw new GeneralCloudException("Failed to reconfigure VM: " + method.getTaskError().getVal(), CloudErrorType.GENERAL);
                        }
                    }

                    long timeout = System.currentTimeMillis() + (CalendarWrapper.MINUTE * 20L);
                    while (System.currentTimeMillis() < timeout) {
                        try {
                            Thread.sleep(10000L);
                        } catch (InterruptedException ignore) {
                        }

                        for (VirtualMachine s : listVirtualMachines()) {
                            if (s.getProviderVirtualMachineId().equals(newVmRef.getValue()) && s.getCurrentState().equals(VmState.RUNNING)) {
                                if (isCustomised && s.getPlatform().equals(Platform.WINDOWS)) {
                                    s.setRootPassword(options.getBootstrapPassword());
                                }
                                return s;
                            }
                        }
                    }
                    //todo is ResourceNotFoundException appropriate here?
                    lastError = new ResourceNotFoundException("Newly created vm", newVmRef.getValue());
                }
                if (lastError == null) {
                    lastError = new GeneralCloudException("Failed to create VM: " + method.getTaskError().getVal(), CloudErrorType.GENERAL);
                }
                throw lastError;
            }
        }
        finally {
            APITrace.end();
        }
        return null;
    }

    @Nonnull
    @Override
    public Iterable<VirtualMachine> listVirtualMachines() throws InternalException, CloudException {
        APITrace.begin(getProvider(), "Vm.listVirtualMachines");
        try {
            List<VirtualMachine> list = new ArrayList<VirtualMachine>();
            ProviderContext ctx = getProvider().getContext();
            if (ctx.getRegionId() == null) {
                throw new InternalException("Region id is not set");
            }

            List<PropertySpec> pSpecs = getVirtualMachinePSpec();
            RetrieveResult listobcont = retrieveObjectList(getProvider(), "vmFolder", null, pSpecs);

            if (listobcont != null) {
                Iterable<ResourcePool> rps = getResourcePools(false);//return all resourcePools
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
                    List<ManagedObjectReference> datastores = null;
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
                            case "datastore":
                                ArrayOfManagedObjectReference array = (ArrayOfManagedObjectReference) dp.getVal();
                                datastores = array.getManagedObjectReference();
                                break;
                        }
                    }
                    if (!isTemplate) {
                        VirtualMachine vm = toVirtualMachine(vmId, vmInfo, guestInfo, vmRuntimeInfo, datastores);
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
                                    if (vmFolderName != null) {
                                        vm.setTag("vmFolder", vmFolderName);
                                        vm.setTag("vmFolderId", parentRef.getValue());
                                    }
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
        APITrace.begin(getProvider(), "Vm.reboot");
        try {
            VirtualMachine vm = getVirtualMachine(vmId);
            if (vm == null) {
                throw new ResourceNotFoundException("Vm", vmId);
            }
            if (!getCapabilities().canReboot(vm.getCurrentState())) {
                //todo
                //should we have a new exception for errors caused by user/client provided data?
                throw new InternalException("Unable to reboot vm in state "+vm.getCurrentState());
            }

            ManagedObjectReference vmRef = new ManagedObjectReference();
            vmRef.setType("VirtualMachine");
            vmRef.setValue(vmId);

            VsphereConnection vsphereConnection = getProvider().getServiceInstance();
            VimPortType vimPortType = vsphereConnection.getVimPort();
            try {
                vimPortType.rebootGuest(vmRef);
            } catch (InvalidStateFaultMsg invalidStateFaultMsg) {
                throw new InvalidStateException("Vm is in invalid state for reboot: " + invalidStateFaultMsg.getMessage(), invalidStateFaultMsg);
            } catch (RuntimeFaultFaultMsg runtimeFaultFaultMsg) {
                if (runtimeFaultFaultMsg.getFaultInfo() instanceof NoPermission) {
                    throw new AuthenticationException("NoPermission fault when rebooting vm", runtimeFaultFaultMsg).withFaultType(AuthenticationException.AuthenticationFaultType.FORBIDDEN);
                }
                throw new GeneralCloudException("Error when rebooting vm", runtimeFaultFaultMsg, CloudErrorType.GENERAL);
            } catch (TaskInProgressFaultMsg taskInProgressFaultMsg) {
                throw new TaskInProgressException("TaskInProgressFaultMsg when rebooting vm: "+ taskInProgressFaultMsg.getMessage());
            } catch (Exception e) {
                throw new GeneralCloudException("Error when rebooting vm", e, CloudErrorType.GENERAL);
            }
        }
        finally {
            APITrace.end();
        }
    }

    @Override
    public void resume(@Nonnull String vmId) throws CloudException, InternalException {
        APITrace.begin(getProvider(), "Vm.resume");
        try {
            VirtualMachine vm = getVirtualMachine(vmId);
            if (vm == null) {
                throw new ResourceNotFoundException("vm", vmId);
            }

            if (!getCapabilities().canResume(vm.getCurrentState())) {
                //todo
                //should we have a new exception for errors caused by user/client provided data?
                throw new InternalException("Unable to resume vm in state "+vm.getCurrentState());
            }

            ManagedObjectReference vmRef = new ManagedObjectReference();
            vmRef.setType("VirtualMachine");
            vmRef.setValue(vmId);

            ManagedObjectReference hostRef = new ManagedObjectReference();
            hostRef.setType("HostSystem");
            hostRef.setValue(vm.getAffinityGroupId());

            VsphereConnection vsphereConnection = getProvider().getServiceInstance();
            VimPortType vimPortType = vsphereConnection.getVimPort();
            try {
                vimPortType.powerOnVMTask(vmRef, hostRef);
            } catch (InsufficientResourcesFaultFaultMsg insufficientResourcesFaultFaultMsg) {
                throw new GeneralCloudException("InsufficientResourcesFaultFaultMsg when resuming vm", insufficientResourcesFaultFaultMsg, CloudErrorType.CAPACITY);
            } catch (InvalidStateFaultMsg invalidStateFaultMsg) {
                throw new InvalidStateException("Vm is in invalid state for resume: " + invalidStateFaultMsg.getMessage(), invalidStateFaultMsg);
            } catch (RuntimeFaultFaultMsg runtimeFaultFaultMsg) {
                if (runtimeFaultFaultMsg.getFaultInfo() instanceof NoPermission) {
                    throw new AuthenticationException("NoPermission fault when resuming vm", runtimeFaultFaultMsg).withFaultType(AuthenticationException.AuthenticationFaultType.FORBIDDEN);
                }
                throw new GeneralCloudException("Error when resuming vm", runtimeFaultFaultMsg, CloudErrorType.GENERAL);
            } catch (TaskInProgressFaultMsg taskInProgressFaultMsg) {
                throw new TaskInProgressException("TaskInProgressFaultMsg when resuming vm: "+ taskInProgressFaultMsg.getMessage());
            } catch (Exception e) {
                throw new GeneralCloudException("Error when resuming vm", e, CloudErrorType.GENERAL);
            }
        }
        finally {
            APITrace.end();
        }
    }

    @Override
    public void start(@Nonnull String vmId) throws InternalException, CloudException {
        APITrace.begin(getProvider(), "Vm.start");
        try {
            VirtualMachine vm = getVirtualMachine(vmId);
            if (vm == null) {
                throw new ResourceNotFoundException("VM", vmId);
            }

            if (!getCapabilities().canStart(vm.getCurrentState())) {
                //todo
                //should we have a new exception for errors caused by user/client provided data?
                throw new InternalException("Unable to start vm in state "+vm.getCurrentState());
            }

            ManagedObjectReference vmRef = new ManagedObjectReference();
            vmRef.setValue(vmId);
            vmRef.setType("VirtualMachine");

            String hostId = vm.getAffinityGroupId();
            if (hostId != null) {
                ManagedObjectReference hostRef = new ManagedObjectReference();
                hostRef.setType("HostSystem");
                hostRef.setValue(hostId);

                VsphereConnection vsphereConnection = getProvider().getServiceInstance();
                VimPortType vimPortType = vsphereConnection.getVimPort();

                try {
                    vimPortType.powerOnVMTask(vmRef, hostRef);
                } catch (InsufficientResourcesFaultFaultMsg insufficientResourcesFaultFaultMsg) {
                    throw new GeneralCloudException("InsufficientResourcesFaultFaultMsg when starting vm", insufficientResourcesFaultFaultMsg, CloudErrorType.CAPACITY);
                } catch (InvalidStateFaultMsg invalidStateFaultMsg) {
                    throw new InvalidStateException("Vm is in invalid state for start: " + invalidStateFaultMsg.getMessage(), invalidStateFaultMsg);
                } catch (RuntimeFaultFaultMsg runtimeFaultFaultMsg) {
                    if (runtimeFaultFaultMsg.getFaultInfo() instanceof NoPermission) {
                        throw new AuthenticationException("NoPermission fault when starting vm", runtimeFaultFaultMsg).withFaultType(AuthenticationException.AuthenticationFaultType.FORBIDDEN);
                    }
                    throw new GeneralCloudException("Error when starting vm", runtimeFaultFaultMsg, CloudErrorType.GENERAL);
                } catch (TaskInProgressFaultMsg taskInProgressFaultMsg) {
                    throw new TaskInProgressException("TaskInProgressFaultMsg when starting vm: "+ taskInProgressFaultMsg.getMessage());
                } catch (Exception e) {
                    throw new GeneralCloudException("Error when starting vm", e, CloudErrorType.GENERAL);
                }
            }
        }
        finally {
            APITrace.end();
        }
    }

    @Override
    public void stop(@Nonnull String vmId, boolean force) throws InternalException, CloudException {
        APITrace.begin(getProvider(), "Vm.stop");
        try {
            VirtualMachine vm = getVirtualMachine(vmId);
            if (vm == null) {
                throw new ResourceNotFoundException("vm", vmId);
            }

            if (!getCapabilities().canStop(vm.getCurrentState())) {
                //todo
                //should we have a new exception for errors caused by user/client provided data?
                throw new InternalException("Unable to stop vm in state "+vm.getCurrentState());
            }

            ManagedObjectReference vmRef = new ManagedObjectReference();
            vmRef.setValue(vmId);
            vmRef.setType("VirtualMachine");

            VsphereConnection vsphereConnection = getProvider().getServiceInstance();
            VimPortType vimPortType = vsphereConnection.getVimPort();
            try {
                if (force) {
                    vimPortType.powerOffVMTask(vmRef);
                }
                else {
                    vimPortType.shutdownGuest(vmRef);
                }
            } catch (InvalidStateFaultMsg invalidStateFaultMsg) {
                throw new InvalidStateException("Vm is in invalid state for stop: " + invalidStateFaultMsg.getMessage(), invalidStateFaultMsg);
            } catch (RuntimeFaultFaultMsg runtimeFaultFaultMsg) {
                if (runtimeFaultFaultMsg.getFaultInfo() instanceof NoPermission) {
                    throw new AuthenticationException("NoPermission fault when stopping vm", runtimeFaultFaultMsg).withFaultType(AuthenticationException.AuthenticationFaultType.FORBIDDEN);
                }
                throw new GeneralCloudException("Error when stopping vm", runtimeFaultFaultMsg, CloudErrorType.GENERAL);
            } catch (TaskInProgressFaultMsg taskInProgressFaultMsg) {
                throw new TaskInProgressException("TaskInProgressFaultMsg when stopping vm: "+taskInProgressFaultMsg.getMessage());
            } catch (Exception e) {
                throw new GeneralCloudException("Error when stopping vm", e, CloudErrorType.GENERAL);
            }
        }
        finally {
            APITrace.end();
        }
    }

    @Override
    public void suspend(@Nonnull String vmId) throws CloudException, InternalException {
        APITrace.begin(getProvider(), "Vm.suspend");
        try {
            VirtualMachine vm = getVirtualMachine(vmId);
            if (vm == null) {
                throw new ResourceNotFoundException("vm", vmId);
            }

            if (!getCapabilities().canSuspend(vm.getCurrentState())) {
                //todo
                //should we have a new exception for errors caused by user/client provided data?
                throw new InternalException("Unable to suspend vm in state "+vm.getCurrentState());
            }

            ManagedObjectReference vmRef = new ManagedObjectReference();
            vmRef.setType("VirtualMachine");
            vmRef.setValue(vmId);

            VsphereConnection vsphereConnection = getProvider().getServiceInstance();
            VimPortType vimPortType = vsphereConnection.getVimPort();
            try {
                vimPortType.suspendVMTask(vmRef);
            } catch (InvalidStateFaultMsg invalidStateFaultMsg) {
                throw new InvalidStateException("Vm is in invalid state for suspend: " + invalidStateFaultMsg.getMessage(), invalidStateFaultMsg);
            } catch (RuntimeFaultFaultMsg runtimeFaultFaultMsg) {
                if (runtimeFaultFaultMsg.getFaultInfo() instanceof NoPermission) {
                    throw new AuthenticationException("NoPermission fault when suspending vm", runtimeFaultFaultMsg).withFaultType(AuthenticationException.AuthenticationFaultType.FORBIDDEN);
                }
                throw new GeneralCloudException("Error when suspending vm", runtimeFaultFaultMsg, CloudErrorType.GENERAL);
            } catch (TaskInProgressFaultMsg taskInProgressFaultMsg) {
                throw new TaskInProgressException("TaskInProgressFaultMsg when suspending vm: "+taskInProgressFaultMsg.getMessage());
            }
        }
        finally {
            APITrace.end();
        }
    }

    @Override
    public void terminate(@Nonnull String vmId, String explanation) throws InternalException, CloudException {
        APITrace.begin(getProvider(), "Vm.terminate");
        try {
            VirtualMachine vm = getVirtualMachine(vmId);
            if (vm == null) {
                logger.info("Unable to find vm with id "+vmId+". Termination unnecessary");
                return;
            }

            ManagedObjectReference vmRef = new ManagedObjectReference();
            vmRef.setType("VirtualMachine");
            vmRef.setValue(vmId);

            VsphereConnection vsphereConnection = getProvider().getServiceInstance();
            VimPortType vimPortType = vsphereConnection.getVimPort();

            try {
                if (!vm.getCurrentState().equals(VmState.STOPPED)) {
                    ManagedObjectReference taskMor = vimPortType.powerOffVMTask(vmRef);
                    VsphereMethod method = new VsphereMethod(getProvider());
                    TimePeriod interval = new TimePeriod<Second>(30, TimePeriod.SECOND);
                    if (!method.getOperationComplete(taskMor, interval, 10)) {
                        throw new GeneralCloudException("Error stopping vm prior to termination: "+method.getTaskError().getVal(), CloudErrorType.GENERAL);
                    }
                }
                vimPortType.destroyTask(vmRef);
            } catch (InvalidStateFaultMsg invalidStateFaultMsg) {
                throw new InvalidStateException("Vm is in invalid state for terminate: " + invalidStateFaultMsg.getMessage(), invalidStateFaultMsg);
            } catch (RuntimeFaultFaultMsg runtimeFaultFaultMsg) {
                if (runtimeFaultFaultMsg.getFaultInfo() instanceof NoPermission) {
                    throw new AuthenticationException("NoPermission fault when terminating vm", runtimeFaultFaultMsg).withFaultType(AuthenticationException.AuthenticationFaultType.FORBIDDEN);
                }
                throw new GeneralCloudException("Error when terminating vm", runtimeFaultFaultMsg, CloudErrorType.GENERAL);
            } catch (TaskInProgressFaultMsg taskInProgressFaultMsg) {
                throw new TaskInProgressException("TaskInProgressFaultMsg when terminating vm: "+taskInProgressFaultMsg.getMessage());
            } catch (Exception e) {
                throw new GeneralCloudException("Error when terminating vm", e, CloudErrorType.GENERAL);
            }
        }
        finally {
            APITrace.end();
        }
    }

    public ManagedObjectReference reconfigVMTask(ManagedObjectReference vmRef, VirtualMachineConfigSpec spec) throws CloudException, InternalException {
        VsphereConnection vsphereConnection = getProvider().getServiceInstance();
        VimPortType vimPort = vsphereConnection.getVimPort();
        try {
            return vimPort.reconfigVMTask(vmRef, spec);
        } catch (DuplicateNameFaultMsg duplicateNameFaultMsg) {
            throw new GeneralCloudException("DuplicateName when altering vm: " + duplicateNameFaultMsg.getFaultInfo().getName(), duplicateNameFaultMsg, CloudErrorType.INVALID_USER_DATA);
        } catch (InsufficientResourcesFaultFaultMsg insufficientResourcesFaultFaultMsg) {
            throw new GeneralCloudException("InsufficientResourcesFaultFaultMsg when altering vm", insufficientResourcesFaultFaultMsg, CloudErrorType.CAPACITY);
        } catch (InvalidDatastoreFaultMsg invalidDatastoreFaultMsg) {
            if (invalidDatastoreFaultMsg.getFaultInfo() instanceof InvalidDatastorePath) {
                throw new GeneralCloudException("InvalidDatastore when altering vm: "+((InvalidDatastorePath) invalidDatastoreFaultMsg.getFaultInfo()).getDatastorePath(), invalidDatastoreFaultMsg, CloudErrorType.INVALID_USER_DATA);
            }
            throw new GeneralCloudException("Error when altering vm", invalidDatastoreFaultMsg, CloudErrorType.GENERAL);
        } catch (InvalidNameFaultMsg invalidNameFaultMsg) {
            throw new GeneralCloudException("InvalidNameFault when altering vm: " + invalidNameFaultMsg.getFaultInfo().getName(), invalidNameFaultMsg, CloudErrorType.INVALID_USER_DATA);
        } catch (InvalidStateFaultMsg invalidStateFaultMsg) {
            throw new InvalidStateException("Vm is in invalid state for reconfig: " + invalidStateFaultMsg.getMessage(), invalidStateFaultMsg);
        } catch (RuntimeFaultFaultMsg runtimeFaultFaultMsg) {
            if (runtimeFaultFaultMsg.getFaultInfo() instanceof NoPermission) {
                throw new AuthenticationException("NoPermission fault when altering vm", runtimeFaultFaultMsg).withFaultType(AuthenticationException.AuthenticationFaultType.FORBIDDEN);
            }
            throw new GeneralCloudException("Error when altering vm", runtimeFaultFaultMsg, CloudErrorType.GENERAL);
        } catch (TaskInProgressFaultMsg taskInProgressFaultMsg) {
            throw new TaskInProgressException("TaskInProgressFaultMsg when altering vm: "+taskInProgressFaultMsg.getMessage());
        } catch (Exception e) {
            throw new GeneralCloudException("Error when altering vm", e, CloudErrorType.GENERAL);
        }
    }

    public ManagedObjectReference cloneVmTask(ManagedObjectReference vmRef, ManagedObjectReference vmFolder, String name, VirtualMachineCloneSpec spec) throws CloudException, InternalException {
        VsphereConnection vsphereConnection = getProvider().getServiceInstance();
        VimPortType vimPort = vsphereConnection.getVimPort();
        try {
            return vimPort.cloneVMTask(vmRef, vmFolder, name, spec);
        } catch (InsufficientResourcesFaultFaultMsg insufficientResourcesFaultFaultMsg) {
            throw new GeneralCloudException("InsufficientResourcesFaultFaultMsg when cloning vm", insufficientResourcesFaultFaultMsg, CloudErrorType.CAPACITY);
        } catch (InvalidStateFaultMsg invalidStateFaultMsg) {
            throw new InvalidStateException("Vm is in invalid state for clone: " + invalidStateFaultMsg.getMessage(), invalidStateFaultMsg);
        } catch (RuntimeFaultFaultMsg runtimeFaultFaultMsg) {
            if (runtimeFaultFaultMsg.getFaultInfo() instanceof NoPermission) {
                throw new AuthenticationException("NoPermission fault when cloning vm", runtimeFaultFaultMsg).withFaultType(AuthenticationException.AuthenticationFaultType.FORBIDDEN);
            }
            throw new GeneralCloudException("Error when cloning vm", runtimeFaultFaultMsg, CloudErrorType.GENERAL);
        } catch (TaskInProgressFaultMsg taskInProgressFaultMsg) {
            throw new TaskInProgressException("TaskInProgressFaultMsg when cloning vm: "+taskInProgressFaultMsg.getMessage());
        } catch (InvalidDatastoreFaultMsg invalidDatastoreFaultMsg) {
            if (invalidDatastoreFaultMsg.getFaultInfo() instanceof InvalidDatastorePath) {
                throw new GeneralCloudException("InvalidDatastore when cloning vm: " + ((InvalidDatastorePath) invalidDatastoreFaultMsg.getFaultInfo()).getDatastorePath(), invalidDatastoreFaultMsg, CloudErrorType.INVALID_USER_DATA);
            }
            throw new GeneralCloudException("Error when cloning vm", invalidDatastoreFaultMsg, CloudErrorType.GENERAL);
        } catch (Exception e) {
            throw new GeneralCloudException("Error when cloning vm", e, CloudErrorType.GENERAL);
        }
    }

    private @Nullable VirtualMachine toVirtualMachine(String vmId, VirtualMachineConfigInfo vmInfo, GuestInfo guest, VirtualMachineRuntimeInfo runtime, List<ManagedObjectReference> datastores) throws InternalException, CloudException {
        if( vmInfo == null || vmId == null || guest == null || runtime == null || datastores == null ) {
            return null;
        }
        Map<String, String> properties = new HashMap<String, String>();
        for( int i = 0; i < datastores.size(); i++ ) {
            properties.put("datastore" + i, datastores.get(i).getValue());
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
        }
        if( time == null || time.toGregorianCalendar().getTimeInMillis() < 1L ) {
            server.setLastBootTimestamp(0L);
        }
        else {
            server.setLastBootTimestamp(time.toGregorianCalendar().getTimeInMillis());
        }
        server.setProviderOwnerId(getContext().getAccountNumber());
        server.setTags(properties);
        return server;
    }

    @Nonnull
    public List<ResourcePool> getResourcePools(boolean rootOnly) throws InternalException, CloudException {
        try {
            List<ResourcePool> resourcePools = new ArrayList<ResourcePool>();

            List<SelectionSpec> selectionSpecsArr = getResourcePoolSelectionSpec();
            List<PropertySpec> pSpecs = getResourcePoolPropertySpec();

            RetrieveResult listobcont = retrieveObjectList(getProvider(), "hostFolder", selectionSpecsArr, pSpecs);

            if (listobcont != null) {
                for (ObjectContent oc : listobcont.getObjects()) {
                    ManagedObjectReference rpRef = oc.getObj();
                    String rpId = rpRef.getValue();
                    String owner = null;
                    boolean isRootResourcePool = false;
                    List<DynamicProperty> dps = oc.getPropSet();
                    if (dps != null) {
                        for (DynamicProperty dp : dps) {
                            if (dp.getName().equals("owner")) {
                                ManagedObjectReference clusterRef = (ManagedObjectReference) dp.getVal();
                                owner = clusterRef.getValue();
                            }
                            else if (dp.getName().equals("parent")) {
                                ManagedObjectReference parentRef = (ManagedObjectReference) dp.getVal();
                                if (!parentRef.getType().equals("ResourcePool")) {
                                    isRootResourcePool = true;
                                }
                            }
                        }
                    }
                    if (owner != null) {
                        if ( (rootOnly && isRootResourcePool) || !rootOnly) {
                            ResourcePool resourcePool = new ResourcePool();
                            resourcePool.setDataCenterId(owner);
                            resourcePool.setProvideResourcePoolId(rpId);
                            resourcePools.add(resourcePool);
                        }
                    }
                }
            }
            return resourcePools;
        }
        finally {
            APITrace.end();
        }
    }

    @Nonnull
    public Architecture getArchitecture(@Nonnull VirtualMachineGuestOsIdentifier os) {
        if( os.value().contains("64") ) {
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

    @Nonnull
    private String getWindowsProductLicenseForOSEdition(String osEdition) {
        if (osEdition.contains("Windows 10")) {
            if (osEdition.contains("Professional N")) {
                return "MH37W-N47XK-V7XM9-C7227-GCQG9";
            }
            else if (osEdition.contains("Professional")) {
                return "W269N-WFGWX-YVC9B-4J6C9-T83GX";
            }
            else if (osEdition.contains("Enterprise N")) {
                return "DPH2V-TTNVB-4X9Q3-TJR4H-KHJW4";
            }
            else if (osEdition.contains("Enterprise")) {
                return "NPPR9-FWDCX-D2C8J-H872K-2YT43";
            }
            else if (osEdition.contains("Education N")) {
                return "2WH4N-8QGBV-H22JP-CT43Q-MDWWJ";
            }
            else if (osEdition.contains("Education")) {
                return "NW6C2-QMPVW-D7KKK-3GKT6-VCFB2";
            }
            else if (osEdition.contains("Enterprise 2015 LTSB N")) {
                return "2F77B-TNFGY-69QQF-B8YKP-D69TJ";
            }
            else if (osEdition.contains("Enterprise 2015 LTSB")) {
                return "WNMTR-4C88C-JK8YV-HQ7T2-76DF9";
            }
        }
        else if (osEdition.contains("Windows 8.1")) {
            if (osEdition.contains("Professional N")) {
                return "HMCNV-VVBFX-7HMBH-CTY9B-B4FXY";
            }
            else if (osEdition.contains("Professional")) {
                return "GCRJD-8NW9H-F2CDX-CCM8D-9D6T9";
            }
            else if (osEdition.contains("Enterprise N")) {
                return "TT4HM-HN7YT-62K67-RGRQJ-JFFXW";
            }
            else if (osEdition.contains("Enterprise")) {
                return "MHF9N-XY6XB-WVXMC-BTDCT-MKKG7";
            }
        }
        else if (osEdition.contains("Windows Server 2012 R2")) {
            if (osEdition.contains("Server Standard")) {
                return "D2N9P-3P6X9-2R39C-7RTCD-MDVJX";
            }
            else if (osEdition.contains("Datacenter")) {
                return "W3GGN-FT8W3-Y4M27-J84CP-Q3VJ9";
            }
            else if (osEdition.contains("Essentials")) {
                return "KNC87-3J2TX-XB4WP-VCPJV-M4FWM";
            }
        }
        else if (osEdition.contains("Windows 8")) {
            if (osEdition.contains("Professional N")) {
                return "XCVCF-2NXM9-723PB-MHCB7-2RYQQ";
            }
            else if (osEdition.contains("Professional")) {
                return "NG4HW-VH26C-733KW-K6F98-J8CK4";
            }
            else if (osEdition.contains("Enterprise N")) {
                return "JMNMF-RHW7P-DMY6X-RF3DR-X2BQT";
            }
            else if (osEdition.contains("Enterprise")) {
                return "32JNW-9KQ84-P47T8-D8GGY-CWCK7";
            }
        }
        else if (osEdition.contains("Windows Server 2012")) {
            if (osEdition.contains("Windows Server 2012 N")) {
                return "8N2M2-HWPGY-7PGT9-HGDD8-GVGGY";
            }
            else if (osEdition.contains("Single Language")) {
                return "2WN2H-YGCQR-KFX6K-CD6TF-84YXQ";
            }
            else if (osEdition.contains("Country Specific")) {
                return "4K36P-JN4VD-GDC6V-KDT89-DYFKP";
            }
            else if (osEdition.contains("Server Standard")) {
                return "XC9B7-NBPP2-83J2H-RHMBY-92BT4";
            }
            else if (osEdition.contains("MultiPoint Standard")) {
                return "HM7DN-YVMH3-46JC3-XYTG7-CYQJJ";
            }
            else if (osEdition.contains("MultiPoint Premium")) {
                return "XNH6W-2V9GX-RGJ4K-Y8X6F-QGJ2G";
            }
            else if (osEdition.contains("Datacenter")) {
                return "48HP8-DN98B-MYWDG-T2DCC-8W83P";
            }
            else {
                return "BN3D2-R7TKB-3YPBD-8DRP2-27GG4";
            }
        }
        else if (osEdition.contains("Windows 7")) {
            if (osEdition.contains("Professional N")) {
                return "MRPKT-YTG23-K7D7T-X2JMM-QY7MG";
            }
            if (osEdition.contains("Professional E")) {
                return "W82YF-2Q76Y-63HXB-FGJG9-GF7QX";
            }
            else if (osEdition.contains("Professional")) {
                return "FJ82H-XT6CR-J8D7P-XQJJ2-GPDD4";
            }
            else if (osEdition.contains("Enterprise N")) {
                return "YDRBP-3D83W-TY26F-D46B2-XCKRJ";
            }
            else if (osEdition.contains("Enterprise E")) {
                return "C29WB-22CC8-VJ326-GHFJW-H9DH4";
            }
            else if (osEdition.contains("Enterprise")) {
                return "33PXH-7Y6KF-2VJC9-XBBR8-HVTHH";
            }
        }
        else if (osEdition.contains("Windows Server 2008 R2")) {
            if (osEdition.contains("for Itanium-based Systems")) {
                return "GT63C-RJFQ3-4GMB6-BRFB9-CB83V";
            }
            else if (osEdition.contains("Datacenter")) {
                return "74YFP-3QFB3-KQT8W-PMXWJ-7M648";
            }
            else if (osEdition.contains("Enterprise")) {
                return "489J6-VHDMP-X63PK-3K798-CPX3Y";
            }
            else if (osEdition.contains("Standard")) {
                return "YC6KT-GKW9T-YTKYR-T4X34-R7VHC";
            }
            else if (osEdition.contains("HPC Edition")) {
                return "TT8MH-CG224-D3D7Q-498W2-9QCTX";
            }
            else if (osEdition.contains("Web")) {
                return "6TPJF-RBVHG-WBW2R-86QPH-6RTM4";
            }
        }
        else if (osEdition.contains("Windows Vista")) {
            if (osEdition.contains("Business N")) {
                return "HMBQG-8H2RH-C77VX-27R82-VMQBT";
            }
            else if (osEdition.contains("Business")) {
                return "YFKBB-PQJJV-G996G-VWGXY-2V3X8";
            }
            else if (osEdition.contains("Enterprise N")) {
                return "VTC42-BM838-43QHV-84HX6-XJXKV";
            }
            else if (osEdition.contains("Enterprise")) {
                return "VKK3X-68KWM-X2YGT-QR4M6-4BWMV";
            }
        }
        else if (osEdition.contains("Windows Server 2008")) {
            if (osEdition.contains("for Itanium-Based Systems")) {
                return "4DWFP-JF3DJ-B7DTH-78FJB-PDRHK";
            }
            else if (osEdition.contains("Datacenter without Hyper-V")) {
                return "22XQ2-VRXRG-P8D42-K34TD-G3QQC";
            }
            else if (osEdition.contains("Datacenter")) {
                return "7M67G-PC374-GR742-YH8V4-TCBY3";
            }
            else if (osEdition.contains("HPC")) {
                return "RCTX3-KWVHP-BR6TB-RB6DM-6X7HP";
            }
            else if (osEdition.contains("Enterprise without Hyper-V")) {
                return "39BXF-X8Q23-P2WWT-38T2F-G3FPG";
            }
            else if (osEdition.contains("Enterprise")) {
                return "YQGMW-MPWTJ-34KDK-48M3W-X4Q6V";
            }
            else if (osEdition.contains("Standard without Hyper-V")) {
                return "W7VD6-7JFBR-RX26B-YKQ3Y-6FFFJ";
            }
            else if (osEdition.contains("Standard")) {
                return "TM24T-X9RMF-VWXK6-X8JC9-BFGM2";
            }
        }
        else if (osEdition.contains("Windows Web Server 2008")) {
            return "WYR28-R7TFJ-3X2YQ-YCY4H-M249D";
        }
        logger.warn("Couldn't find a default product key for OS. Returning empty string.");
        return "";
    }

    private String validateName(String name) {
        name = name.toLowerCase().replaceAll("_", "-").replaceAll(" ", "");
        if( name.length() <= 30 ) {
            return name;
        }
        return name.substring(0, 30);
    }
}
