/**
 * Copyright (C) 2010-2015 Dell, Inc
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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.rmi.RemoteException;
import java.util.*;

import com.vmware.vim25.*;
import org.dasein.cloud.CloudErrorType;
import org.dasein.cloud.CloudException;
import org.dasein.cloud.InternalException;
import org.dasein.cloud.ProviderContext;
import org.dasein.cloud.ResourceStatus;
import org.apache.log4j.Logger;
import org.dasein.cloud.compute.*;
import org.dasein.cloud.dc.DataCenter;
import org.dasein.cloud.identity.ServiceAction;
import org.dasein.cloud.network.RawAddress;
import org.dasein.cloud.network.VLAN;
import org.dasein.cloud.network.VLANSupport;
import org.dasein.cloud.util.APITrace;
import org.dasein.cloud.util.Cache;
import org.dasein.cloud.util.CacheLevel;
import org.dasein.cloud.vsphere.PrivateCloud;

import com.vmware.vim25.mo.ComputeResource;
import com.vmware.vim25.mo.Datacenter;
import com.vmware.vim25.mo.Datastore;
import com.vmware.vim25.mo.Folder;
import com.vmware.vim25.mo.HostSystem;
import com.vmware.vim25.mo.InventoryNavigator;
import com.vmware.vim25.mo.ManagedEntity;
import com.vmware.vim25.mo.ResourcePool;
import com.vmware.vim25.mo.ServiceInstance;
import com.vmware.vim25.mo.Task;
import org.dasein.util.CalendarWrapper;
import org.dasein.util.uom.storage.Gigabyte;
import org.dasein.util.uom.storage.Kilobyte;
import org.dasein.util.uom.storage.Megabyte;
import org.dasein.util.uom.storage.Storage;
import org.dasein.util.uom.time.Day;
import org.dasein.util.uom.time.Minute;
import org.dasein.util.uom.time.TimePeriod;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.servlet.http.HttpServletResponse;

public class Vm extends AbstractVMSupport<PrivateCloud> {
    static private final Logger log = PrivateCloud.getLogger(Vm.class, "std");

    Vm(@Nonnull PrivateCloud provider) {
        super(provider);
    }

    private @Nonnull ServiceInstance getServiceInstance() throws CloudException, InternalException {
        ServiceInstance instance = getProvider().getServiceInstance();

        if( instance == null ) {
            throw new CloudException(CloudErrorType.AUTHENTICATION, HttpServletResponse.SC_UNAUTHORIZED, null, "Unauthorized");
        }
        return instance;
    }

    @Override
    public void start(@Nonnull String serverId) throws InternalException, CloudException {
        APITrace.begin(getProvider(), "Vm.start");
        try {
            ServiceInstance instance = getServiceInstance();

            com.vmware.vim25.mo.VirtualMachine vm = getVirtualMachine(instance, serverId);

            if( vm != null ) {
                try {
                    String datacenter = vm.getResourcePool().getOwner().getName();
                    Datacenter dc = getVmwareDatacenter(vm);

                    if( dc == null ) {
                        throw new CloudException("Could not identify a deployment data center.");
                    }
                    HostSystem host = getHost(vm);
                    Task task = null;
                    if( host == null ) {
                        task = vm.powerOnVM_Task(getBestHost(dc, datacenter));
                    }
                    else {
                        task = vm.powerOnVM_Task(host);
                    }
                    String status = task.waitForTask();

                    if( !status.equals(Task.SUCCESS) ) {
                        if( task.getTaskInfo().getError().getLocalizedMessage().contains("lock the file") ) {
                            throw new CloudException("Failed to start VM: " + task.getTaskInfo().getError().getLocalizedMessage() + ". This vm may be using a disk file already in use");
                        }
                        throw new CloudException("Failed to start VM: " + task.getTaskInfo().getError().getLocalizedMessage());
                    }
                }
                catch( TaskInProgress e ) {
                    throw new CloudException(e);
                }
                catch( InvalidState e ) {
                    throw new CloudException(e);
                }
                catch( RuntimeFault e ) {
                    throw new InternalException(e);
                }
                catch( RemoteException e ) {
                    throw new CloudException(e);
                }
                catch( InterruptedException e ) {
                    throw new CloudException(e);
                }
            }
        }
        finally {
            APITrace.end();
        }
    }

    @Nonnull com.vmware.vim25.mo.VirtualMachine clone(@Nonnull ServiceInstance instance, @Nonnull com.vmware.vim25.mo.VirtualMachine vm, @Nonnull String name, boolean asTemplate) throws InternalException, CloudException {
        APITrace.begin(getProvider(), "Vm.clone(ServiceInstance, VirtualMachine)");
        try {
            try {
                String dcId = getDataCenter(vm);

                if( dcId == null ) {
                    throw new CloudException("Virtual machine " + vm + " has no data center parent");
                }
                name = validateName(name);

                Datacenter dc = null;
                DataCenter ourDC = getProvider().getDataCenterServices().getDataCenter(dcId);
                if( ourDC != null ) {
                    dc = getProvider().getDataCenterServices().getVmwareDatacenterFromVDCId(instance, ourDC.getRegionId());
                }
                else {
                    dc = getProvider().getDataCenterServices().getVmwareDatacenterFromVDCId(instance, dcId);
                }
                ResourcePool pool = vm.getResourcePool();

                if( dc == null ) {
                    throw new CloudException("Invalid DC for cloning operation: " + dcId);
                }
                Folder vmFolder = dc.getVmFolder();

                VirtualMachineConfigSpec config = new VirtualMachineConfigSpec();
                VirtualMachineProduct product = getProduct(vm.getConfig().getHardware());
                String[] sizeInfo = product.getProviderProductId().split(":");
                int cpuCount = Integer.parseInt(sizeInfo[0]);
                long memory = Long.parseLong(sizeInfo[1]);

                config.setName(name);
                config.setAnnotation(vm.getConfig().getAnnotation());
                config.setMemoryMB(memory);
                config.setNumCPUs(cpuCount);
                config.setCpuHotAddEnabled(true);
                config.setNumCoresPerSocket(cpuCount);

                VirtualMachineCloneSpec spec = new VirtualMachineCloneSpec();
                VirtualMachineRelocateSpec location = new VirtualMachineRelocateSpec();

                HostSystem host = getHost(vm);
                if( host == null ) {
                    location.setHost(getBestHost(dc, dcId).getConfig().getHost());
                }
                else {
                    location.setHost(host.getConfig().getHost());
                }
                location.setPool(pool.getConfig().getEntity());
                spec.setLocation(location);
                spec.setPowerOn(false);
                spec.setTemplate(asTemplate);
                spec.setConfig(config);

                Task task = vm.cloneVM_Task(vmFolder, name, spec);
                String status = task.waitForTask();

                if( status.equals(Task.SUCCESS) ) {
                    return ( com.vmware.vim25.mo.VirtualMachine ) ( new InventoryNavigator(vmFolder).searchManagedEntity("VirtualMachine", name) );
                }
                else {
                    throw new CloudException("Failed to create VM: " + task.getTaskInfo().getError().getLocalizedMessage());
                }
            }
            catch( InvalidProperty e ) {
                throw new CloudException(e);
            }
            catch( RuntimeFault e ) {
                throw new InternalException(e);
            }
            catch( RemoteException e ) {
                throw new CloudException(e);
            }
            catch( InterruptedException e ) {
                throw new InternalException(e);
            }
        }
        finally {
            APITrace.end();
        }
    }

    @Override
    public VirtualMachine alterVirtualMachineSize(@Nonnull String virtualMachineId, @Nullable String cpuCount, @Nullable String ramInMB) throws InternalException, CloudException {
        APITrace.begin(getProvider(), "Vm.alterVirtualMachine");
        try {
            ServiceInstance instance = getServiceInstance();
            com.vmware.vim25.mo.VirtualMachine vm = getVirtualMachine(instance, virtualMachineId);

            if( vm != null ) {
                if( cpuCount != null || ramInMB != null ) {

                    int cpuCountVal;
                    long memoryVal;

                    try {
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

                        CloudException lastError;
                        Task task = vm.reconfigVM_Task(spec);

                        String status = task.waitForTask();

                        if( status.equals(Task.SUCCESS) ) {
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
                            lastError = new CloudException("Failed to update VM: " + task.getTaskInfo().getError().getLocalizedMessage());
                        }
                        if( lastError != null ) {
                            throw lastError;
                        }
                        throw new CloudException("No server and no error");
                    }
                    catch( InvalidProperty e ) {
                        throw new CloudException(e);
                    }
                    catch( RuntimeFault e ) {
                        throw new InternalException(e);
                    }
                    catch( RemoteException e ) {
                        throw new CloudException(e);
                    }
                    catch( InterruptedException e ) {
                        throw new CloudException(e);
                    }
                }
            }
            return null;
        }
        finally {
            APITrace.end();
        }
    }

    @Override
    public @Nonnull VirtualMachine clone(@Nonnull String serverId, @Nullable String intoDcId, @Nonnull String name, @Nonnull String description, boolean powerOn, @Nullable String... firewallIds) throws InternalException, CloudException {
        APITrace.begin(getProvider(), "Vm.clone");
        try {
            ServiceInstance instance = getServiceInstance();

            com.vmware.vim25.mo.VirtualMachine vm = getVirtualMachine(instance, serverId);

            if( vm != null ) {
                VirtualMachine target = toServer(clone(instance, vm, name, false), description);

                if( target == null ) {
                    throw new CloudException("Request appeared to succeed, but no VM was created");
                }
                if( powerOn ) {
                    try {
                        Thread.sleep(5000L);
                    }
                    catch( InterruptedException ignore ) { /* ignore */ }
                    String id = target.getProviderVirtualMachineId();

                    if( id == null ) {
                        throw new CloudException("Got a VM without an ID");
                    }
                    start(id);
                }
                return target;
            }
            throw new CloudException("No virtual machine " + serverId + ".");
        }
        finally {
            APITrace.end();
        }
    }

    private transient volatile VMCapabilities capabilities;

    @Override
    public @Nonnull VirtualMachineCapabilities getCapabilities() throws InternalException, CloudException {
        if( capabilities == null ) {
            capabilities = new VMCapabilities(getProvider());
        }
        return capabilities;
    }

    private ManagedEntity[] randomize(ManagedEntity[] source) {
        return source; // TODO: make this random
    }

    private Random random = new Random();

    private @Nonnull VirtualMachine defineFromTemplate(@Nonnull VMLaunchOptions options) throws InternalException, CloudException {
        APITrace.begin(getProvider(), "Vm.define");
        try {
            ProviderContext ctx = getProvider().getContext();

            if( ctx == null ) {
                throw new InternalException("No context was set for this request");
            }
            ServiceInstance instance = getServiceInstance();
            try {
                com.vmware.vim25.mo.VirtualMachine template = getTemplate(instance, options.getMachineImageId());

                if( template == null ) {
                    throw new CloudException("No such template: " + options.getMachineImageId());
                }
                String hostName = validateName(options.getHostName());
                String dataCenterId = options.getDataCenterId();
                String resourceProductStr = options.getStandardProductId();
                String[] items = resourceProductStr.split(":");
                if( items.length == 3 ) {
                    options.withResourcePoolId(items[0]);
                }

                if( dataCenterId == null ) {
                    String rid = ctx.getRegionId();

                    if( rid != null ) {
                        for( DataCenter dsdc : getProvider().getDataCenterServices().listDataCenters(rid) ) {
                            dataCenterId = dsdc.getProviderDataCenterId();
                            if( random.nextInt() % 3 == 0 ) {
                                break;
                            }
                        }
                    }
                }
                ManagedEntity[] pools = null;

                Datacenter vdc = null;

                if( dataCenterId != null ) {
                    DataCenter ourDC = getProvider().getDataCenterServices().getDataCenter(dataCenterId);
                    if( ourDC != null ) {
                        vdc = getProvider().getDataCenterServices().getVmwareDatacenterFromVDCId(instance, ourDC.getRegionId());

                        if( vdc == null ) {
                            throw new CloudException("Unable to identify VDC " + dataCenterId);
                        }

                        if( options.getResourcePoolId() == null ) {
                            ResourcePool pool = getProvider().getDataCenterServices().getResourcePoolFromClusterId(instance, dataCenterId);
                            if( pool != null ) {
                                pools = new ManagedEntity[]{pool};
                            }
                        }
                    }
                    else {
                        vdc = getProvider().getDataCenterServices().getVmwareDatacenterFromVDCId(instance, dataCenterId);
                        if( vdc == null ) {
                            throw new CloudException("Unable to identify VDC " + dataCenterId);
                        }
                        if( options.getResourcePoolId() == null ) {
                            pools = new InventoryNavigator(vdc).searchManagedEntities("ResourcePool");
                        }
                    }
                }

                CloudException lastError = null;

                if( options.getResourcePoolId() != null ) {
                    ResourcePool pool = getProvider().getDataCenterServices().getVMWareResourcePool(options.getResourcePoolId());
                    if( pool != null ) {
                        pools = new ManagedEntity[]{pool};
                    }
                    else {
                        throw new CloudException("Unable to find resource pool with id " + options.getResourcePoolId());
                    }
                }

                for( ManagedEntity p : pools ) {
                    ResourcePool pool = ( ResourcePool ) p;
                    Folder vmFolder = vdc.getVmFolder();
                    if( options.getVmFolderId() != null ) {
                        ManagedEntity tmp = new InventoryNavigator(vmFolder).searchManagedEntity("Folder", options.getVmFolderId());
                        if( tmp != null ) {
                            vmFolder = ( Folder ) tmp;
                        }
                    }

                    VirtualMachineConfigSpec config = new VirtualMachineConfigSpec();
                    String[] vmInfo = options.getStandardProductId().split(":");
                    int cpuCount;
                    long memory;
                    if( vmInfo.length == 2 ) {
                        cpuCount = Integer.parseInt(vmInfo[0]);
                        memory = Long.parseLong(vmInfo[1]);
                    }
                    else {
                        cpuCount = Integer.parseInt(vmInfo[1]);
                        memory = Long.parseLong(vmInfo[2]);
                    }

                    config.setName(hostName);
                    config.setAnnotation(options.getMachineImageId());
                    config.setMemoryMB(memory);
                    config.setNumCPUs(cpuCount);
                    config.setCpuHotAddEnabled(true);
                    config.setNumCoresPerSocket(cpuCount);

                    // record all networks we will end up with so that we can configure NICs correctly
                    List<String> resultingNetworks = new ArrayList<String>();
                    //networking section
                    //borrowed heavily from https://github.com/jedi4ever/jvspherecontrol
                    String vlan = options.getVlanId();
                    int count = 0;
                    if( vlan != null ) {

                        // we don't need to do network config if the selected network
                        // is part of the template config anyway
                        VLANSupport vlanSupport = getProvider().getNetworkServices().getVlanSupport();

                        Iterable<VLAN> accessibleNetworks = vlanSupport.listVlans();
                        boolean addNetwork = true;
                        List<VirtualDeviceConfigSpec> machineSpecs = new ArrayList<VirtualDeviceConfigSpec>();
                        VirtualDevice[] virtualDevices = template.getConfig().getHardware().getDevice();
                        VLAN targetVlan = null;
                        for(VirtualDevice virtualDevice : virtualDevices) {
                            if( virtualDevice instanceof VirtualEthernetCard ) {
                                VirtualEthernetCard veCard = ( VirtualEthernetCard ) virtualDevice;
                                if( veCard.getBacking() instanceof VirtualEthernetCardNetworkBackingInfo ) {
                                    boolean nicDeleted = false;
                                    VirtualEthernetCardNetworkBackingInfo nicBacking = (VirtualEthernetCardNetworkBackingInfo) veCard.getBacking();
                                    if( vlan.equals(nicBacking.getNetwork().getVal()) && veCard.getKey() == 0 ) {
                                        addNetwork = false;
                                    }
                                    else {
                                        for( VLAN accessibleNetwork : accessibleNetworks ) {
                                            if( accessibleNetwork.getProviderVlanId().equals(nicBacking.getNetwork().getVal()) ) {
                                                VirtualDeviceConfigSpec nicSpec = new VirtualDeviceConfigSpec();
                                                nicSpec.setOperation(VirtualDeviceConfigSpecOperation.remove);

                                                nicSpec.setDevice(veCard);
                                                machineSpecs.add(nicSpec);
                                                nicDeleted = true;

                                                if( accessibleNetwork.getProviderVlanId().equals(vlan) ) {
                                                    targetVlan = accessibleNetwork;
                                                }
                                            }
                                            else if( accessibleNetwork.getProviderVlanId().equals(vlan) ) {
                                                targetVlan = accessibleNetwork;
                                            }
                                            if( nicDeleted && targetVlan != null ) {
                                                break;
                                            }
                                        }
                                    }
                                    if( !nicDeleted ) {
                                        resultingNetworks.add(nicBacking.getNetwork().getVal());
                                    }
                                }else if ( veCard.getBacking() instanceof VirtualEthernetCardDistributedVirtualPortBackingInfo ){
                                    boolean nicDeleted = false;
                                    VirtualEthernetCardDistributedVirtualPortBackingInfo nicBacking = (VirtualEthernetCardDistributedVirtualPortBackingInfo) veCard.getBacking();
                                    if( vlan.equals(nicBacking.getPort().getPortgroupKey()) && veCard.getKey() == 0 ) {
                                        addNetwork = false;
                                    }
                                    else {
                                        for( VLAN accessibleNetwork : accessibleNetworks ) {
                                            if( accessibleNetwork.getProviderVlanId().equals(nicBacking.getPort().getPortgroupKey()) ) {
                                                VirtualDeviceConfigSpec nicSpec = new VirtualDeviceConfigSpec();
                                                nicSpec.setOperation(VirtualDeviceConfigSpecOperation.remove);

                                                nicSpec.setDevice(veCard);
                                                machineSpecs.add(nicSpec);
                                                nicDeleted = true;

                                                if( accessibleNetwork.getProviderVlanId().equals(vlan) ) {
                                                    targetVlan = accessibleNetwork;
                                                }
                                            }
                                            else if( accessibleNetwork.getProviderVlanId().equals(vlan) ) {
                                                targetVlan = accessibleNetwork;
                                            }
                                            if( nicDeleted && targetVlan != null ) {
                                                break;
                                            }
                                        }
                                    }
                                    if( !nicDeleted ) {
                                        resultingNetworks.add(nicBacking.getPort().getPortgroupKey());
                                    }
                                }

                            }
                        }

                        if( addNetwork && targetVlan != null ) {
                            VirtualDeviceConfigSpec nicSpec = new VirtualDeviceConfigSpec();
                            nicSpec.setOperation(VirtualDeviceConfigSpecOperation.add);

                            VirtualEthernetCard nic = new VirtualVmxnet3();
                            nic.setConnectable(new VirtualDeviceConnectInfo());
                            nic.connectable.connected = true;
                            nic.connectable.startConnected = true;

                            Description info = new Description();
                            info.setLabel(targetVlan.getName());
                            if( targetVlan.getProviderVlanId().startsWith("network") ) {
                                info.setSummary("Nic for network " + targetVlan.getName());

                                VirtualEthernetCardNetworkBackingInfo nicBacking = new VirtualEthernetCardNetworkBackingInfo();
                                nicBacking.setDeviceName(targetVlan.getName());

                                nic.setAddressType("generated");
                                nic.setBacking(nicBacking);
                                nic.setKey(0);
                            }
                            else {
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
                        config.setDeviceChange(machineSpecs.toArray(new VirtualDeviceConfigSpec[machineSpecs.size()]));
                        // end networking section
                    }

                    VirtualMachineRelocateSpec location = new VirtualMachineRelocateSpec();
                    if( options.getAffinityGroupId() != null ) {
                        Host agSupport = getProvider().getComputeServices().getAffinityGroupSupport();
                        location.setHost(agSupport.getHostSystemForAffinity(options.getAffinityGroupId()).getConfig().getHost());
                    }
                    if( options.getStoragePoolId() != null ) {
                        String locationId = options.getStoragePoolId();

                        Datastore[] datastores = vdc.getDatastores();
                        for( Datastore ds : datastores ) {
                            if( ds.getName().equals(locationId) ) {
                                location.setDatastore(ds.getMOR());
                                break;
                            }
                        }
                    }
                    location.setPool(pool.getConfig().getEntity());

                    boolean isCustomised = false;
                    if( options.getPrivateIp() != null ) {
                        isCustomised = true;
                        log.debug("isCustomised");
                    } else {
                        log.debug("notCustomised");
                    }
                    CustomizationSpec customizationSpec = new CustomizationSpec();
                    if( isCustomised ) {
                        String templatePlatform = template.getConfig().getGuestFullName();
                        if(templatePlatform == null) templatePlatform = template.getName();
                        Platform platform = Platform.guess(templatePlatform.toLowerCase());
                        if (platform.isLinux()) {

                            CustomizationLinuxPrep lPrep = new CustomizationLinuxPrep();
                            lPrep.setDomain(options.getDnsDomain());
                            lPrep.setHostName(new CustomizationVirtualMachineName());
                            customizationSpec.setIdentity(lPrep);
                        }
                        else if( platform.isWindows() ) {
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
                            log.debug("Found win license key: "+serial);
                            log.debug("Guest os version: "+ template.getConfig().getGuestFullName());
                            String guestOS = template.getConfig().getGuestFullName();
                            if (serial == null || serial.length() <= 0) {
                                log.warn("Product license key not specified in launch options. Trying to get default.");
                                CustomFieldValue[] customValues = template.getCustomValue();
                                for (CustomFieldValue value : customValues) {
                                    if (value instanceof CustomFieldStringValue) {
                                        CustomFieldStringValue string = (CustomFieldStringValue) value;
                                        if (string.getValue().contains("2k12r2")) {
                                            guestOS = "Windows Server 2012 R2 Server Standard";
                                            log.debug("Found custom value specifying "+string.getValue());
                                            break;
                                        }
                                    }
                                }
                                serial = getWindowsProductLicenseForOSEdition(guestOS);
                                log.debug("License key found for guest OS version: " + serial);
                            }
                            else {
                                log.debug("Using the user provided key: " + serial);
                            }
                            userData.setProductId(serial);
                            sysprep.setUserData(userData);

                            customizationSpec.setIdentity(sysprep);
                        }
                        else {
                            log.error("Guest customisation could not take place as platform is not linux or windows: " + platform);
                            isCustomised = false;
                        }

                        if( isCustomised ) {
                            CustomizationGlobalIPSettings globalIPSettings = new CustomizationGlobalIPSettings();
                            globalIPSettings.setDnsServerList(options.getDnsServerList());
                            globalIPSettings.setDnsSuffixList(options.getDnsSuffixList());
                            customizationSpec.setGlobalIPSettings(globalIPSettings);

                            CustomizationAdapterMapping adapterMap = new CustomizationAdapterMapping();
                            CustomizationIPSettings adapter = new CustomizationIPSettings();
                            adapter.setDnsDomain(options.getDnsDomain());
                            adapter.setGateway(options.getGatewayList());
                            CustomizationFixedIp fixedIp = new CustomizationFixedIp();
                            fixedIp.setIpAddress(options.getPrivateIp());
                            adapter.setIp(fixedIp);
                            if( options.getMetaData().containsKey("vSphereNetMaskNothingToSeeHere") ) {
                                String netmask = ( String ) options.getMetaData().get("vSphereNetMaskNothingToSeeHere");
                                adapter.setSubnetMask(netmask);
                                log.debug("custom subnet mask: " + netmask);
                            }
                            else {
                                adapter.setSubnetMask("255.255.252.0");
                                log.debug("default subnet mask");
                            }

                            adapterMap.setAdapter(adapter);
                            customizationSpec.setNicSettingMap(Arrays.asList(adapterMap).toArray(new CustomizationAdapterMapping[1]));
                        }
                    }

                    VirtualMachineCloneSpec spec = new VirtualMachineCloneSpec();
                    spec.setLocation(location);
                    spec.setPowerOn(true);
                    spec.setTemplate(false);
                    spec.setConfig(config);
                    if( isCustomised ) {
                        spec.setCustomization(customizationSpec);
                    }

                    Task task = template.cloneVM_Task(vmFolder, hostName, spec);

                    String status = task.waitForTask();

                    if( status.equals(Task.SUCCESS) ) {
                        long timeout = System.currentTimeMillis() + ( CalendarWrapper.MINUTE * 20L );

                        while( System.currentTimeMillis() < timeout ) {
                            try {
                                Thread.sleep(10000L);
                            }
                            catch( InterruptedException ignore ) {
                            }

                            for( VirtualMachine s : listVirtualMachines() ) {
                                if( s.getName().equals(hostName) ) {
                                    if( isCustomised && s.getPlatform().equals(Platform.WINDOWS) ) {
                                        s.setRootPassword(options.getBootstrapPassword());
                                    }
                                    return s;
                                }
                            }
                        }
                        lastError = new CloudException("Unable to identify newly created server.");
                    }
                    else {
                        lastError = new CloudException("Failed to create VM: " + task.getTaskInfo().getError().getLocalizedMessage());
                    }
                }
                if( lastError != null ) {
                    throw lastError;
                }
                throw new CloudException("No server and no error");
            }
            catch( InvalidProperty e ) {
                throw new CloudException(e);
            }
            catch( RuntimeFault e ) {
                throw new InternalException(e);
            }
            catch( RemoteException e ) {
                throw new CloudException(e);
            }
            catch( InterruptedException e ) {
                throw new InternalException(e);
            }
        }
        finally {
            APITrace.end();
        }
    }

    private @Nonnull VirtualMachine defineFromScratch(@Nonnull VMLaunchOptions options) throws InternalException, CloudException {
        APITrace.begin(getProvider(), "Vm.define");
        try {
            ProviderContext ctx = getProvider().getContext();

            if( ctx == null ) {
                throw new InternalException("No context was set for this request");
            }
            ServiceInstance instance = getServiceInstance();
            try {
                String hostName = validateName(options.getHostName());
                String dataCenterId = options.getDataCenterId();
                String resourceProductStr = options.getStandardProductId();
                String imageId = options.getMachineImageId();
                String[] items = resourceProductStr.split(":");
                if( items.length == 3 ) {
                    options.withResourcePoolId(items[0]);
                }

                if( dataCenterId == null ) {
                    String rid = ctx.getRegionId();

                    if( rid != null ) {
                        for( DataCenter dsdc : getProvider().getDataCenterServices().listDataCenters(rid) ) {
                            dataCenterId = dsdc.getProviderDataCenterId();
                            if( random.nextInt() % 3 == 0 ) {
                                break;
                            }
                        }
                    }
                }
                ManagedEntity[] pools = null;

                Datacenter vdc = null;

                if( dataCenterId != null ) {
                    DataCenter ourDC = getProvider().getDataCenterServices().getDataCenter(dataCenterId);
                    if( ourDC != null ) {
                        vdc = getProvider().getDataCenterServices().getVmwareDatacenterFromVDCId(instance, ourDC.getRegionId());

                        if( vdc == null ) {
                            throw new CloudException("Unable to identify VDC " + dataCenterId);
                        }

                        if( options.getResourcePoolId() == null ) {
                            ResourcePool pool = getProvider().getDataCenterServices().getResourcePoolFromClusterId(instance, dataCenterId);
                            if( pool != null ) {
                                pools = new ManagedEntity[]{pool};
                            }
                        }
                    }
                    else {
                        vdc = getProvider().getDataCenterServices().getVmwareDatacenterFromVDCId(instance, dataCenterId);
                        if( options.getResourcePoolId() == null ) {
                            pools = new InventoryNavigator(vdc).searchManagedEntities("ResourcePool");
                        }
                    }
                }

                CloudException lastError = null;

                if( options.getResourcePoolId() != null ) {
                    ResourcePool pool = getProvider().getDataCenterServices().getVMWareResourcePool(options.getResourcePoolId());
                    if( pool != null ) {
                        pools = new ManagedEntity[]{pool};
                    }
                    else {
                        throw new CloudException("Unable to find resource pool with id " + options.getResourcePoolId());
                    }
                }

                for( ManagedEntity p : pools ) {
                    ResourcePool pool = ( ResourcePool ) p;
                    Folder vmFolder = vdc.getVmFolder();
                    if( options.getVmFolderId() != null ) {
                        ManagedEntity tmp = new InventoryNavigator(vmFolder).searchManagedEntity("Folder", options.getVmFolderId());
                        if( tmp != null ) {
                            vmFolder = ( Folder ) tmp;
                        }
                    }

                    VirtualMachineConfigSpec config = new VirtualMachineConfigSpec();
                    String[] vmInfo = options.getStandardProductId().split(":");
                    int cpuCount;
                    long memory;
                    if( vmInfo.length == 2 ) {
                        cpuCount = Integer.parseInt(vmInfo[0]);
                        memory = Long.parseLong(vmInfo[1]);
                    }
                    else {
                        cpuCount = Integer.parseInt(vmInfo[1]);
                        memory = Long.parseLong(vmInfo[2]);
                    }

                    config.setName(hostName);
                    config.setAnnotation(imageId);
                    config.setMemoryMB(memory);
                    config.setNumCPUs(cpuCount);
                    config.setCpuHotAddEnabled(true);
                    config.setNumCoresPerSocket(cpuCount);
                    config.setGuestId(imageId);

                    // create vm file info for the vmx file
                    VirtualMachineFileInfo vmfi = new VirtualMachineFileInfo();
                    String vmDataStoreName = null;
                    Datastore[] datastores = vdc.getDatastores();
                    for( Datastore ds : datastores ) {
                        if( options.getStoragePoolId() != null ) {
                            String locationId = options.getStoragePoolId();
                            if( ds.getName().equals(locationId) ) {
                                vmDataStoreName = ds.getName();
                                break;
                            }
                        }
                        else {
                            //just pick the first datastore as user doesn't care
                            vmDataStoreName = ds.getName();
                            break;
                        }
                    }
                    if( vmDataStoreName == null ) {
                        throw new CloudException("Unable to find a datastore for vm " + hostName);
                    }

                    vmfi.setVmPathName("[" + vmDataStoreName + "]");
                    config.setFiles(vmfi);

                    //networking section
                    //borrowed heavily from https://github.com/jedi4ever/jvspherecontrol
                    String vlan = options.getVlanId();
                    VLANSupport vlanSupport = getProvider().getNetworkServices().getVlanSupport();
                    VLAN fullvlan = vlanSupport.getVlan(vlan);
                    if( vlan != null ) {
                        VirtualDeviceConfigSpec nicSpec = new VirtualDeviceConfigSpec();
                        nicSpec.setOperation(VirtualDeviceConfigSpecOperation.add);

                        VirtualEthernetCard nic = new VirtualVmxnet3();
                        nic.setConnectable(new VirtualDeviceConnectInfo());
                        nic.connectable.connected = true;
                        nic.connectable.startConnected = true;

                        Description info = new Description();
                        info.setLabel(fullvlan.getName());
                        if( fullvlan.getProviderVlanId().startsWith("network") ) {
                            info.setSummary("Nic for network " + fullvlan.getName());

                            VirtualEthernetCardNetworkBackingInfo nicBacking = new VirtualEthernetCardNetworkBackingInfo();
                            nicBacking.setDeviceName(fullvlan.getName());

                            nic.setAddressType("generated");
                            nic.setBacking(nicBacking);
                            nic.setKey(0);
                        }
                        else {
                            info.setSummary("Nic for DVS " + fullvlan.getName());

                            VirtualEthernetCardDistributedVirtualPortBackingInfo nicBacking = new VirtualEthernetCardDistributedVirtualPortBackingInfo();
                            DistributedVirtualSwitchPortConnection connection = new DistributedVirtualSwitchPortConnection();
                            connection.setPortgroupKey(fullvlan.getProviderVlanId());
                            connection.setSwitchUuid(fullvlan.getTag("switch.uuid"));
                            nicBacking.setPort(connection);
                            nic.setAddressType("generated");
                            nic.setBacking(nicBacking);
                            nic.setKey(0);
                        }
                        nicSpec.setDevice(nic);

                        VirtualDeviceConfigSpec[] machineSpecs = new VirtualDeviceConfigSpec[1];
                        machineSpecs[0] = nicSpec;

                        config.setDeviceChange(machineSpecs);
                        // end networking section
                    }
                    else {
                        throw new CloudException("You must choose a network when creating a vm from scratch");
                    }

                    // VirtualMachineRelocateSpec location = new VirtualMachineRelocateSpec();
                    HostSystem host = null;
                    if( options.getAffinityGroupId() != null ) {

                        Host agSupport = getProvider().getComputeServices().getAffinityGroupSupport();
                        host = agSupport.getHostSystemForAffinity(options.getAffinityGroupId());
                    }

                    Task task = vmFolder.createVM_Task(config, pool, host);

                    String status = task.waitForTask();

                    if( status.equals(Task.SUCCESS) ) {
                        long timeout = System.currentTimeMillis() + ( CalendarWrapper.MINUTE * 20L );

                        while( System.currentTimeMillis() < timeout ) {
                            try {
                                Thread.sleep(10000L);
                            }
                            catch( InterruptedException ignore ) {
                            }

                            for( VirtualMachine s : listVirtualMachines() ) {
                                if( s.getName().equals(hostName) ) {
                                    return s;
                                }
                            }
                        }
                        lastError = new CloudException("Unable to identify newly created server.");
                    }
                    else {
                        lastError = new CloudException("Failed to create VM: " + task.getTaskInfo().getError().getLocalizedMessage());
                    }
                }
                if( lastError != null ) {
                    throw lastError;
                }
                throw new CloudException("No server and no error");
            }
            catch( InvalidProperty e ) {
                throw new CloudException(e);
            }
            catch( RuntimeFault e ) {
                throw new InternalException(e);
            }
            catch( RemoteException e ) {
                throw new CloudException(e);
            }
            catch( InterruptedException e ) {
                throw new InternalException(e);
            }
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

    @Nonnull HostSystem getBestHost(@Nonnull Datacenter forDatacenter, @Nonnull String clusterName) throws CloudException, RemoteException {
        APITrace.begin(getProvider(), "Vm.getBestHost");
        try {
            Collection<HostSystem> possibles = getPossibleHosts(forDatacenter, clusterName);

            if( possibles.isEmpty() ) {
                HostSystem ohWell = null;

                for( ManagedEntity me : forDatacenter.getHostFolder().getChildEntity() ) {
                    if( me.getName().equals(clusterName) ) {
                        ComputeResource cluster = ( ComputeResource ) me;

                        for( HostSystem host : cluster.getHosts() ) {
                            if( host.getConfigStatus().equals(ManagedEntityStatus.green) ) {
                                return host;
                            }
                            if( ohWell == null || host.getConfigStatus().equals(ManagedEntityStatus.yellow) ) {
                                ohWell = host;
                            }
                        }
                    }
                }
                if( ohWell == null ) {
                    throw new CloudException("Insufficient capacity for this operation");
                }
                return ohWell;
            }

            return possibles.iterator().next();
        }
        finally {
            APITrace.end();
        }
    }

    @Nonnull Collection<HostSystem> getPossibleHosts(@Nonnull Datacenter dc, @Nonnull String clusterName) throws CloudException, RemoteException {
        APITrace.begin(getProvider(), "Vm.getPossibleHosts");
        try {
            ArrayList<HostSystem> possibles = new ArrayList<HostSystem>();

            for( ManagedEntity me : dc.getHostFolder().getChildEntity() ) {
                if( me.getName().equals(clusterName) ) {
                    ComputeResource cluster = ( ComputeResource ) me;

                    for( HostSystem host : cluster.getHosts() ) {
                        if( host.getConfigStatus().equals(ManagedEntityStatus.green) ) {
                            possibles.add(host);
                        }
                    }
                }
            }
            return possibles;
        }
        finally {
            APITrace.end();
        }
    }

    @Override
    public @Nonnull String getConsoleOutput(@Nonnull String serverId) throws InternalException, CloudException {
        return "";
    }

    private @Nullable String getDataCenter(@Nonnull com.vmware.vim25.mo.VirtualMachine vm) throws InternalException, CloudException {
        APITrace.begin(getProvider(), "Vm.getDataCenter");
        try {
            try {
                return vm.getResourcePool().getOwner().getName();
            }
            catch( RemoteException e ) {
                throw new CloudException(e);
            }
        }
        finally {
            APITrace.end();
        }
    }

    @Override
    public @Nonnull Collection<String> listFirewalls(@Nonnull String serverId) throws InternalException, CloudException {
        return Collections.emptyList();
    }

    private @Nullable HostSystem getHost(@Nonnull com.vmware.vim25.mo.VirtualMachine vm) throws InternalException, CloudException {
        APITrace.begin(getProvider(), "getHostForVM");
        try {
            String dc = getDataCenter(vm);
            ManagedObjectReference vmHost = vm.getRuntime().getHost();

            Host affinityGroupSupport = getProvider().getComputeServices().getAffinityGroupSupport();
            Iterable<HostSystem> hostSystems = affinityGroupSupport.listHostSystems(dc);
            for( HostSystem host : hostSystems ) {
                if( vmHost.getVal().equals(host.getMOR().getVal()) ) {
                    return host;
                }
            }
            return null;
        }
        finally {
            APITrace.end();
        }
    }

    private @Nullable com.vmware.vim25.mo.VirtualMachine getTemplate(@Nonnull ServiceInstance instance, @Nonnull String templateId) throws CloudException, RemoteException, InternalException {
        APITrace.begin(getProvider(), "Vm.getTemplate");
        try {
            Folder folder = getProvider().getVmFolder(instance);
            ManagedEntity[] mes;

            mes = new InventoryNavigator(folder).searchManagedEntities("VirtualMachine");
            if( mes != null && mes.length > 0 ) {
                for( ManagedEntity entity : mes ) {
                    com.vmware.vim25.mo.VirtualMachine template = ( com.vmware.vim25.mo.VirtualMachine ) entity;

                    if( template != null ) {
                        VirtualMachineConfigInfo vminfo = template.getConfig();

                        if( vminfo != null && vminfo.isTemplate() && vminfo.getUuid().equals(templateId) ) {
                            return template;
                        }
                    }
                }
            }
            return null;
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

    @Override
    public @Nullable VirtualMachine getVirtualMachine(@Nonnull String serverId) throws InternalException, CloudException {
        APITrace.begin(getProvider(), "Vm.getVirtualMachine");
        try {
            ServiceInstance instance = getServiceInstance();

            com.vmware.vim25.mo.VirtualMachine vm = getVirtualMachine(instance, serverId);

            if( vm == null ) {
                return null;
            }
            return toServer(vm, null);
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

    @Override
    public Iterable<VirtualMachineProduct> listProducts(
            /** ignored **/ @Nonnull String machineImageId,
            @Nullable VirtualMachineProductFilterOptions options) throws InternalException, CloudException {

        APITrace.begin(getProvider(), "Vm.listProducts(String, VirtualMachineProductFilterOptions)");
        try {
            // get resource pools from cache or live
            Cache<org.dasein.cloud.dc.ResourcePool> cache = Cache.getInstance(
                    getProvider(), "resourcePools", org.dasein.cloud.dc.ResourcePool.class, CacheLevel.REGION_ACCOUNT,
                    new TimePeriod<Minute>(15, TimePeriod.MINUTE));
            Collection<org.dasein.cloud.dc.ResourcePool> rps = ( Collection<org.dasein.cloud.dc.ResourcePool> ) cache.get(getContext());

            if( rps == null ) {
                Collection<DataCenter> dcs = getProvider().getDataCenterServices().listDataCenters(getContext().getRegionId());
                rps = new ArrayList<org.dasein.cloud.dc.ResourcePool>();

                for( DataCenter dc : dcs ) {
                    Collection<org.dasein.cloud.dc.ResourcePool> pools = getProvider().getDataCenterServices().listResourcePools(dc.getProviderDataCenterId());
                    rps.addAll(pools);
                }
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

    private @Nullable VirtualMachineProduct toProduct( @Nonnull JSONObject json ) throws InternalException {
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

    @Override
    public @Nonnull Iterable<ResourceStatus> listVirtualMachineStatus() throws InternalException, CloudException {
        APITrace.begin(getProvider(), "Vm.listVirtualMachineStatus");
        try {
            ServiceInstance instance = getServiceInstance();
            Folder folder = getProvider().getVmFolder(instance);

            ArrayList<ResourceStatus> servers = new ArrayList<ResourceStatus>();
            ManagedEntity[] mes;

            try {
                mes = new InventoryNavigator(folder).searchManagedEntities("VirtualMachine");
            }
            catch( InvalidProperty e ) {
                throw new CloudException("No virtual machine support in cluster: " + e.getMessage());
            }
            catch( RuntimeFault e ) {
                throw new CloudException("Error in processing request to cluster: " + e.getMessage());
            }
            catch( RemoteException e ) {
                throw new CloudException("Error in cluster processing request: " + e.getMessage());
            }

            if( mes != null && mes.length > 0 ) {
                for( ManagedEntity entity : mes ) {
                    ResourceStatus server = toStatus(( com.vmware.vim25.mo.VirtualMachine ) entity);

                    if( server != null ) {
                        servers.add(server);
                    }
                }
            }
            return servers;
        }
        finally {
            APITrace.end();
        }
    }

    @Nullable com.vmware.vim25.mo.VirtualMachine getVirtualMachine(@Nonnull ServiceInstance instance, @Nonnull String vmId) throws CloudException, InternalException {
        APITrace.begin(getProvider(), "Vm.getVirtualMachine(ServiceInstance, String)");
        try {
            Folder folder = getProvider().getVmFolder(instance);
            ManagedEntity[] mes;

            try {
                mes = new InventoryNavigator(folder).searchManagedEntities("VirtualMachine");
            }
            catch( InvalidProperty e ) {
                throw new CloudException("No virtual machine support in cluster: " + e.getMessage());
            }
            catch( RuntimeFault e ) {
                throw new CloudException("Error in processing request to cluster: " + e.getMessage());
            }
            catch( RemoteException e ) {
                throw new CloudException("Error in cluster processing request: " + e.getMessage());
            }

            if( mes != null && mes.length > 0 ) {
                for( ManagedEntity entity : mes ) {
                    com.vmware.vim25.mo.VirtualMachine vm = ( com.vmware.vim25.mo.VirtualMachine ) entity;
                    VirtualMachineConfigInfo cfg = ( vm == null ? null : vm.getConfig() );
                    if( cfg != null && cfg.getInstanceUuid().equals(vmId) ) {
                        return vm;
                    }
                }
            }
            return null;
        }
        finally {
            APITrace.end();
        }
    }

    private @Nullable Datacenter getVmwareDatacenter(@Nonnull com.vmware.vim25.mo.VirtualMachine vm) throws CloudException {
        ManagedEntity parent = vm.getParent();

        if( parent == null ) {
            parent = vm.getParentVApp();
        }
        while( parent != null ) {
            if( parent instanceof Datacenter ) {
                return ( ( Datacenter ) parent );
            }
            parent = parent.getParent();
        }
        return null;
    }

    @Override
    public @Nonnull VirtualMachine launch(@Nonnull VMLaunchOptions withLaunchOptions) throws CloudException, InternalException {
        APITrace.begin(getProvider(), "Vm.launch");
        try {
            ServiceInstance instance = getServiceInstance();
            VirtualMachine server;
            boolean isOSId = false;
            String imageId = withLaunchOptions.getMachineImageId();
            try {
                VirtualMachineGuestOsIdentifier os = VirtualMachineGuestOsIdentifier.valueOf(imageId);
                isOSId = true;
            }
            catch( IllegalArgumentException e ) {
                log.debug("Couldn't find a match to os identifier so trying existing templates instead: " + imageId);
            }
            if( !isOSId ) {
                try {
                    com.vmware.vim25.mo.VirtualMachine template = getTemplate(instance, imageId);
                    if( template == null ) {
                        throw new CloudException("No such template or guest os identifier: " + imageId);
                    }
                    server = defineFromTemplate(withLaunchOptions);
                }
                catch( RemoteException e ) {
                    throw new CloudException(e);
                }
            }
            else {
                server = defineFromScratch(withLaunchOptions);
            }
            return server;
        }
        finally {
            APITrace.end();
        }
    }

    @Override
    public @Nonnull Collection<VirtualMachine> listVirtualMachines() throws InternalException, CloudException {
        APITrace.begin(getProvider(), "Vm.listVirtualMachines");
        try {
            ServiceInstance instance = getServiceInstance();
            Folder folder = getProvider().getVmFolder(instance);

            ArrayList<VirtualMachine> servers = new ArrayList<VirtualMachine>();
            ManagedEntity[] mes;

            try {
                mes = new InventoryNavigator(folder).searchManagedEntities("VirtualMachine");
            }
            catch( InvalidProperty e ) {
                throw new CloudException("No virtual machine support in cluster: " + e.getMessage());
            }
            catch( RuntimeFault e ) {
                throw new CloudException("Error in processing request to cluster: " + e.getMessage());
            }
            catch( RemoteException e ) {
                throw new CloudException("Error in cluster processing request: " + e.getMessage());
            }

            if( mes != null && mes.length > 0 ) {
                for( ManagedEntity entity : mes ) {
                    VirtualMachine server = toServer(( com.vmware.vim25.mo.VirtualMachine ) entity, null);

                    if( server != null ) {
                        servers.add(server);
                    }
                }
            }
            return servers;
        }
        finally {
            APITrace.end();
        }
    }

    @Override
    public @Nonnull String[] mapServiceAction(@Nonnull ServiceAction action) {
        return new String[0];
    }

    @Override
    public void resume(@Nonnull String serverId) throws InternalException, CloudException {
        APITrace.begin(getProvider(), "Vm.resume");
        try {
            ServiceInstance instance = getServiceInstance();

            com.vmware.vim25.mo.VirtualMachine vm = getVirtualMachine(instance, serverId);

            if( vm != null ) {
                try {
                    vm.powerOnVM_Task(null);
                }
                catch( TaskInProgress e ) {
                    throw new CloudException(e);
                }
                catch( InvalidState e ) {
                    throw new CloudException(e);
                }
                catch( RuntimeFault e ) {
                    throw new InternalException(e);
                }
                catch( RemoteException e ) {
                    throw new CloudException(e);
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
            ServiceInstance instance = getServiceInstance();

            com.vmware.vim25.mo.VirtualMachine vm = getVirtualMachine(instance, vmId);

            if( vm != null ) {
                try {
                    vm.powerOffVM_Task();
                }
                catch( TaskInProgress e ) {
                    throw new CloudException(e);
                }
                catch( InvalidState e ) {
                    throw new CloudException(e);
                }
                catch( RuntimeFault e ) {
                    throw new InternalException(e);
                }
                catch( RemoteException e ) {
                    throw new CloudException(e);
                }
            }
        }
        finally {
            APITrace.end();
        }
    }

    @Override
    public void suspend(@Nonnull String serverId) throws InternalException, CloudException {
        APITrace.begin(getProvider(), "Vm.suspend");
        try {
            ServiceInstance instance = getServiceInstance();

            com.vmware.vim25.mo.VirtualMachine vm = getVirtualMachine(instance, serverId);

            if( vm != null ) {
                try {
                    vm.suspendVM_Task();
                }
                catch( TaskInProgress e ) {
                    throw new CloudException(e);
                }
                catch( InvalidState e ) {
                    throw new CloudException(e);
                }
                catch( RuntimeFault e ) {
                    throw new InternalException(e);
                }
                catch( RemoteException e ) {
                    throw new CloudException(e);
                }
            }
        }
        finally {
            APITrace.end();
        }
    }

    @Override
    public void reboot(@Nonnull String serverId) throws CloudException, InternalException {
        APITrace.begin(getProvider(), "Vm.reboot");
        try {
            String id = serverId;
            ServiceInstance instance = getProvider().getServiceInstance();

            com.vmware.vim25.mo.VirtualMachine vm = getVirtualMachine(instance, id);
            if( vm.getRuntime().getPowerState().equals(VirtualMachinePowerState.poweredOn) ) {
                try {
                    vm.rebootGuest();
                }
                catch( TaskInProgress e ) {
                    throw new CloudException(e);
                }
                catch( InvalidState e ) {
                    throw new CloudException(e);
                }
                catch( RuntimeFault e ) {
                    throw new InternalException(e);
                }
                catch( RemoteException e ) {
                    throw new CloudException(e);
                }
            }
            else {
                throw new CloudException("Vm must be powered on before rebooting os");
            }
        }
        finally {
            APITrace.end();
        }
    }

    private void powerOnAndOff(@Nonnull String serverId) {
        APITrace.begin(getProvider(), "Vm.powerOnAndOff");
        try {
            try {
                ServiceInstance instance = getProvider().getServiceInstance();

                com.vmware.vim25.mo.VirtualMachine vm = getVirtualMachine(instance, serverId);
                HostSystem host = getHost(vm);

                if( vm != null ) {
                    Task task = vm.powerOffVM_Task();
                    String status = task.waitForTask();

                    if( !status.equals(Task.SUCCESS) ) {
                        System.err.println("Reboot failed: " + status);
                    }
                    else {
                        try {
                            Thread.sleep(15000L);
                        }
                        catch( InterruptedException ignore ) { /* ignore */ }
                        vm = getVirtualMachine(instance, serverId);
                        vm.powerOnVM_Task(host);
                    }
                }

            }
            catch( Throwable t ) {
                t.printStackTrace();
            }
        }
        finally {
            APITrace.end();
        }
    }

    @Override
    public void terminate(@Nonnull String serverId) throws InternalException, CloudException {
        terminate(serverId, "");
    }

    @Override
    public void terminate(@Nonnull String vmId, String explanation) throws InternalException, CloudException {
        final String id = vmId;

        getProvider().hold();
        Thread t = new Thread() {
            public void run() {
                try {
                    terminateVm(id);
                }
                finally {
                    getProvider().release();
                }
            }
        };

        t.setName("Terminate " + vmId);
        t.setDaemon(true);
        t.start();
    }

    private void terminateVm(@Nonnull String serverId) {
        APITrace.begin(getProvider(), "Vm.terminateVm");
        try {
            try {
                ServiceInstance instance = getServiceInstance();

                com.vmware.vim25.mo.VirtualMachine vm = getVirtualMachine(instance, serverId);

                if( vm != null ) {
                    VirtualMachineRuntimeInfo runtime = vm.getRuntime();

                    if( runtime != null ) {
                        String status = "";

                        VirtualMachinePowerState state = runtime.getPowerState();
                        if( state != VirtualMachinePowerState.poweredOff ) {
                            Task task = vm.powerOffVM_Task();
                            status = task.waitForTask();
                        }

                        if( !status.equals("") && !status.equals(Task.SUCCESS) ) {
                            System.err.println("Termination failed: " + status);
                        }
                        else {
                            try {
                                Thread.sleep(15000L);
                            }
                            catch( InterruptedException ignore ) { /* ignore */ }
                            vm = getVirtualMachine(instance, serverId);
                            if( vm != null ) {
                                vm.destroy_Task();
                            }
                        }
                    }
                }
            }
            catch( Throwable t ) {
                t.printStackTrace();
            }
        }
        finally {
            APITrace.end();
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

    private @Nullable ResourceStatus toStatus(@Nullable com.vmware.vim25.mo.VirtualMachine vm) {
        if( vm == null ) {
            return null;
        }
        VirtualMachineConfigInfo vminfo;

        try {
            vminfo = vm.getConfig();
        }
        catch( RuntimeException e ) {
            return null;
        }
        if( vminfo == null || vminfo.isTemplate() ) {
            return null;
        }
        String id = vminfo.getInstanceUuid();

        if( id == null ) {
            return null;
        }

        VirtualMachineRuntimeInfo runtime = vm.getRuntime();
        VmState vmState = VmState.PENDING;

        if( runtime != null ) {
            VirtualMachinePowerState state = runtime.getPowerState();

            switch( state ) {
                case suspended:
                    vmState = VmState.SUSPENDED;
                    break;
                case poweredOff:
                    vmState = VmState.STOPPED;
                    break;
                case poweredOn:
                    vmState = VmState.RUNNING;
                    break;
                default:
                    System.out.println("DEBUG: Unknown vSphere server state: " + state);
            }
        }
        return new ResourceStatus(id, vmState);
    }

    private @Nullable VirtualMachine toServer(@Nullable com.vmware.vim25.mo.VirtualMachine vm, @Nullable String description) throws InternalException, CloudException {
        if( vm != null ) {
            VirtualMachineConfigInfo vminfo;

            try {
                vminfo = vm.getConfig();
            }
            catch( RuntimeException e ) {
                return null;
            }
            if( vminfo == null || vminfo.isTemplate() ) {
                return null;
            }
            Map<String, String> properties = new HashMap<String, String>();
            VirtualMachineConfigInfoDatastoreUrlPair[] datastoreUrl = vminfo.getDatastoreUrl();
            for( int i = 0; i < datastoreUrl.length; i++ ) {
                properties.put("datastore" + i, datastoreUrl[i].getName());
            }

            ManagedEntity parent = vm.getParent();
            while( parent != null ) {
                if( parent instanceof Folder ) {
                    properties.put("vmFolder", parent.getName());
                    break;
                }
                parent = parent.getParent();
            }

            VirtualMachineGuestOsIdentifier os = VirtualMachineGuestOsIdentifier.valueOf(vminfo.getGuestId());
            VirtualMachine server = new VirtualMachine();

            HostSystem host = getHost(vm);
            if( host != null ) {
                server.setAffinityGroupId(host.getName());
            }

            server.setName(vm.getName());
            server.setPlatform(Platform.guess(vminfo.getGuestFullName()));
            server.setProviderVirtualMachineId(vm.getConfig().getInstanceUuid());
            server.setPersistent(true);
            server.setImagable(true);
            server.setClonable(true);
            server.setArchitecture(getArchitecture(os));
            if( description == null ) {
                description = vm.getName();
            }
            server.setDescription(description);
            server.setProductId(getProduct(vminfo.getHardware()).getProviderProductId());
            String imageId = vminfo.getAnnotation();

            if( imageId != null && imageId.length() > 0 && !imageId.contains(" ") ) {
                server.setProviderMachineImageId(imageId);
            }
            else {
                server.setProviderMachineImageId(getContext().getAccountNumber() + "-unknown");
            }
            String dc = getDataCenter(vm);

            if( dc == null ) {
                return null;
            }
            DataCenter ourDC = getProvider().getDataCenterServices().getDataCenter(dc);
            if( ourDC != null ) {
                server.setProviderDataCenterId(dc);
                server.setProviderRegionId(ourDC.getRegionId());
            }
            else if( dc.equals(getContext().getRegionId()) ) {
                // env doesn't have clusters?
                server.setProviderDataCenterId(dc + "-a");
                server.setProviderRegionId(dc);
            }
            else {
                return null;
            }

            try {
                ResourcePool rp = vm.getResourcePool();
                if( rp != null ) {
                    String id = getProvider().getDataCenterServices().getIdForResourcePool(rp);
                    server.setResourcePoolId(id);
                }
            }
            catch( InvalidProperty ex ) {
                throw new CloudException(ex);
            }
            catch( RuntimeFault ex ) {
                throw new CloudException(ex);
            }
            catch( RemoteException ex ) {
                throw new CloudException(ex);
            }

            if ( vminfo.getHardware().getDevice() != null && vminfo.getHardware().getDevice().length > 0 ) {
                VirtualDevice[] virtualDevices = vm.getConfig().getHardware().getDevice();
                for(VirtualDevice virtualDevice : virtualDevices) {
                    if( virtualDevice instanceof VirtualEthernetCard ) {
                        VirtualEthernetCard veCard = ( VirtualEthernetCard ) virtualDevice;
                        if( veCard.getBacking() instanceof VirtualEthernetCardNetworkBackingInfo ) {
                            VirtualEthernetCardNetworkBackingInfo nicBacking = (VirtualEthernetCardNetworkBackingInfo) veCard.getBacking();
                            String net = nicBacking.getNetwork().getVal();
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

            GuestInfo guest = vm.getGuest();
            if( guest != null ) {
                if( guest.getHostName() != null ) {
                    server.setPrivateDnsAddress(guest.getHostName());
                }
                if( guest.getIpAddress() != null ) {
                    server.setProviderAssignedIpAddressId(guest.getIpAddress());
                }
                GuestNicInfo[] nicInfoArray = guest.getNet();
                if( nicInfoArray != null && nicInfoArray.length > 0 ) {
                    List<RawAddress> pubIps = new ArrayList<RawAddress>();
                    List<RawAddress> privIps = new ArrayList<RawAddress>();
                    for( GuestNicInfo nicInfo : nicInfoArray ) {
                        String[] ipAddresses = nicInfo.getIpAddress();
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
                    if( privIps != null && privIps.size() > 0 ) {
                        RawAddress[] rawPriv = privIps.toArray(new RawAddress[privIps.size()]);
                        server.setPrivateAddresses(rawPriv);
                    }
                    if( pubIps != null && pubIps.size() > 0 ) {
                        RawAddress[] rawPub = pubIps.toArray(new RawAddress[pubIps.size()]);
                        server.setPublicAddresses(rawPub);
                    }
                }
            }

            VirtualMachineRuntimeInfo runtime = vm.getRuntime();

            if( runtime != null ) {
                VirtualMachinePowerState state = runtime.getPowerState();

                if( server.getCurrentState() == null ) {
                    switch( state ) {
                        case suspended:
                            server.setCurrentState(VmState.SUSPENDED);
                            break;
                        case poweredOff:
                            server.setCurrentState(VmState.STOPPED);
                            break;
                        case poweredOn:
                            server.setCurrentState(VmState.RUNNING);
                            server.setRebootable(true);
                            break;
                    }
                }
                Calendar suspend = runtime.getSuspendTime();
                Calendar time = runtime.getBootTime();

                if( suspend == null || suspend.getTimeInMillis() < 1L ) {
                    server.setLastPauseTimestamp(-1L);
                }
                else {
                    server.setLastPauseTimestamp(suspend.getTimeInMillis());
                    server.setCreationTimestamp(server.getLastPauseTimestamp());
                }
                if( time == null || time.getTimeInMillis() < 1L ) {
                    server.setLastBootTimestamp(0L);
                }
                else {
                    server.setLastBootTimestamp(time.getTimeInMillis());
                    server.setCreationTimestamp(server.getLastBootTimestamp());
                }
            }
            server.setProviderOwnerId(getContext().getAccountNumber());
            server.setTags(properties);
            return server;
        }
        return null;
    }

    private String validateName(String name) {
        name = name.toLowerCase().replaceAll("_", "-").replaceAll(" ", "");
        if( name.length() <= 30 ) {
            return name;
        }
        return name.substring(0, 30);
    }

    @Override
    public boolean isSubscribed() throws CloudException, InternalException {
        return ( getProvider().getServiceInstance() != null );
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
        log.warn("Couldn't find a default product key for OS. Returning empty string.");
        return "";
    }
}
