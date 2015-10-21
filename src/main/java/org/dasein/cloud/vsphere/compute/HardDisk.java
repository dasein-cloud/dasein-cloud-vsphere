package org.dasein.cloud.vsphere.compute;

import com.vmware.vim25.*;
import org.dasein.cloud.CloudException;
import org.dasein.cloud.InternalException;
import org.dasein.cloud.ProviderContext;
import org.dasein.cloud.compute.*;
import org.dasein.cloud.dc.Region;
import org.dasein.cloud.dc.ResourcePool;
import org.dasein.cloud.dc.StoragePool;
import org.dasein.cloud.util.APITrace;
import org.dasein.cloud.vsphere.*;
import org.dasein.cloud.vsphere.capabilities.HardDiskCapabilities;
import org.dasein.util.CalendarWrapper;
import org.dasein.util.uom.storage.Kilobyte;
import org.dasein.util.uom.storage.Storage;
import org.dasein.util.uom.time.Second;
import org.dasein.util.uom.time.TimePeriod;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.xml.datatype.XMLGregorianCalendar;
import java.util.ArrayList;
import java.util.List;

/**
 * User: daniellemayne
 * Date: 28/09/2015
 * Time: 10:28
 */
public class HardDisk extends AbstractVolumeSupport<Vsphere> {
    private List<PropertySpec> hardDiskPSpecs;
    private List<PropertySpec> rpPSpecs;
    private List<SelectionSpec> rpSSpecs;
    private List<PropertySpec> spPSpecs;
    private DataCenters dc;

    private ObjectManagement om = new ObjectManagement();

    public HardDisk(@Nonnull Vsphere provider) {
        super(provider);
        dc = provider.getDataCenterServices();
    }

    public RetrieveResult retrieveObjectList(Vsphere provider, @Nonnull String baseFolder, @Nullable List<SelectionSpec> selectionSpecsArr, @Nonnull List<PropertySpec> pSpecs) throws InternalException, CloudException {
        VsphereInventoryNavigation nav = new VsphereInventoryNavigation();
        return nav.retrieveObjectList(provider, baseFolder, selectionSpecsArr, pSpecs);
    }

    public ManagedObjectReference searchDatastores(Vsphere provider, @Nonnull ManagedObjectReference hostDatastoreBrowser, @Nonnull String datastoreFolder, @Nullable HostDatastoreBrowserSearchSpec spec) throws InternalException, CloudException {
        VsphereInventoryNavigation nav = new VsphereInventoryNavigation();
        return nav.searchDatastores(provider, hostDatastoreBrowser, datastoreFolder, spec);
    }

    public Iterable<StoragePool> listStoragePools() throws CloudException, InternalException {
        return dc.listStoragePools();
    }

    public List<PropertySpec> getHardDiskPSpec() {
        if (hardDiskPSpecs == null) {
            hardDiskPSpecs = VsphereTraversalSpec.createPropertySpec(hardDiskPSpecs, "VirtualMachine", false, "runtime.powerState", "config.template", "config.guestFullName", "resourcePool", "config.hardware.device", "datastore");
        }
        return hardDiskPSpecs;
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

    public List<PropertySpec> getDatastorePropertySpec() {
        spPSpecs = VsphereTraversalSpec.createPropertySpec(spPSpecs, "Datastore", false, "browser", "summary.name");
        return spPSpecs;
    }

    @Override
    public void attach(@Nonnull String volumeId, @Nonnull String toServer, @Nonnull String deviceId) throws InternalException, CloudException {
        APITrace.begin(getProvider(), "HardDisk.attach");
        try {
            try {
                Vm vmSupport = getProvider().getComputeServices().getVirtualMachineSupport();
                VirtualMachine vm = vmSupport.getVirtualMachine(toServer);
                if (vm == null) {
                    throw new CloudException("Unable to find vm with id "+toServer);
                }

                Volume volume = getVolume(volumeId);
                if (volume == null) {
                    throw new CloudException("Unable to find volume with id "+volumeId);
                }

                List<PropertySpec> pSpecs = getHardDiskPSpec();
                RetrieveResult props = retrieveObjectList(getProvider(), "vmFolder", null, pSpecs);

                List<VirtualDeviceConfigSpec> machineSpecs = null;
                Object deviceObject = getVMProperty(props, toServer, "config.hardware.device");
                ArrayOfVirtualDevice array = (ArrayOfVirtualDevice) deviceObject;
                List<VirtualDevice> devices = array.getVirtualDevice();

                Object vmMor = getVMProperty(props, toServer, "MOR");
                ManagedObjectReference vmRef = (ManagedObjectReference) vmMor;

                int cKey = 1000;
                boolean scsiExists = false;
                for (VirtualDevice device : devices) {
                    if (device instanceof VirtualSCSIController) {
                        if (!scsiExists) {
                            cKey = device.getKey();
                            scsiExists = true;
                            break;
                        }
                    }
                }

                machineSpecs = new ArrayList<VirtualDeviceConfigSpec>();
                if (!scsiExists) {
                    VirtualDeviceConfigSpec scsiSpec =
                            new VirtualDeviceConfigSpec();
                    scsiSpec.setOperation(VirtualDeviceConfigSpecOperation.ADD);
                    VirtualLsiLogicSASController scsiCtrl =
                            new VirtualLsiLogicSASController();
                    scsiCtrl.setKey(cKey);
                    scsiCtrl.setBusNumber(0);
                    scsiCtrl.setSharedBus(VirtualSCSISharing.NO_SHARING);
                    scsiSpec.setDevice(scsiCtrl);
                    machineSpecs.add(scsiSpec);
                }

                VirtualDisk disk = new VirtualDisk();

                disk.setControllerKey(cKey);
                disk.setUnitNumber(Integer.parseInt(deviceId));

                VirtualDeviceConfigSpec diskSpec =
                        new VirtualDeviceConfigSpec();
                diskSpec.setOperation(VirtualDeviceConfigSpecOperation.ADD);
                diskSpec.setDevice(disk);

                VirtualDiskFlatVer2BackingInfo diskFileBacking = new VirtualDiskFlatVer2BackingInfo();
                String fileName = volume.getTag("filePath");
                diskFileBacking.setFileName(fileName);
                diskFileBacking.setDiskMode("persistent");
                diskFileBacking.setThinProvisioned(true);
                disk.setBacking(diskFileBacking);

                machineSpecs.add(diskSpec);

                VirtualMachineConfigSpec spec = new VirtualMachineConfigSpec();
                spec.getDeviceChange().addAll(machineSpecs);

                CloudException lastError = null;
                ManagedObjectReference taskmor = vmSupport.reconfigVMTask(vmRef, spec);

                VsphereMethod method = new VsphereMethod(getProvider());
                TimePeriod interval = new TimePeriod<Second>(15, TimePeriod.SECOND);

                if( taskmor != null && !method.getOperationComplete(taskmor, interval, 4) ) {
                    lastError = new CloudException("Failed to attach volume: " + method.getTaskError().getVal());
                }
                if( lastError != null ) {
                    throw lastError;
                }
            }
            catch (Exception e) {
                throw new CloudException(e);
            }
        }
        finally {
            APITrace.end();
        }
    }

    @Nonnull
    @Override
    public String createVolume(@Nonnull VolumeCreateOptions options) throws InternalException, CloudException {
        APITrace.begin(getProvider(), "HardDisk.createVolume");
        try {
            if (options.getProviderVirtualMachineId() == null) {
                throw new CloudException("Volumes can only be created in the context of a vm for "+getProvider().getCloudName()+". ProviderVirtualMachineId cannot be null");
            }
            Vm vmSupport = getProvider().getComputeServices().getVirtualMachineSupport();
            VirtualMachine vm = vmSupport.getVirtualMachine(options.getProviderVirtualMachineId());
            if( vm == null ) {
                throw new CloudException("Unable to find vm with id " + options.getProviderVirtualMachineId());
            }

            List<PropertySpec> pSpecs = getHardDiskPSpec();
            RetrieveResult props = retrieveObjectList(getProvider(), "vmFolder", null, pSpecs);

            try {
                List<VirtualDeviceConfigSpec> machineSpecs = null;
                Object deviceObject = getVMProperty(props, options.getProviderVirtualMachineId(), "config.hardware.device");
                ArrayOfVirtualDevice array = (ArrayOfVirtualDevice) deviceObject;
                List<VirtualDevice> devices = array.getVirtualDevice();

                Object datastoreObject = getVMProperty(props, options.getProviderVirtualMachineId(), "datastore");
                ArrayOfManagedObjectReference mors = (ArrayOfManagedObjectReference) datastoreObject;
                ManagedObjectReference datastore = mors.getManagedObjectReference().get(0);

                Object vmMor = getVMProperty(props, options.getProviderVirtualMachineId(), "MOR");
                ManagedObjectReference vmRef = (ManagedObjectReference) vmMor;

                String datastoreId = datastore.getValue();
                StoragePool sp = dc.getStoragePool(datastoreId);
                String datastoreName = sp.getStoragePoolName();

                int cKey = 1000;
                boolean scsiExists = false;
                int numDisks = 0;
                List<String> diskNames = new ArrayList<String>();
                for (VirtualDevice device : devices) {
                    if (device instanceof VirtualSCSIController) {
                        if (!scsiExists) {
                            cKey = device.getKey();
                            scsiExists = true;
                        }
                    }
                    else if (device instanceof VirtualDisk) {
                        numDisks++;
                        VirtualDisk vDisk = (VirtualDisk) device;
                        VirtualDiskFlatVer2BackingInfo bkInfo = (VirtualDiskFlatVer2BackingInfo) vDisk.getBacking();
                        diskNames.add(bkInfo.getFileName());
                    }
                }
                machineSpecs = new ArrayList<VirtualDeviceConfigSpec>();
                if (!scsiExists) {
                    VirtualDeviceConfigSpec scsiSpec = new VirtualDeviceConfigSpec();
                    scsiSpec.setOperation(VirtualDeviceConfigSpecOperation.ADD);
                    VirtualLsiLogicSASController scsiCtrl = new VirtualLsiLogicSASController();
                    scsiCtrl.setKey(cKey);
                    scsiCtrl.setBusNumber(0);
                    scsiCtrl.setSharedBus(VirtualSCSISharing.NO_SHARING);
                    scsiSpec.setDevice(scsiCtrl);
                    machineSpecs.add(scsiSpec);
                }
                // Associate the virtual disk with the scsi controller
                VirtualDisk disk = new VirtualDisk();

                disk.setControllerKey(cKey);
                disk.setUnitNumber(numDisks);
                disk.setCapacityInKB(options.getVolumeSize().intValue() * 1000000);

                VirtualDeviceConfigSpec diskSpec = new VirtualDeviceConfigSpec();
                diskSpec.setOperation(VirtualDeviceConfigSpecOperation.ADD);
                diskSpec.setFileOperation(VirtualDeviceConfigSpecFileOperation.CREATE);
                diskSpec.setDevice(disk);

                VirtualDiskFlatVer2BackingInfo diskFileBacking = new VirtualDiskFlatVer2BackingInfo();
                String fileName2 = "[" + datastoreName + "]" + vm.getName() + "/" + options.getName();
                diskFileBacking.setFileName(fileName2);
                diskFileBacking.setDiskMode("persistent");
                diskFileBacking.setThinProvisioned(false);
                diskFileBacking.setWriteThrough(false);
                disk.setBacking(diskFileBacking);
                machineSpecs.add(diskSpec);

                VirtualMachineConfigSpec spec = new VirtualMachineConfigSpec();
                spec.getDeviceChange().addAll(machineSpecs);

                CloudException lastError;
                ManagedObjectReference taskmor = vmSupport.reconfigVMTask(vmRef, spec);

                VsphereMethod method = new VsphereMethod(getProvider());
                TimePeriod interval = new TimePeriod<Second>(15, TimePeriod.SECOND);

                if( method.getOperationComplete(taskmor, interval, 4) ) {
                    long timeout = System.currentTimeMillis() + (CalendarWrapper.MINUTE * 20L);

                    while( System.currentTimeMillis() < timeout ) {
                        try { Thread.sleep(10000L); }
                        catch( InterruptedException ignore ) { }

                        props = retrieveObjectList(getProvider(), "vmFolder", null, pSpecs);
                        array = (ArrayOfVirtualDevice) getVMProperty(props, options.getProviderVirtualMachineId(), "config.hardware.device");;
                        devices = array.getVirtualDevice();

                        for (VirtualDevice device : devices) {
                            if (device instanceof VirtualDisk) {
                                VirtualDisk vDisk = (VirtualDisk) device;
                                VirtualDiskFlatVer2BackingInfo bkInfo = (VirtualDiskFlatVer2BackingInfo) vDisk.getBacking();
                                String diskFileName = bkInfo.getFileName();
                                if (!diskNames.contains(diskFileName)) {
                                    diskFileName = diskFileName.substring(diskFileName.lastIndexOf("/") + 1);
                                    return diskFileName;
                                }
                            }
                        }
                    }
                    lastError = new CloudException("Unable to identify new volume.");
                }
                else {
                    lastError = new CloudException("Failed to create volume: " + method.getTaskError().getVal());
                }
                if( lastError != null ) {
                    throw lastError;
                }
                throw new CloudException("No volume and no error");
            }
            catch (Exception e) {
                throw new CloudException(e);
            }
        }
        finally {
            APITrace.end();
        }
    }

    @Override
    public void detach(@Nonnull String volumeId, boolean force) throws InternalException, CloudException {
        APITrace.begin(getProvider(), "HardDisk.detach");
        try {
            Volume volume = getVolume(volumeId);
            if (volume == null ) {
                throw new CloudException("Volume not found with id "+volumeId);
            }
            if ( volume.getProviderVirtualMachineId() == null) {
                throw new CloudException("Volume not currently attached");
            }

            Vm vmSupport = getProvider().getComputeServices().getVirtualMachineSupport();
            VirtualMachine vm = vmSupport.getVirtualMachine(volume.getProviderVirtualMachineId());
            if (vm == null) {
                throw new CloudException("Vm not found with id " + volume.getProviderVirtualMachineId());
            }

            List<PropertySpec> pSpecs = getHardDiskPSpec();
            RetrieveResult props = retrieveObjectList(getProvider(), "vmFolder", null, pSpecs);

            try {
                List<VirtualDeviceConfigSpec> machineSpecs = new ArrayList<VirtualDeviceConfigSpec>();
                Object deviceObject = getVMProperty(props, volume.getProviderVirtualMachineId(), "config.hardware.device");
                ArrayOfVirtualDevice array = (ArrayOfVirtualDevice) deviceObject;
                List<VirtualDevice> devices = array.getVirtualDevice();

                Object vmMor = getVMProperty(props, volume.getProviderVirtualMachineId(), "MOR");
                ManagedObjectReference vmRef = (ManagedObjectReference) vmMor;

                String diskId;
                int diskKey = 0;
                int controller = 0;
                boolean found = false;
                for (VirtualDevice device : devices) {
                    if (device instanceof VirtualDisk) {
                        VirtualDisk disk = (VirtualDisk)device;
                        VirtualDeviceFileBackingInfo info = (VirtualDeviceFileBackingInfo)disk.getBacking();
                        String filePath = info.getFileName();
                        diskId = filePath.substring(info.getFileName().lastIndexOf("/") + 1);
                        if (diskId == null || diskId.equals("")) {
                            //cloud has not returned an id so we need to infer it from vm and volume name
                            diskId = vmRef.getValue()+"-"+volume.getName();
                        }
                        if (diskId.equals(volumeId)) {
                            diskKey = disk.getKey();
                            controller = disk.getControllerKey();
                            found = true;
                            break;
                        }
                    }
                }

                if (found) {
                    VirtualDeviceConfigSpec diskSpec =
                            new VirtualDeviceConfigSpec();
                    diskSpec.setOperation(VirtualDeviceConfigSpecOperation.REMOVE);

                    VirtualDisk vd = new VirtualDisk();
                    vd.setKey(diskKey);
                    vd.setControllerKey(controller);
                    diskSpec.setDevice(vd);

                    machineSpecs.add(diskSpec);

                    VirtualMachineConfigSpec spec = new VirtualMachineConfigSpec();
                    spec.getDeviceChange().addAll(machineSpecs);

                    CloudException lastError = null;
                    ManagedObjectReference taskmor = vmSupport.reconfigVMTask(vmRef, spec);

                    VsphereMethod method = new VsphereMethod(getProvider());
                    TimePeriod interval = new TimePeriod<Second>(15, TimePeriod.SECOND);

                    if( !method.getOperationComplete(taskmor, interval, 4) ) {
                        lastError = new CloudException("Failed to update VM: " + method.getTaskError().getVal());
                    }
                    if( lastError != null ) {
                        throw lastError;
                    }
                }
                else {
                    throw new CloudException("Couldn't find device "+volumeId+" to detach in vm "+vm.getName());
                }
            }
            catch (Exception e) {
                throw new CloudException(e);
            }
        }
        finally {
            APITrace.end();
        }
    }

    private transient volatile HardDiskCapabilities capabilities;
    @Override
    public VolumeCapabilities getCapabilities() throws CloudException, InternalException {
        if( capabilities == null ) {
            capabilities = new HardDiskCapabilities(getProvider());
        }
        return capabilities;
    }

    @Nonnull
    @Override
    public Iterable<Volume> listVolumes() throws InternalException, CloudException {
        APITrace.begin(getProvider(), "HardDisk.listVolumes");
        try {
            VsphereMethod method = new VsphereMethod(getProvider());
            TimePeriod interval = new TimePeriod<Second>(15, TimePeriod.SECOND);

            List<Volume> list = new ArrayList<Volume>();
            List<String> fileNames = new ArrayList<String>();
            ProviderContext ctx = getProvider().getContext();
            if (ctx == null) {
                throw new NoContextException();
            }
            if (ctx.getRegionId() == null) {
                throw new CloudException("Region id is not set");
            }

            //get attached volumes
            List<PropertySpec> pSpecs = getHardDiskPSpec();
            RetrieveResult listobcont = retrieveObjectList(getProvider(), "vmFolder", null, pSpecs);

            if (listobcont != null) {
                Iterable<ResourcePool> rps = getAllResourcePoolsIncludingRoot();//return all resourcePools
                List<ObjectContent> objectContents = listobcont.getObjects();
                ManagedObjectReference mo;
                String vmId;
                String dataCenterId;
                Platform guestOs;
                List<DynamicProperty> dps;
                List<Volume> tmpVolList;
                List<String> tmpFileNames;
                boolean skipObject;
                for (ObjectContent oc : objectContents) {
                    mo = oc.getObj();
                    vmId = mo.getValue();
                    dataCenterId = null;
                    guestOs = null;
                    dps = oc.getPropSet();
                    if (dps != null) {
                        tmpVolList = new ArrayList<Volume>();
                        tmpFileNames = new ArrayList<String>();
                        skipObject = false;
                        for (DynamicProperty dp : dps) {
                            if (dp.getName().equals("runtime.powerState")) {
                                VirtualMachinePowerState ps = (VirtualMachinePowerState) dp.getVal();
                                if (ps.equals(VirtualMachinePowerState.SUSPENDED )) {
                                    skipObject = true;
                                }
                            }
                            else if (dp.getName().equals("config.template")) {
                                Boolean isTemplate = (Boolean) dp.getVal();
                                if (isTemplate) {
                                    skipObject = true;
                                }
                            }
                            else if (dp.getName().equals("config.guestFullName")) {
                                guestOs = Platform.guess((String) dp.getVal());
                            }
                            else if (dp.getName().equals("resourcePool")) {
                                ManagedObjectReference ref = (ManagedObjectReference) dp.getVal();
                                String resourcePoolId = ref.getValue();
                                for (ResourcePool rp : rps) {
                                    if (rp.getProvideResourcePoolId().equals(resourcePoolId)) {
                                        dataCenterId = rp.getDataCenterId();
                                        break;
                                    }
                                }
                            }
                            else if (dp.getName().equals("config.hardware.device")) {
                                ArrayOfVirtualDevice avd = (ArrayOfVirtualDevice) dp.getVal();
                                List<VirtualDevice> devices = avd.getVirtualDevice();
                                for (VirtualDevice device : devices) {
                                    if (device instanceof VirtualDisk) {
                                        VirtualDisk disk = (VirtualDisk)device;
                                        Volume vol = toVolume(disk, vmId, ctx.getRegionId());
                                        if (vol != null) {
                                            vol.setGuestOperatingSystem(guestOs);
                                            tmpVolList.add(vol);
                                            tmpFileNames.add(vol.getProviderVolumeId());
                                        }
                                    }
                                }
                            }
                            if (skipObject) {
                                break;
                            }
                        }
                        if (!skipObject) {
                            if (tmpVolList.size() > 0) {
                                for (Volume v : tmpVolList) {
                                    v.setProviderDataCenterId(dataCenterId);
                                }
                                list.addAll(tmpVolList);
                                fileNames.addAll(tmpFileNames);
                            }
                        }
                    }
                }
            }

            // get .vmdk files
            List<PropertySpec> dsPSpecs = getDatastorePropertySpec();

            RetrieveResult dsListobcont = retrieveObjectList(getProvider(), "datastoreFolder", null, dsPSpecs);
            if (dsListobcont != null) {
                Iterable<StoragePool> pools = listStoragePools();
                String dataCenterId;
                String dsName;
                ManagedObjectReference hostDatastoreBrowser;
                List<DynamicProperty> dps;
                for (ObjectContent oc : dsListobcont.getObjects()) {
                    dataCenterId = null;
                    dsName = null;
                    hostDatastoreBrowser = null;
                    dps = oc.getPropSet();
                    if (dps != null) {
                        for (DynamicProperty dp : dps) {
                            if (dp.getName().equals("summary.name")) {
                                dsName = (String) dp.getVal();
                            }
                            else if (dp.getName().equals("browser")) {
                                hostDatastoreBrowser = (ManagedObjectReference) dp.getVal();
                            }
                        }
                    }
                    for (StoragePool pool : pools) {
                        if (pool.getStoragePoolName().equals(dsName)) {
                            dataCenterId = pool.getDataCenterId();
                            break;
                        }
                    }

                    if (dsName != null && hostDatastoreBrowser != null) {
                        try {
                            ManagedObjectReference taskmor = searchDatastores(getProvider(), hostDatastoreBrowser, "[" + dsName + "]", null);
                            if (taskmor != null && method.getOperationComplete(taskmor, interval, 4)) {
                                PropertyChange taskResult = method.getTaskResult();
                                if (taskResult != null && taskResult.getVal() != null) {
                                    ArrayOfHostDatastoreBrowserSearchResults result = (ArrayOfHostDatastoreBrowserSearchResults) taskResult.getVal();
                                    List<HostDatastoreBrowserSearchResults> res = result.getHostDatastoreBrowserSearchResults();
                                    for (HostDatastoreBrowserSearchResults r : res) {
                                        List<FileInfo> files = r.getFile();
                                        if (files != null) {
                                            for (FileInfo file : files) {
                                                String filePath = file.getPath();
                                                if (filePath.endsWith(".vmdk") && !filePath.endsWith("-flat.vmdk")) {
                                                    if (!fileNames.contains(file.getPath())) {
                                                        Volume d = toVolume(file, dataCenterId, ctx.getRegionId());
                                                        if (d != null) {
                                                            d.setTag("filePath", r.getFolderPath() + d.getProviderVolumeId());
                                                            list.add(d);
                                                            fileNames.add(file.getPath());
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        catch (Exception e) {
                            throw new CloudException("Error in processing search datastore request: " + e.getMessage());
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
    public boolean isSubscribed() throws CloudException, InternalException {
        return true;
    }

    @Override
    public void remove(@Nonnull String volumeId) throws InternalException, CloudException {
        APITrace.begin(getProvider(), "HardDisk.remove");
        try {
            Volume volume = getVolume(volumeId);
            if (volume == null ) {
                throw new CloudException("Unable to find volume with id "+volumeId);
            }
            if (volume.getProviderVirtualMachineId() == null) {
                throw new CloudException("Volume is attached to vm " + volume.getProviderVirtualMachineId() + " - removing not allowed");
            }

            VsphereConnection vsphereConnection = getProvider().getServiceInstance();
            ManagedObjectReference fileManager = vsphereConnection.getServiceContent().getFileManager();
            ManagedObjectReference searchIndex = vsphereConnection.getServiceContent().getSearchIndex();
            VimPortType vimPortType = vsphereConnection.getVimPort();
            Region r = dc.getRegion(volume.getProviderRegionId());
            String regionName = r.getName();
            String pathToDC = "/"+regionName;

            ManagedObjectReference datacenter = vimPortType.findByInventoryPath(searchIndex, pathToDC);

            if (datacenter != null) {
                String filePath = volume.getTag("filePath");
                vimPortType.deleteDatastoreFileTask(fileManager, filePath, datacenter);
                //also delete the flat file
                String flatfile = filePath.substring(0, filePath.indexOf(".vmdk")) + "-flat.vmdk";
                vimPortType.deleteDatastoreFileTask(fileManager, flatfile, datacenter);
            }
        }
        catch (Exception e) {
            throw new CloudException("Error in processing remove file request: " + e.getMessage());
        }
        finally {
            APITrace.end();
        }
    }

    @Nonnull
    public List<ResourcePool> getAllResourcePoolsIncludingRoot() throws InternalException, CloudException {
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

    private Object getVMProperty(@Nonnull RetrieveResult rr, @Nonnull String vmId, @Nonnull String propertyName) throws CloudException, InternalException {
        Object object = null;
        if (rr != null) {
            List<ObjectContent> objectContentList = rr.getObjects();
            for (ObjectContent obj : objectContentList) {
                ManagedObjectReference vmRef = obj.getObj();
                String id = vmRef.getValue();
                if (vmId.equals(id)) {
                    if (propertyName.equals("MOR")) {
                        return vmRef;
                    }
                    List<DynamicProperty> dps = obj.getPropSet();
                    for (DynamicProperty dp : dps) {
                        if (dp.getName().equals(propertyName)) {
                            object = dp.getVal();
                            break;
                        }
                    }
                }
            }
        }

        return object;
    }

    private @Nullable Volume toVolume(@Nonnull VirtualDisk disk, @Nonnull String vmId, @Nonnull String regionId) {
        Volume volume = new Volume();

        VirtualDeviceFileBackingInfo info = (VirtualDeviceFileBackingInfo)disk.getBacking();
        String filePath = info.getFileName();
        String fileName = filePath.substring(info.getFileName().lastIndexOf("/") + 1);
        volume.setTag("filePath", filePath);

        volume.setProviderVolumeId(fileName);
        volume.setName(disk.getDeviceInfo().getLabel());
        volume.setProviderRegionId(regionId);
        volume.setDescription(disk.getDeviceInfo().getSummary());
        volume.setCurrentState(VolumeState.AVAILABLE);
        volume.setDeleteOnVirtualMachineTermination(true);
        volume.setDeviceId(disk.getUnitNumber().toString());
        volume.setFormat(VolumeFormat.BLOCK);
        volume.setProviderVirtualMachineId(vmId);
        volume.setSize(new Storage<Kilobyte>(disk.getCapacityInKB(), Storage.KILOBYTE));
        volume.setType(VolumeType.SSD);

        if (volume.getProviderVolumeId() == null) {
            volume.setProviderVolumeId(vmId+"-"+volume.getName());
        }
        if (volume.getDeviceId().equals("0")) {
            volume.setRootVolume(true);
        }
        return volume;
    }

    private @Nullable Volume toVolume(@Nonnull FileInfo disk, @Nullable String dataCenterId, @Nonnull String regionId) {
        Volume volume = new Volume();
        volume.setProviderVolumeId(disk.getPath());
        volume.setName(disk.getPath());
        volume.setProviderDataCenterId(dataCenterId);
        volume.setProviderRegionId(regionId);
        volume.setDescription(disk.getPath());
        volume.setCurrentState(VolumeState.AVAILABLE);
        volume.setDeleteOnVirtualMachineTermination(true);
        volume.setFormat(VolumeFormat.BLOCK);
        if (disk.getFileSize() != null) {
            volume.setSize(new Storage<org.dasein.util.uom.storage.Byte>(disk.getFileSize(), Storage.BYTE));
        }
        volume.setType(VolumeType.SSD);
        XMLGregorianCalendar cal = disk.getModification();
        if (cal != null) {
            volume.setCreationTimestamp(cal.toGregorianCalendar().getTimeInMillis());
        }
        volume.setRootVolume(false);
        return volume;
    }
}
