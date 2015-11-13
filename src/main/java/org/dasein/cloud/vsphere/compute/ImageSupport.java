package org.dasein.cloud.vsphere.compute;


import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.vmware.vim25.*;
import org.apache.log4j.Logger;
import org.dasein.cloud.AsynchronousTask;
import org.dasein.cloud.CloudException;
import org.dasein.cloud.InternalException;
import org.dasein.cloud.ProviderContext;
import org.dasein.cloud.compute.AbstractImageSupport;
import org.dasein.cloud.compute.Architecture;
import org.dasein.cloud.compute.ImageClass;
import org.dasein.cloud.compute.ImageCreateOptions;
import org.dasein.cloud.compute.ImageFilterOptions;
import org.dasein.cloud.compute.MachineImage;
import org.dasein.cloud.compute.MachineImageState;
import org.dasein.cloud.compute.Platform;
import org.dasein.cloud.compute.VirtualMachine;
import org.dasein.cloud.vsphere.*;
import org.dasein.cloud.vsphere.capabilities.VsphereImageCapabilities;

import org.dasein.cloud.util.APITrace;
import org.dasein.util.uom.time.Second;
import org.dasein.util.uom.time.TimePeriod;


/**
 *
 */
public class ImageSupport extends AbstractImageSupport<Vsphere> {
    private VsphereImageCapabilities capabilities;
    static private final Logger logger = Vsphere.getLogger(ImageSupport.class);
    private List<PropertySpec> templatePSpec;

    public ImageSupport(@Nonnull Vsphere provider) {
        super(provider);
    }

    @Override
    public VsphereImageCapabilities getCapabilities() throws CloudException, InternalException {
        if (capabilities == null) {
            capabilities = new VsphereImageCapabilities(getProvider());
        }
        return capabilities;
    }

    @Override
    public MachineImage getImage(@Nonnull String providerImageId) throws CloudException, InternalException {
        ImageFilterOptions options = ImageFilterOptions.getInstance(true, providerImageId);
        Iterable<MachineImage> images = listImages(options);
        if (images.iterator().hasNext()) {
            return images.iterator().next();
        }
        return null;
    }

    @Override
    public boolean isSubscribed() throws CloudException, InternalException {
        return true;
    }

    public RetrieveResult retrieveObjectList(Vsphere provider, @Nonnull String baseFolder, @Nullable List<SelectionSpec> selectionSpecsArr, @Nonnull List<PropertySpec> pSpecs) throws InternalException, CloudException {
        VsphereInventoryNavigation nav = new VsphereInventoryNavigation();
        return nav.retrieveObjectList(provider, baseFolder, selectionSpecsArr, pSpecs);
    }

    public List<PropertySpec> getTemplatePSpec() {
        templatePSpec = VsphereTraversalSpec.createPropertySpec(templatePSpec, "VirtualMachine", false, "summary.config", "summary.overallStatus");
        return templatePSpec;
    }

    @Nonnull
    @Override
    public Iterable<MachineImage> listImages(@Nullable ImageFilterOptions opts) throws CloudException, InternalException {
        APITrace.begin(getProvider(), "ImageSupport.listImages");

        final ImageFilterOptions options;

        if (opts == null) {
            options = ImageFilterOptions.getInstance();
        } else {
            options = opts;
        }

        ArrayList<MachineImage> machineImages = new ArrayList<MachineImage>();

        ProviderContext ctx = getProvider().getContext();
        if (ctx == null) {
            throw new NoContextException();
        }
        if (ctx.getRegionId() == null) {
            throw new CloudException("Region id is not set");
        }

        try {
            String regionId = ctx.getRegionId();

            List<PropertySpec> pSpecs = getTemplatePSpec();

            // get the data from the server
            RetrieveResult props = retrieveObjectList(getProvider(), "vmFolder", null, pSpecs);

            if (props != null) {
                for (ObjectContent oc : props.getObjects()) {
                    ManagedObjectReference templateRef = oc.getObj();
                    MachineImageState state = MachineImageState.ERROR;

                    VirtualMachineConfigSummary virtualMachineConfigSummary = null;
                    List<DynamicProperty> dps = oc.getPropSet();
                    if (dps != null) {
                        for (DynamicProperty dp : dps) {
                            if (dp.getName().equals("summary.config")) {
                                virtualMachineConfigSummary = (VirtualMachineConfigSummary) dp.getVal();
                            } else if (dp.getName().equals("summary.overallStatus")) {
                                ManagedEntityStatus s = (ManagedEntityStatus) dp.getVal();
                                if (s.equals(ManagedEntityStatus.GREEN) || s.equals(ManagedEntityStatus.YELLOW)) {
                                    state = MachineImageState.ACTIVE;
                                }
                            }
                        }
                        if (virtualMachineConfigSummary != null) {
                            if (virtualMachineConfigSummary.isTemplate()) {
                                MachineImage machineImage = toMachineImage(templateRef.getValue(), virtualMachineConfigSummary, regionId, state);
                                if (options.matches(machineImage)) {
                                    machineImages.add(machineImage);
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            throw new CloudException(e);
        } finally {
            APITrace.end();
        }

        return machineImages;
    }

    @Override
    protected MachineImage capture(@Nonnull ImageCreateOptions options, @Nullable AsynchronousTask<MachineImage> task) throws CloudException, InternalException {
        APITrace.begin(getProvider(), "Image.capture");
        try {
            String vmId = options.getVirtualMachineId();

            if( vmId == null ) {
                throw new CloudException("You must specify a virtual machine to capture");
            }
            VirtualMachine vm = getProvider().getComputeServices().getVirtualMachineSupport().getVirtualMachine(vmId);
            if( vm == null ) {
                throw new CloudException("No such virtual machine for imaging: " + vmId);
            }

            ManagedObjectReference templateRef = new ManagedObjectReference();
            templateRef.setType("VirtualMachine");
            templateRef.setValue(vmId);

            ManagedObjectReference vmFolderRef = new ManagedObjectReference();
            vmFolderRef.setType("Folder");
            vmFolderRef.setValue(vm.getTag("vmFolderId").toString());

            ManagedObjectReference hostRef = new ManagedObjectReference();
            hostRef.setType("HostSystem");
            hostRef.setValue(vm.getAffinityGroupId());

            ManagedObjectReference rpRef = new ManagedObjectReference();
            rpRef.setType("ResourcePool");
            rpRef.setValue(vm.getResourcePoolId());

            VirtualMachineRelocateSpec location = new VirtualMachineRelocateSpec();
            location.setHost(hostRef);
            location.setPool(rpRef);

            VirtualMachineCloneSpec spec = new VirtualMachineCloneSpec();
            spec.setLocation(location);
            spec.setPowerOn(false);
            spec.setTemplate(true);

            MachineImage img;
            ManagedObjectReference taskMor = getProvider().getComputeServices().getVirtualMachineSupport().cloneVmTask(templateRef, vmFolderRef, options.getName(), spec);
            VsphereMethod method = new VsphereMethod(getProvider());
            TimePeriod interval = new TimePeriod<Second>(30, TimePeriod.SECOND);
            if (method.getOperationComplete(taskMor, interval, 10)) {
                PropertyChange pChange = method.getTaskResult();
                ManagedObjectReference newVmRef = (ManagedObjectReference) pChange.getVal();

                img =  getImage(newVmRef.getValue());
            }
            else {
                throw new CloudException("Failed to capture image: " + method.getTaskError().getVal());
            }

            if( task != null ) {
                task.completeWithResult(img);
            }
            return img;
        }
        finally {
            APITrace.end();
        }
    }

    @Override
    public void remove(@Nonnull String providerImageId, boolean checkState) throws CloudException, InternalException {
        APITrace.begin(getProvider(), "ImageSupport.remove");

        MachineImage image = getImage(providerImageId);
        if (image == null) {
            logger.info("Machine image does not exist, removal unnecessary");
            return;
        }

        VsphereConnection vsphereConnection = getProvider().getServiceInstance();
        VimPortType vimPort = vsphereConnection.getVimPort();

        ManagedObjectReference templateToBeDeleted = new ManagedObjectReference();
        templateToBeDeleted.setType("VirtualMachine");
        templateToBeDeleted.setValue(providerImageId);

        try {
            ManagedObjectReference taskmor = vimPort.destroyTask(templateToBeDeleted);
            VsphereMethod method = new VsphereMethod(getProvider());
            TimePeriod<Second> interval = new TimePeriod<Second>(30, TimePeriod.SECOND);
            method.getOperationComplete(taskmor, interval, 10);
        }
        catch (RuntimeFaultFaultMsg runtimeFaultFaultMsg) {
            throw new CloudException("RuntimeFaultFaultMsg when deleting image", runtimeFaultFaultMsg);
        } catch (VimFaultFaultMsg vimFaultFaultMsg) {
            throw new CloudException("VimFaultFaultMsg when deleting image", vimFaultFaultMsg);
        }
        finally {
            APITrace.end();
        }
    }

    private MachineImage toMachineImage(String imageId, VirtualMachineConfigSummary imageInfo, String regionId, MachineImageState state) {
        VirtualMachineGuestOsIdentifier os;
        Platform platform;
        Architecture arch;

        try {
            os = VirtualMachineGuestOsIdentifier.fromValue(imageInfo.getGuestId());
            platform = Platform.guess(imageInfo.getGuestFullName());
        }
        catch( IllegalArgumentException e ) {
            System.out.println("DEBUG: No such guest in enum: " + imageInfo.getGuestId());
            os = null;
            platform = Platform.guess(imageInfo.getGuestId());
        }
        if( os == null ) {
            arch = (imageInfo.getGuestId().contains("64") ? Architecture.I64 : Architecture.I32);
        }
        else {
            arch = getArchitecture(os);
        }

        return MachineImage.getInstance(
                getProvider().getContext().getAccountNumber(),
                regionId,
                imageId,
                ImageClass.MACHINE,
                state,
                imageInfo.getName(),
                imageInfo.getGuestFullName(),
                arch,
                platform);
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
}
